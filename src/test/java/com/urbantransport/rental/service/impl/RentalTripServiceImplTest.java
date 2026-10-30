package com.urbantransport.rental.service.impl;

import com.urbantransport.rental.domain.ProcessedEventEntity;
import com.urbantransport.rental.domain.RentalChannel;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import com.urbantransport.rental.event.EventTypes;
import com.urbantransport.rental.repository.ProcessedEventRepository;
import com.urbantransport.rental.repository.RentalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalTripServiceImplTest {

    private static final LocalDate DEPARTURE = LocalDate.now(ZoneId.of("America/Guatemala")).plusDays(5);
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RENTAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRIP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BUS_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DRIVER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String REASON = "La licencia del chofer vence antes del viaje";

    @Mock
    private RentalRepository rentalRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Captor
    private ArgumentCaptor<ProcessedEventEntity> processedEventCaptor;

    private RentalTripServiceImpl tripService;

    @BeforeEach
    void setUp() {
        tripService = new RentalTripServiceImpl(rentalRepository, processedEventRepository);
    }

    @Nested
    class ApplyPrivateTripRejected {

        @Test
        void assignedRental_returnsToPaidWithReasonAndWithoutResources() {
            RentalEntity rental = rental(RentalStatus.ASSIGNED, null);
            lock(rental);

            tripService.applyPrivateTripRejected(EVENT_ID, EventTypes.PRIVATE_TRIP_REJECTED, RENTAL_ID, REASON);

            assertEquals(RentalStatus.PAID, rental.getStatus());
            assertNull(rental.getBusId());
            assertNull(rental.getDriverId());
            assertEquals(REASON, rental.getAssignmentRejectionReason());
            assertMarkedProcessed(EventTypes.PRIVATE_TRIP_REJECTED);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "  ")
        void missingReason_storesGenericReason(String reason) {
            RentalEntity rental = rental(RentalStatus.ASSIGNED, null);
            lock(rental);

            tripService.applyPrivateTripRejected(EVENT_ID, EventTypes.PRIVATE_TRIP_REJECTED, RENTAL_ID, reason);

            assertEquals("trip-service rechazó la asignación", rental.getAssignmentRejectionReason());
        }

        @Test
        void longReason_isTruncatedToColumnSize() {
            RentalEntity rental = rental(RentalStatus.ASSIGNED, null);
            lock(rental);

            tripService.applyPrivateTripRejected(EVENT_ID, EventTypes.PRIVATE_TRIP_REJECTED, RENTAL_ID,
                    "R".repeat(300));

            assertEquals("R".repeat(255), rental.getAssignmentRejectionReason());
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = "ASSIGNED", mode = EnumSource.Mode.EXCLUDE)
        void rentalNotAssigned_keepsDataAndMarksEvent(RentalStatus status) {
            RentalEntity rental = rental(status, null);
            lock(rental);

            tripService.applyPrivateTripRejected(EVENT_ID, EventTypes.PRIVATE_TRIP_REJECTED, RENTAL_ID, REASON);

            assertEquals(status, rental.getStatus());
            assertEquals(BUS_ID, rental.getBusId());
            assertNull(rental.getAssignmentRejectionReason());
            assertMarkedProcessed(EventTypes.PRIVATE_TRIP_REJECTED);
        }

        @Test
        void unknownRental_throwsSoTheMessageGoesToDlt() {
            when(rentalRepository.findByIdForUpdate(RENTAL_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class, () -> tripService.applyPrivateTripRejected(EVENT_ID,
                    EventTypes.PRIVATE_TRIP_REJECTED, RENTAL_ID, REASON));

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            tripService.applyPrivateTripRejected(EVENT_ID, EventTypes.PRIVATE_TRIP_REJECTED, RENTAL_ID, REASON);

            verifyNoInteractions(rentalRepository);
            verify(processedEventRepository, never()).save(any());
        }
    }

    @Nested
    class ApplyTripCompleted {

        @Test
        void oneWayRental_isCompletedWithItsOnlyTrip() {
            RentalEntity rental = rental(RentalStatus.ASSIGNED, null);
            lock(rental);

            tripService.applyTripCompleted(EVENT_ID, EventTypes.TRIP_COMPLETED, RENTAL_ID, TRIP_ID);

            assertEquals(1, rental.getCompletedTrips());
            assertEquals(RentalStatus.COMPLETED, rental.getStatus());
            assertMarkedProcessed(EventTypes.TRIP_COMPLETED);
        }

        @Test
        void roundTripRental_staysAssignedAfterFirstTrip() {
            RentalEntity rental = rental(RentalStatus.ASSIGNED, DEPARTURE.plusDays(1));
            lock(rental);

            tripService.applyTripCompleted(EVENT_ID, EventTypes.TRIP_COMPLETED, RENTAL_ID, TRIP_ID);

            assertEquals(1, rental.getCompletedTrips());
            assertEquals(RentalStatus.ASSIGNED, rental.getStatus());
            assertMarkedProcessed(EventTypes.TRIP_COMPLETED);
        }

        @Test
        void roundTripRental_isCompletedWithSecondTrip() {
            RentalEntity rental = rental(RentalStatus.ASSIGNED, DEPARTURE.plusDays(1));
            rental.setCompletedTrips(1);
            lock(rental);

            tripService.applyTripCompleted(EVENT_ID, EventTypes.TRIP_COMPLETED, RENTAL_ID, TRIP_ID);

            assertEquals(2, rental.getCompletedTrips());
            assertEquals(RentalStatus.COMPLETED, rental.getStatus());
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = "ASSIGNED", mode = EnumSource.Mode.EXCLUDE)
        void rentalNotAssigned_keepsCounterAndMarksEvent(RentalStatus status) {
            RentalEntity rental = rental(status, null);
            lock(rental);

            tripService.applyTripCompleted(EVENT_ID, EventTypes.TRIP_COMPLETED, RENTAL_ID, TRIP_ID);

            assertEquals(status, rental.getStatus());
            assertNull(rental.getCompletedTrips());
            assertMarkedProcessed(EventTypes.TRIP_COMPLETED);
        }

        @Test
        void unknownRental_throwsSoTheMessageGoesToDlt() {
            when(rentalRepository.findByIdForUpdate(RENTAL_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class,
                    () -> tripService.applyTripCompleted(EVENT_ID, EventTypes.TRIP_COMPLETED, RENTAL_ID, TRIP_ID));

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            tripService.applyTripCompleted(EVENT_ID, EventTypes.TRIP_COMPLETED, RENTAL_ID, TRIP_ID);

            verifyNoInteractions(rentalRepository);
            verify(processedEventRepository, never()).save(any());
        }
    }

    private static RentalEntity rental(RentalStatus status, LocalDate returnDate) {
        return RentalEntity.builder()
                .id(RENTAL_ID)
                .channel(RentalChannel.ONLINE)
                .customerNit("12345678")
                .branchId(UUID.fromString("66666666-6666-6666-6666-666666666666"))
                .originText("Parque Central")
                .destinationText("Panajachel")
                .departureDate(DEPARTURE)
                .returnDate(returnDate)
                .passengers(30)
                .distanceKm(new BigDecimal("120.00"))
                .estimatedPrice(new BigDecimal("1350.00"))
                .busId(BUS_ID)
                .driverId(DRIVER_ID)
                .status(status)
                .build();
    }

    private void lock(RentalEntity rental) {
        when(rentalRepository.findByIdForUpdate(RENTAL_ID)).thenReturn(Optional.of(rental));
    }

    private void assertMarkedProcessed(String eventType) {
        verify(processedEventRepository).save(processedEventCaptor.capture());
        assertEquals(EVENT_ID, processedEventCaptor.getValue().getEventId());
        assertEquals(eventType, processedEventCaptor.getValue().getEventType());
    }
}

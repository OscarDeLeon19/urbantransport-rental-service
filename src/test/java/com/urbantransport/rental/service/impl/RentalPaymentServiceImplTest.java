package com.urbantransport.rental.service.impl;

import com.urbantransport.rental.domain.ProcessedEventEntity;
import com.urbantransport.rental.domain.RentalChannel;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import com.urbantransport.rental.event.EventTypes;
import com.urbantransport.rental.event.PaymentRequestedPayload;
import com.urbantransport.rental.event.RentalEventPayload;
import com.urbantransport.rental.repository.ProcessedEventRepository;
import com.urbantransport.rental.repository.RentalRepository;
import com.urbantransport.rental.service.OutboxService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalPaymentServiceImplTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Guatemala"));
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RENTAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_RENTAL_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CUSTOMER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant REQUESTED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final BigDecimal PRICE = new BigDecimal("2400.00");

    @Mock
    private RentalRepository rentalRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private OutboxService outboxService;

    @Captor
    private ArgumentCaptor<ProcessedEventEntity> processedEventCaptor;

    private RentalPaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new RentalPaymentServiceImpl(rentalRepository, processedEventRepository, outboxService);
    }

    @Nested
    class ApplyPaymentCompleted {

        @Test
        void quotedRental_becomesPaidAndRecordsEvent() {
            RentalEntity rental = pendingPayment(RENTAL_ID);
            rental.setPaymentFailureReason("INSUFFICIENT_FUNDS");
            lock(rental);

            paymentService.applyPaymentCompleted(EVENT_ID, EventTypes.PAYMENT_COMPLETED, RENTAL_ID);

            assertEquals(RentalStatus.PAID, rental.getStatus());
            assertNull(rental.getPaymentFailureReason());
            assertEquals(TODAY, rental.getPaymentDate());
            verify(outboxService).record(EventTypes.AGGREGATE_RENTAL, RENTAL_ID, EventTypes.RENTAL_PAID,
                    RentalEventPayload.fromEntity(rental));
            assertMarkedProcessed(EventTypes.PAYMENT_COMPLETED);
        }

        @Test
        void alreadyPaidRental_onlyMarksEvent() {
            RentalEntity rental = pendingPayment(RENTAL_ID);
            rental.setStatus(RentalStatus.PAID);
            lock(rental);

            paymentService.applyPaymentCompleted(EVENT_ID, EventTypes.PAYMENT_COMPLETED, RENTAL_ID);

            assertEquals(RentalStatus.PAID, rental.getStatus());
            verifyNoInteractions(outboxService);
            assertMarkedProcessed(EventTypes.PAYMENT_COMPLETED);
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = {"QUOTED", "PAID"}, mode = EnumSource.Mode.EXCLUDE)
        void rentalInOtherStatus_keepsStatusAndMarksEvent(RentalStatus status) {
            RentalEntity rental = pendingPayment(RENTAL_ID);
            rental.setStatus(status);
            lock(rental);

            paymentService.applyPaymentCompleted(EVENT_ID, EventTypes.PAYMENT_COMPLETED, RENTAL_ID);

            assertEquals(status, rental.getStatus());
            verifyNoInteractions(outboxService);
            assertMarkedProcessed(EventTypes.PAYMENT_COMPLETED);
        }

        @Test
        void unknownRental_throwsSoTheMessageGoesToDlt() {
            when(rentalRepository.findByIdForUpdate(RENTAL_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class,
                    () -> paymentService.applyPaymentCompleted(EVENT_ID, EventTypes.PAYMENT_COMPLETED, RENTAL_ID));

            verifyNoInteractions(outboxService);
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            paymentService.applyPaymentCompleted(EVENT_ID, EventTypes.PAYMENT_COMPLETED, RENTAL_ID);

            verifyNoInteractions(rentalRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }
    }

    @Nested
    class ApplyPaymentFailed {

        @Test
        void quotedRental_keepsQuotedWithReasonAndReleasesPayment() {
            RentalEntity rental = pendingPayment(RENTAL_ID);
            lock(rental);

            paymentService.applyPaymentFailed(EVENT_ID, EventTypes.PAYMENT_FAILED, RENTAL_ID, "INSUFFICIENT_FUNDS");

            assertEquals(RentalStatus.QUOTED, rental.getStatus());
            assertEquals("INSUFFICIENT_FUNDS", rental.getPaymentFailureReason());
            assertNull(rental.getPaymentDate());
            assertNull(rental.getPaymentRequestedAt());
            verifyNoInteractions(outboxService);
            assertMarkedProcessed(EventTypes.PAYMENT_FAILED);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        void missingReason_storesUnknown(String reason) {
            RentalEntity rental = pendingPayment(RENTAL_ID);
            lock(rental);

            paymentService.applyPaymentFailed(EVENT_ID, EventTypes.PAYMENT_FAILED, RENTAL_ID, reason);

            assertEquals("UNKNOWN", rental.getPaymentFailureReason());
        }

        @Test
        void longReason_isTruncatedToColumnSize() {
            RentalEntity rental = pendingPayment(RENTAL_ID);
            lock(rental);

            paymentService.applyPaymentFailed(EVENT_ID, EventTypes.PAYMENT_FAILED, RENTAL_ID, "X".repeat(60));

            assertEquals("X".repeat(40), rental.getPaymentFailureReason());
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = "QUOTED", mode = EnumSource.Mode.EXCLUDE)
        void rentalNotQuoted_keepsDataAndMarksEvent(RentalStatus status) {
            RentalEntity rental = pendingPayment(RENTAL_ID);
            rental.setStatus(status);
            lock(rental);

            paymentService.applyPaymentFailed(EVENT_ID, EventTypes.PAYMENT_FAILED, RENTAL_ID, "INSUFFICIENT_FUNDS");

            assertEquals(status, rental.getStatus());
            assertNull(rental.getPaymentFailureReason());
            assertEquals(TODAY, rental.getPaymentDate());
            assertMarkedProcessed(EventTypes.PAYMENT_FAILED);
        }

        @Test
        void unknownRental_throwsSoTheMessageGoesToDlt() {
            when(rentalRepository.findByIdForUpdate(RENTAL_ID)).thenReturn(Optional.empty());

            assertThrows(IllegalStateException.class, () -> paymentService.applyPaymentFailed(EVENT_ID,
                    EventTypes.PAYMENT_FAILED, RENTAL_ID, "INSUFFICIENT_FUNDS"));

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        void alreadyProcessedEvent_isIgnored() {
            when(processedEventRepository.existsById(EVENT_ID)).thenReturn(true);

            paymentService.applyPaymentFailed(EVENT_ID, EventTypes.PAYMENT_FAILED, RENTAL_ID, "INSUFFICIENT_FUNDS");

            verifyNoInteractions(rentalRepository, outboxService);
            verify(processedEventRepository, never()).save(any());
        }
    }

    @Nested
    class ResendStalePayments {

        @Test
        void stalePayments_areResentWithTheirDataAndNewRequestTime() {
            RentalEntity first = pendingPayment(RENTAL_ID);
            RentalEntity second = pendingPayment(OTHER_RENTAL_ID);
            Instant requestedBefore = Instant.now();
            when(rentalRepository.findStalePaymentsForUpdate(RentalStatus.QUOTED, requestedBefore))
                    .thenReturn(List.of(first, second));

            int resent = paymentService.resendStalePayments(requestedBefore);

            assertEquals(2, resent);
            assertTrue(first.getPaymentRequestedAt().isAfter(REQUESTED_AT));
            assertTrue(second.getPaymentRequestedAt().isAfter(REQUESTED_AT));
            verify(outboxService).record(EventTypes.AGGREGATE_RENTAL, RENTAL_ID, EventTypes.PAYMENT_REQUESTED,
                    new PaymentRequestedPayload(EventTypes.REFERENCE_RENTAL, RENTAL_ID, CUSTOMER_ID, "12345678", PRICE,
                            TODAY));
            verify(outboxService).record(EventTypes.AGGREGATE_RENTAL, OTHER_RENTAL_ID, EventTypes.PAYMENT_REQUESTED,
                    PaymentRequestedPayload.from(second));
        }

        @Test
        void withoutStalePayments_recordsNothing() {
            Instant requestedBefore = Instant.now();
            when(rentalRepository.findStalePaymentsForUpdate(RentalStatus.QUOTED, requestedBefore))
                    .thenReturn(List.of());

            assertEquals(0, paymentService.resendStalePayments(requestedBefore));

            verifyNoInteractions(outboxService);
        }
    }

    private static RentalEntity pendingPayment(UUID rentalId) {
        return RentalEntity.builder()
                .id(rentalId)
                .channel(RentalChannel.ONLINE)
                .customerNit("12345678")
                .customerUserId(UUID.fromString("55555555-5555-5555-5555-555555555555"))
                .customerId(CUSTOMER_ID)
                .branchId(UUID.fromString("66666666-6666-6666-6666-666666666666"))
                .originText("Parque Central")
                .destinationText("Panajachel")
                .departureDate(TODAY.plusDays(5))
                .passengers(30)
                .distanceKm(new BigDecimal("120.00"))
                .estimatedPrice(new BigDecimal("1350.00"))
                .confirmedPrice(PRICE)
                .paymentDate(TODAY)
                .paymentRequestedAt(REQUESTED_AT)
                .status(RentalStatus.QUOTED)
                .build();
    }

    private void lock(RentalEntity rental) {
        when(rentalRepository.findByIdForUpdate(rental.getId())).thenReturn(Optional.of(rental));
    }

    private void assertMarkedProcessed(String eventType) {
        verify(processedEventRepository).save(processedEventCaptor.capture());
        assertEquals(EVENT_ID, processedEventCaptor.getValue().getEventId());
        assertEquals(eventType, processedEventCaptor.getValue().getEventType());
    }
}

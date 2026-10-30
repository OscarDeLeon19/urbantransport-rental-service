package com.urbantransport.rental.service.impl;

import com.urbantransport.rental.client.CustomerClient;
import com.urbantransport.rental.client.CustomerSummaryResponse;
import com.urbantransport.rental.domain.RentalChannel;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalSettingsEntity;
import com.urbantransport.rental.domain.RentalStatus;
import com.urbantransport.rental.dto.AssignRentalRequest;
import com.urbantransport.rental.dto.CounterRentalRequest;
import com.urbantransport.rental.dto.CreateRentalRequest;
import com.urbantransport.rental.dto.PageResponse;
import com.urbantransport.rental.dto.PayRentalRequest;
import com.urbantransport.rental.dto.QuoteRentalRequest;
import com.urbantransport.rental.dto.RejectRentalRequest;
import com.urbantransport.rental.dto.RentalEstimateResponse;
import com.urbantransport.rental.dto.RentalResponse;
import com.urbantransport.rental.dto.RescheduleRentalRequest;
import com.urbantransport.rental.event.EventTypes;
import com.urbantransport.rental.event.PaymentRequestedPayload;
import com.urbantransport.rental.event.RentalAssignedPayload;
import com.urbantransport.rental.event.RentalEventPayload;
import com.urbantransport.rental.exception.ApiException;
import com.urbantransport.rental.exception.ErrorCodes;
import com.urbantransport.rental.repository.RentalRepository;
import com.urbantransport.rental.repository.RentalSettingsRepository;
import com.urbantransport.rental.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalServiceImplTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDate TODAY = LocalDate.now(ZONE);
    private static final LocalDate DEPARTURE = TODAY.plusDays(5);
    private static final LocalDate RETURN = DEPARTURE.plusDays(1);

    private static final UUID RENTAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BRANCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_BRANCH_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID OTHER_USER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CUSTOMER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID BUS_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID DRIVER_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID ADMIN_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private static final String NIT = "12345678";
    private static final String OTHER_NIT = "87654321";
    private static final String TOKEN = "token-de-prueba";
    private static final String ORIGIN = "Parque Central";
    private static final String DESTINATION = "Panajachel";
    private static final int PASSENGERS = 30;
    private static final BigDecimal DISTANCE = new BigDecimal("120.00");
    private static final BigDecimal PRICE_PER_KM = new BigDecimal("10.00");
    private static final BigDecimal SURCHARGE = new BigDecimal("5.00");
    private static final BigDecimal ONE_WAY_PRICE = new BigDecimal("1350.00");
    private static final BigDecimal ROUND_TRIP_PRICE = new BigDecimal("2550.00");
    private static final BigDecimal CONFIRMED_PRICE = new BigDecimal("2400.00");

    @Mock
    private RentalRepository rentalRepository;

    @Mock
    private RentalSettingsRepository rentalSettingsRepository;

    @Mock
    private OutboxService outboxService;

    @Mock
    private CustomerClient customerClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Captor
    private ArgumentCaptor<RentalEntity> rentalCaptor;

    @Captor
    private ArgumentCaptor<Object> payloadCaptor;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private RentalServiceImpl rentalService;

    @BeforeEach
    void setUp() {
        rentalService = new RentalServiceImpl(rentalRepository, rentalSettingsRepository, outboxService,
                customerClient, transactionTemplate);
        ReflectionTestUtils.setField(rentalService, "zone", ZONE);
    }

    @Nested
    class Estimate {

        @Test
        void oneWay_returnsBreakdownWithOneLeg() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.of(settings()));

            RentalEstimateResponse response = rentalService.estimate(createRequest(DEPARTURE, null));

            assertEquals(BRANCH_ID, response.getBranchId());
            assertEquals(DISTANCE, response.getDistanceKm());
            assertEquals(1, response.getLegs());
            assertEquals(PASSENGERS, response.getPassengers());
            assertEquals(PRICE_PER_KM, response.getPricePerKm());
            assertEquals(SURCHARGE, response.getPassengerSurcharge());
            assertEquals(ONE_WAY_PRICE, response.getEstimatedPrice());
            verifyNoInteractions(rentalRepository, outboxService);
        }

        @Test
        void roundTrip_countsDistanceTwice() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.of(settings()));

            RentalEstimateResponse response = rentalService.estimate(createRequest(DEPARTURE, RETURN));

            assertEquals(2, response.getLegs());
            assertEquals(ROUND_TRIP_PRICE, response.getEstimatedPrice());
        }

        @Test
        void departureTodayAndSameDayReturn_areAccepted() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.of(settings()));

            RentalEstimateResponse response = rentalService.estimate(createRequest(TODAY, TODAY));

            assertEquals(ROUND_TRIP_PRICE, response.getEstimatedPrice());
        }

        @Test
        void departureInPast_throwsBadRequest() {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    () -> rentalService.estimate(createRequest(TODAY.minusDays(1), null)));

            verifyNoInteractions(rentalSettingsRepository);
        }

        @Test
        void returnBeforeDeparture_throwsBadRequest() {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_BEFORE_DEPARTURE,
                    () -> rentalService.estimate(createRequest(DEPARTURE, DEPARTURE.minusDays(1))));

            verifyNoInteractions(rentalSettingsRepository);
        }

        @Test
        void branchWithoutSettings_throwsUnprocessable() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_SETTINGS_NOT_FOUND,
                    () -> rentalService.estimate(createRequest(DEPARTURE, null)));
        }
    }

    @Nested
    class RequestOnline {

        @Test
        void validRequest_savesTrimmedRequestedRentalAndRecordsEvent() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.of(settings()));
            when(rentalRepository.save(any(RentalEntity.class))).thenAnswer(savedWithId());
            CreateRentalRequest request = new CreateRentalRequest(BRANCH_ID, "  " + ORIGIN + " ",
                    " " + DESTINATION + "  ", DEPARTURE, RETURN, PASSENGERS, DISTANCE);

            RentalResponse response = rentalService.requestOnline(request, USER_ID, NIT);

            verify(rentalRepository).save(rentalCaptor.capture());
            RentalEntity saved = rentalCaptor.getValue();
            assertEquals(RentalChannel.ONLINE, saved.getChannel());
            assertEquals(NIT, saved.getCustomerNit());
            assertEquals(USER_ID, saved.getCustomerUserId());
            assertEquals(BRANCH_ID, saved.getBranchId());
            assertEquals(ORIGIN, saved.getOriginText());
            assertEquals(DESTINATION, saved.getDestinationText());
            assertEquals(DEPARTURE, saved.getDepartureDate());
            assertEquals(RETURN, saved.getReturnDate());
            assertEquals(PASSENGERS, saved.getPassengers());
            assertEquals(DISTANCE, saved.getDistanceKm());
            assertEquals(ROUND_TRIP_PRICE, saved.getEstimatedPrice());
            assertEquals(RentalStatus.REQUESTED, saved.getStatus());
            assertNull(saved.getConfirmedPrice());
            assertNull(saved.getPaymentDate());
            verify(outboxService).record(EventTypes.AGGREGATE_RENTAL, RENTAL_ID, EventTypes.RENTAL_REQUESTED,
                    RentalEventPayload.fromEntity(saved));
            assertEquals(RENTAL_ID, response.getId());
            assertEquals(RentalStatus.REQUESTED, response.getStatus());
        }

        @Test
        void departureInPast_savesNothing() {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    () -> rentalService.requestOnline(createRequest(TODAY.minusDays(1), null), USER_ID, NIT));

            verifyNoInteractions(rentalSettingsRepository, rentalRepository, outboxService);
        }

        @Test
        void branchWithoutSettings_savesNothing() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_SETTINGS_NOT_FOUND,
                    () -> rentalService.requestOnline(createRequest(DEPARTURE, null), USER_ID, NIT));

            verifyNoInteractions(rentalRepository, outboxService);
        }
    }

    @Nested
    class RegisterCounter {

        @Test
        void registeredNit_savesPaidRentalWithCustomerNitAndRecordsEvent() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.of(settings()));
            when(customerClient.getByNit(NIT, TOKEN))
                    .thenReturn(new CustomerSummaryResponse(CUSTOMER_ID, NIT, "Juan Perez", false, true));
            runTransactionsInline();
            when(rentalRepository.save(any(RentalEntity.class))).thenAnswer(savedWithId());
            LocalDate paymentDate = TODAY.minusDays(1);

            RentalResponse response = rentalService.registerCounter(
                    counterRequest(" 1234567-8 ", new BigDecimal("1900.5"), paymentDate), BRANCH_ID, TOKEN);

            verify(rentalRepository).save(rentalCaptor.capture());
            RentalEntity saved = rentalCaptor.getValue();
            assertEquals(RentalChannel.COUNTER, saved.getChannel());
            assertEquals(NIT, saved.getCustomerNit());
            assertNull(saved.getCustomerUserId());
            assertEquals(BRANCH_ID, saved.getBranchId());
            assertEquals(ORIGIN, saved.getOriginText());
            assertEquals(DESTINATION, saved.getDestinationText());
            assertEquals(ONE_WAY_PRICE, saved.getEstimatedPrice());
            assertEquals(new BigDecimal("1900.50"), saved.getConfirmedPrice());
            assertEquals(paymentDate, saved.getPaymentDate());
            assertEquals(RentalStatus.PAID, saved.getStatus());
            verify(outboxService).record(EventTypes.AGGREGATE_RENTAL, RENTAL_ID, EventTypes.RENTAL_PAID,
                    RentalEventPayload.fromEntity(saved));
            assertEquals(RentalStatus.PAID, response.getStatus());
        }

        @Test
        void genericNit_skipsCustomerLookup() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.of(settings()));
            runTransactionsInline();
            when(rentalRepository.save(any(RentalEntity.class))).thenAnswer(savedWithId());

            rentalService.registerCounter(counterRequest(" cf ", CONFIRMED_PRICE, null), BRANCH_ID, TOKEN);

            verify(rentalRepository).save(rentalCaptor.capture());
            assertEquals("CF", rentalCaptor.getValue().getCustomerNit());
            assertEquals(TODAY, rentalCaptor.getValue().getPaymentDate());
            verifyNoInteractions(customerClient);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ABC", "K1234", "12345X", "1", "   "})
        void invalidNit_throwsBadRequest(String nit) {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_NIT,
                    () -> rentalService.registerCounter(counterRequest(nit, CONFIRMED_PRICE, null), BRANCH_ID, TOKEN));

            verifyNoInteractions(customerClient, rentalSettingsRepository, transactionTemplate, outboxService);
        }

        @Test
        void futurePaymentDate_throwsBadRequest() {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.PAYMENT_DATE_IN_FUTURE,
                    () -> rentalService.registerCounter(counterRequest(NIT, CONFIRMED_PRICE, TODAY.plusDays(1)),
                            BRANCH_ID, TOKEN));

            verifyNoInteractions(customerClient, rentalSettingsRepository, transactionTemplate);
        }

        @Test
        void departureInPast_throwsBadRequest() {
            CounterRentalRequest request = new CounterRentalRequest(NIT, ORIGIN, DESTINATION, TODAY.minusDays(1),
                    null, PASSENGERS, DISTANCE, CONFIRMED_PRICE, null);

            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    () -> rentalService.registerCounter(request, BRANCH_ID, TOKEN));

            verifyNoInteractions(customerClient, transactionTemplate);
        }

        @Test
        void branchWithoutSettings_doesNotCallCustomer() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_SETTINGS_NOT_FOUND,
                    () -> rentalService.registerCounter(counterRequest(NIT, CONFIRMED_PRICE, null), BRANCH_ID, TOKEN));

            verifyNoInteractions(customerClient, transactionTemplate);
        }

        @Test
        void unknownCustomer_propagatesErrorAndSavesNothing() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.of(settings()));
            ApiException notFound = new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.CUSTOMER_NOT_FOUND,
                    "El cliente no esta registrado");
            when(customerClient.getByNit(NIT, TOKEN)).thenThrow(notFound);

            ApiException thrown = assertThrows(ApiException.class,
                    () -> rentalService.registerCounter(counterRequest(NIT, CONFIRMED_PRICE, null), BRANCH_ID, TOKEN));

            assertSame(notFound, thrown);
            verifyNoInteractions(transactionTemplate, rentalRepository, outboxService);
        }
    }

    @Nested
    class Quote {

        @Test
        void withoutPrice_confirmsEstimateAndRecordsEvent() {
            RentalEntity rental = rental(RentalStatus.REQUESTED);
            lock(rental);

            RentalResponse response = rentalService.quote(RENTAL_ID, new QuoteRentalRequest(null), BRANCH_ID);

            assertEquals(ONE_WAY_PRICE, rental.getConfirmedPrice());
            assertEquals(RentalStatus.QUOTED, rental.getStatus());
            verify(outboxService).record(EventTypes.AGGREGATE_RENTAL, RENTAL_ID, EventTypes.RENTAL_QUOTED,
                    RentalEventPayload.fromEntity(rental));
            assertEquals(RentalStatus.QUOTED, response.getStatus());
            assertEquals(ONE_WAY_PRICE, response.getConfirmedPrice());
        }

        @Test
        void withPrice_confirmsRoundedPrice() {
            RentalEntity rental = rental(RentalStatus.REQUESTED);
            lock(rental);

            rentalService.quote(RENTAL_ID, new QuoteRentalRequest(new BigDecimal("2400.5")), BRANCH_ID);

            assertEquals(new BigDecimal("2400.50"), rental.getConfirmedPrice());
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = "REQUESTED", mode = EnumSource.Mode.EXCLUDE)
        void notRequested_throwsUnprocessable(RentalStatus status) {
            RentalEntity rental = rental(status);
            lock(rental);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_REQUESTED,
                    () -> rentalService.quote(RENTAL_ID, new QuoteRentalRequest(null), BRANCH_ID));

            assertEquals(status, rental.getStatus());
            assertNull(rental.getConfirmedPrice());
            verifyNoInteractions(outboxService);
        }

        @Test
        void rentalOfOtherBranch_throwsForbidden() {
            RentalEntity rental = rental(RentalStatus.REQUESTED);
            lock(rental);

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_IN_BRANCH,
                    () -> rentalService.quote(RENTAL_ID, new QuoteRentalRequest(null), OTHER_BRANCH_ID));

            assertEquals(RentalStatus.REQUESTED, rental.getStatus());
            verifyNoInteractions(outboxService);
        }

        @Test
        void unknownRental_throwsNotFound() {
            when(rentalRepository.findByIdForUpdate(RENTAL_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.RENTAL_NOT_FOUND,
                    () -> rentalService.quote(RENTAL_ID, new QuoteRentalRequest(null), BRANCH_ID));

            verifyNoInteractions(outboxService);
        }
    }

    @Nested
    class Reject {

        @Test
        void requestedRental_isRejectedWithTrimmedReasonAndRecordsEvent() {
            RentalEntity rental = rental(RentalStatus.REQUESTED);
            lock(rental);

            RentalResponse response = rentalService.reject(RENTAL_ID,
                    new RejectRentalRequest("  Sin buses disponibles "), BRANCH_ID);

            assertEquals(RentalStatus.REJECTED, rental.getStatus());
            assertEquals("Sin buses disponibles", rental.getRejectionReason());
            verify(outboxService).record(EventTypes.AGGREGATE_RENTAL, RENTAL_ID, EventTypes.RENTAL_REJECTED,
                    RentalEventPayload.fromEntity(rental));
            assertEquals("Sin buses disponibles", response.getRejectionReason());
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = "REQUESTED", mode = EnumSource.Mode.EXCLUDE)
        void notRequested_throwsUnprocessable(RentalStatus status) {
            RentalEntity rental = rental(status);
            lock(rental);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_REQUESTED,
                    () -> rentalService.reject(RENTAL_ID, new RejectRentalRequest("motivo"), BRANCH_ID));

            assertEquals(status, rental.getStatus());
            assertNull(rental.getRejectionReason());
            verifyNoInteractions(outboxService);
        }

        @Test
        void rentalOfOtherBranch_throwsForbidden() {
            lock(rental(RentalStatus.REQUESTED));

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_IN_BRANCH,
                    () -> rentalService.reject(RENTAL_ID, new RejectRentalRequest("motivo"), OTHER_BRANCH_ID));

            verifyNoInteractions(outboxService);
        }
    }

    @Nested
    class Pay {

        @Test
        void quotedRental_requestsPaymentAndRecordsEvent() {
            RentalEntity rental = quoted();
            rental.setPaymentFailureReason("INSUFFICIENT_FUNDS");
            lock(rental);
            LocalDate paymentDate = TODAY.minusDays(1);

            RentalResponse response = rentalService.pay(RENTAL_ID, new PayRentalRequest(paymentDate), USER_ID,
                    CUSTOMER_ID);

            assertEquals(RentalStatus.QUOTED, rental.getStatus());
            assertEquals(paymentDate, rental.getPaymentDate());
            assertNull(rental.getPaymentFailureReason());
            assertEquals(CUSTOMER_ID, rental.getCustomerId());
            assertNotNull(rental.getPaymentRequestedAt());
            verify(outboxService).record(eq(EventTypes.AGGREGATE_RENTAL), eq(RENTAL_ID),
                    eq(EventTypes.PAYMENT_REQUESTED), payloadCaptor.capture());
            PaymentRequestedPayload payload = (PaymentRequestedPayload) payloadCaptor.getValue();
            assertEquals(EventTypes.REFERENCE_RENTAL, payload.getReferenceType());
            assertEquals(RENTAL_ID, payload.getReferenceId());
            assertEquals(CUSTOMER_ID, payload.getCustomerId());
            assertEquals(NIT, payload.getCustomerNit());
            assertEquals(CONFIRMED_PRICE, payload.getAmount());
            assertEquals(paymentDate, payload.getPaymentDate());
            assertEquals(paymentDate, response.getPaymentDate());
        }

        @Test
        void withoutPaymentDate_usesToday() {
            RentalEntity rental = quoted();
            lock(rental);

            rentalService.pay(RENTAL_ID, new PayRentalRequest(null), USER_ID, CUSTOMER_ID);

            assertEquals(TODAY, rental.getPaymentDate());
        }

        @Test
        void futurePaymentDate_throwsBadRequestWithoutLoadingRental() {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.PAYMENT_DATE_IN_FUTURE,
                    () -> rentalService.pay(RENTAL_ID, new PayRentalRequest(TODAY.plusDays(1)), USER_ID, CUSTOMER_ID));

            verifyNoInteractions(rentalRepository, outboxService);
        }

        @Test
        void paymentAlreadyInProgress_throwsUnprocessable() {
            RentalEntity rental = quoted();
            rental.setPaymentDate(TODAY);
            lock(rental);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.PAYMENT_IN_PROGRESS,
                    () -> rentalService.pay(RENTAL_ID, new PayRentalRequest(null), USER_ID, CUSTOMER_ID));

            assertNull(rental.getCustomerId());
            assertNull(rental.getPaymentRequestedAt());
            verifyNoInteractions(outboxService);
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = "QUOTED", mode = EnumSource.Mode.EXCLUDE)
        void notQuoted_throwsUnprocessable(RentalStatus status) {
            RentalEntity rental = rental(status);
            lock(rental);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_QUOTED,
                    () -> rentalService.pay(RENTAL_ID, new PayRentalRequest(null), USER_ID, CUSTOMER_ID));

            assertNull(rental.getPaymentDate());
            verifyNoInteractions(outboxService);
        }

        @Test
        void rentalOfOtherCustomer_throwsForbidden() {
            lock(quoted());

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_OWNED,
                    () -> rentalService.pay(RENTAL_ID, new PayRentalRequest(null), OTHER_USER_ID, CUSTOMER_ID));

            verifyNoInteractions(outboxService);
        }

        @Test
        void counterRentalWithoutUser_throwsForbidden() {
            RentalEntity rental = quoted();
            rental.setCustomerUserId(null);
            lock(rental);

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_OWNED,
                    () -> rentalService.pay(RENTAL_ID, new PayRentalRequest(null), USER_ID, CUSTOMER_ID));
        }

        @Test
        void unknownRental_throwsNotFound() {
            when(rentalRepository.findByIdForUpdate(RENTAL_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.RENTAL_NOT_FOUND,
                    () -> rentalService.pay(RENTAL_ID, new PayRentalRequest(null), USER_ID, CUSTOMER_ID));
        }
    }

    @Nested
    class Assign {

        @Test
        void oneWay_assignsBusAndDriverAndRecordsOneLeg() {
            RentalEntity rental = rental(RentalStatus.PAID);
            rental.setAssignmentRejectionReason("rechazo anterior");
            lock(rental);
            AssignRentalRequest request = oneWaySchedule(at(DEPARTURE, 8), at(DEPARTURE, 12));

            RentalResponse response = rentalService.assign(RENTAL_ID, request, BRANCH_ID, ADMIN_ID);

            assertEquals(RentalStatus.ASSIGNED, rental.getStatus());
            assertEquals(BUS_ID, rental.getBusId());
            assertEquals(DRIVER_ID, rental.getDriverId());
            assertNull(rental.getAssignmentRejectionReason());
            RentalAssignedPayload payload = capturedAssignedPayload();
            assertEquals(RentalAssignedPayload.from(rental, request, ADMIN_ID), payload);
            assertEquals(RENTAL_ID, payload.getRentalId());
            assertEquals(BRANCH_ID, payload.getBranchId());
            assertEquals(BUS_ID, payload.getBusId());
            assertEquals(DRIVER_ID, payload.getDriverId());
            assertEquals(ADMIN_ID, payload.getAssignedBy());
            assertEquals(1, payload.getLegs().size());
            assertLeg(payload.getLegs().getFirst(), ORIGIN, DESTINATION, at(DEPARTURE, 8), at(DEPARTURE, 12));
            assertEquals(RentalStatus.ASSIGNED, response.getStatus());
        }

        @Test
        void roundTrip_recordsReturnLegWithSwappedPlaces() {
            lock(roundTrip(RentalStatus.PAID));

            rentalService.assign(RENTAL_ID, roundTripSchedule(at(DEPARTURE, 8), at(DEPARTURE, 12), at(RETURN, 15),
                    at(RETURN, 19)), BRANCH_ID, ADMIN_ID);

            RentalAssignedPayload payload = capturedAssignedPayload();
            assertEquals(2, payload.getLegs().size());
            assertLeg(payload.getLegs().get(0), ORIGIN, DESTINATION, at(DEPARTURE, 8), at(DEPARTURE, 12));
            assertLeg(payload.getLegs().get(1), DESTINATION, ORIGIN, at(RETURN, 15), at(RETURN, 19));
        }

        @Test
        void returnLeavingRightAtOutboundArrival_isAccepted() {
            RentalEntity rental = rental(RentalStatus.PAID);
            rental.setReturnDate(DEPARTURE);
            lock(rental);

            rentalService.assign(RENTAL_ID, roundTripSchedule(at(DEPARTURE, 8), at(DEPARTURE, 12),
                    at(DEPARTURE, 12), at(DEPARTURE, 16)), BRANCH_ID, ADMIN_ID);

            assertEquals(RentalStatus.ASSIGNED, rental.getStatus());
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = "PAID", mode = EnumSource.Mode.EXCLUDE)
        void notPaid_throwsUnprocessable(RentalStatus status) {
            RentalEntity rental = rental(status);
            lock(rental);

            assertAssignRejected(rental, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_PAID,
                    oneWaySchedule(at(DEPARTURE, 8), at(DEPARTURE, 12)));

            assertEquals(status, rental.getStatus());
        }

        @Test
        void rentalOfOtherBranch_throwsForbidden() {
            lock(rental(RentalStatus.PAID));

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_IN_BRANCH,
                    () -> rentalService.assign(RENTAL_ID, oneWaySchedule(at(DEPARTURE, 8), at(DEPARTURE, 12)),
                            OTHER_BRANCH_ID, ADMIN_ID));

            verifyNoInteractions(outboxService);
        }

        @Test
        void departureInPast_throwsBadRequest() {
            RentalEntity rental = rental(RentalStatus.PAID);
            rental.setDepartureDate(TODAY);
            lock(rental);
            Instant departure = Instant.now().minus(Duration.ofHours(1));

            assertAssignRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    oneWaySchedule(departure, departure.plus(Duration.ofHours(3))));
        }

        @Test
        void departureOnOtherDate_throwsScheduleDateMismatch() {
            RentalEntity rental = rental(RentalStatus.PAID);
            lock(rental);

            assertAssignRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.SCHEDULE_DATE_MISMATCH,
                    oneWaySchedule(at(DEPARTURE.plusDays(1), 8), at(DEPARTURE.plusDays(1), 12)));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1})
        void arrivalNotAfterDeparture_throwsBadRequest(int hoursBefore) {
            RentalEntity rental = rental(RentalStatus.PAID);
            lock(rental);
            Instant departure = at(DEPARTURE, 10);

            assertAssignRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.ARRIVAL_BEFORE_DEPARTURE,
                    oneWaySchedule(departure, departure.minus(Duration.ofHours(hoursBefore))));
        }

        @ParameterizedTest
        @CsvSource({"true,false", "false,true", "true,true"})
        void oneWayWithReturnSchedule_throwsReturnScheduleNotAllowed(boolean withDeparture, boolean withArrival) {
            RentalEntity rental = rental(RentalStatus.PAID);
            lock(rental);

            assertAssignRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_SCHEDULE_NOT_ALLOWED,
                    roundTripSchedule(at(DEPARTURE, 8), at(DEPARTURE, 12),
                            withDeparture ? at(RETURN, 15) : null, withArrival ? at(RETURN, 19) : null));
        }

        @ParameterizedTest
        @CsvSource({"false,false", "true,false", "false,true"})
        void roundTripWithoutReturnSchedule_throwsReturnScheduleRequired(boolean withDeparture, boolean withArrival) {
            RentalEntity rental = roundTrip(RentalStatus.PAID);
            lock(rental);

            assertAssignRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_SCHEDULE_REQUIRED,
                    roundTripSchedule(at(DEPARTURE, 8), at(DEPARTURE, 12),
                            withDeparture ? at(RETURN, 15) : null, withArrival ? at(RETURN, 19) : null));
        }

        @Test
        void returnBeforeOutboundArrival_throwsBadRequest() {
            RentalEntity rental = rental(RentalStatus.PAID);
            rental.setReturnDate(DEPARTURE);
            lock(rental);

            assertAssignRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_BEFORE_ARRIVAL,
                    roundTripSchedule(at(DEPARTURE, 8), at(DEPARTURE, 12), at(DEPARTURE, 11), at(DEPARTURE, 16)));
        }

        @Test
        void returnOnOtherDate_throwsScheduleDateMismatch() {
            RentalEntity rental = roundTrip(RentalStatus.PAID);
            lock(rental);

            assertAssignRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.SCHEDULE_DATE_MISMATCH,
                    roundTripSchedule(at(DEPARTURE, 8), at(DEPARTURE, 12), at(RETURN.plusDays(1), 15),
                            at(RETURN.plusDays(1), 19)));
        }

        @Test
        void returnArrivalBeforeReturnDeparture_throwsBadRequest() {
            RentalEntity rental = roundTrip(RentalStatus.PAID);
            lock(rental);

            assertAssignRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.ARRIVAL_BEFORE_DEPARTURE,
                    roundTripSchedule(at(DEPARTURE, 8), at(DEPARTURE, 12), at(RETURN, 15), at(RETURN, 14)));
        }

        private void assertAssignRejected(RentalEntity rental, HttpStatus status, String code,
                                          AssignRentalRequest request) {
            assertApiError(status, code, () -> rentalService.assign(RENTAL_ID, request, BRANCH_ID, ADMIN_ID));

            assertNull(rental.getBusId());
            assertNull(rental.getDriverId());
            verifyNoInteractions(outboxService);
        }

        private RentalAssignedPayload capturedAssignedPayload() {
            verify(outboxService).record(eq(EventTypes.AGGREGATE_RENTAL), eq(RENTAL_ID),
                    eq(EventTypes.RENTAL_ASSIGNED), payloadCaptor.capture());
            return (RentalAssignedPayload) payloadCaptor.getValue();
        }
    }

    @Nested
    class CancelAsCustomer {

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = {"REQUESTED", "QUOTED"})
        void unpaidRental_isCancelledWithoutEvent(RentalStatus status) {
            RentalEntity rental = rental(status);
            lock(rental);

            RentalResponse response = rentalService.cancelAsCustomer(RENTAL_ID, USER_ID);

            assertEquals(RentalStatus.CANCELLED, rental.getStatus());
            assertEquals(RentalStatus.CANCELLED, response.getStatus());
            verifyNoInteractions(outboxService);
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = {"REQUESTED", "QUOTED"}, mode = EnumSource.Mode.EXCLUDE)
        void paidOrClosedRental_throwsNotCancellable(RentalStatus status) {
            RentalEntity rental = rental(status);
            lock(rental);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_CANCELLABLE,
                    () -> rentalService.cancelAsCustomer(RENTAL_ID, USER_ID));

            assertEquals(status, rental.getStatus());
        }

        @Test
        void paymentInProgress_throwsUnprocessable() {
            RentalEntity rental = quoted();
            rental.setPaymentDate(TODAY);
            lock(rental);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.PAYMENT_IN_PROGRESS,
                    () -> rentalService.cancelAsCustomer(RENTAL_ID, USER_ID));

            assertEquals(RentalStatus.QUOTED, rental.getStatus());
        }

        @Test
        void rentalOfOtherCustomer_throwsForbidden() {
            RentalEntity rental = rental(RentalStatus.REQUESTED);
            lock(rental);

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_OWNED,
                    () -> rentalService.cancelAsCustomer(RENTAL_ID, OTHER_USER_ID));

            assertEquals(RentalStatus.REQUESTED, rental.getStatus());
        }
    }

    @Nested
    class CancelAsAdmin {

        @Test
        void requestedRentalOfOwnBranch_isCancelled() {
            RentalEntity rental = rental(RentalStatus.REQUESTED);
            lock(rental);

            rentalService.cancelAsAdmin(RENTAL_ID, BRANCH_ID);

            assertEquals(RentalStatus.CANCELLED, rental.getStatus());
        }

        @Test
        void rentalOfOtherBranch_throwsForbidden() {
            RentalEntity rental = rental(RentalStatus.REQUESTED);
            lock(rental);

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_IN_BRANCH,
                    () -> rentalService.cancelAsAdmin(RENTAL_ID, OTHER_BRANCH_ID));

            assertEquals(RentalStatus.REQUESTED, rental.getStatus());
        }

        @Test
        void paidRental_throwsNotCancellable() {
            RentalEntity rental = rental(RentalStatus.PAID);
            lock(rental);

            assertApiError(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_CANCELLABLE,
                    () -> rentalService.cancelAsAdmin(RENTAL_ID, BRANCH_ID));

            assertEquals(RentalStatus.PAID, rental.getStatus());
        }
    }

    @Nested
    class Reschedule {

        @Test
        void roundTrip_updatesBothDates() {
            RentalEntity rental = roundTrip(RentalStatus.PAID);
            lock(rental);
            LocalDate newDeparture = DEPARTURE.plusDays(10);

            RentalResponse response = rentalService.reschedule(RENTAL_ID,
                    new RescheduleRentalRequest(newDeparture, newDeparture.plusDays(2)), BRANCH_ID);

            assertEquals(newDeparture, rental.getDepartureDate());
            assertEquals(newDeparture.plusDays(2), rental.getReturnDate());
            assertEquals(RentalStatus.PAID, rental.getStatus());
            assertEquals(newDeparture, response.getDepartureDate());
            verifyNoInteractions(outboxService);
        }

        @Test
        void oneWayToToday_updatesDeparture() {
            RentalEntity rental = rental(RentalStatus.PAID);
            lock(rental);

            rentalService.reschedule(RENTAL_ID, new RescheduleRentalRequest(TODAY, null), BRANCH_ID);

            assertEquals(TODAY, rental.getDepartureDate());
            assertNull(rental.getReturnDate());
        }

        @ParameterizedTest
        @EnumSource(value = RentalStatus.class, names = "PAID", mode = EnumSource.Mode.EXCLUDE)
        void notPaid_throwsUnprocessable(RentalStatus status) {
            RentalEntity rental = rental(status);
            lock(rental);

            assertRescheduleRejected(rental, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.RENTAL_NOT_PAID,
                    new RescheduleRentalRequest(DEPARTURE.plusDays(10), null));
        }

        @Test
        void roundTripWithoutReturnDate_throwsReturnDateRequired() {
            RentalEntity rental = roundTrip(RentalStatus.PAID);
            lock(rental);

            assertRescheduleRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_DATE_REQUIRED,
                    new RescheduleRentalRequest(DEPARTURE.plusDays(10), null));
        }

        @Test
        void oneWayWithReturnDate_throwsReturnDateNotAllowed() {
            RentalEntity rental = rental(RentalStatus.PAID);
            lock(rental);

            assertRescheduleRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_DATE_NOT_ALLOWED,
                    new RescheduleRentalRequest(DEPARTURE.plusDays(10), DEPARTURE.plusDays(11)));
        }

        @Test
        void departureInPast_throwsBadRequest() {
            RentalEntity rental = rental(RentalStatus.PAID);
            lock(rental);

            assertRescheduleRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.DEPARTURE_IN_PAST,
                    new RescheduleRentalRequest(TODAY.minusDays(1), null));
        }

        @Test
        void returnBeforeDeparture_throwsBadRequest() {
            RentalEntity rental = roundTrip(RentalStatus.PAID);
            lock(rental);

            assertRescheduleRejected(rental, HttpStatus.BAD_REQUEST, ErrorCodes.RETURN_BEFORE_DEPARTURE,
                    new RescheduleRentalRequest(DEPARTURE.plusDays(10), DEPARTURE.plusDays(9)));
        }

        @Test
        void rentalOfOtherBranch_throwsForbidden() {
            lock(rental(RentalStatus.PAID));

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_IN_BRANCH,
                    () -> rentalService.reschedule(RENTAL_ID, new RescheduleRentalRequest(DEPARTURE, null),
                            OTHER_BRANCH_ID));
        }

        private void assertRescheduleRejected(RentalEntity rental, HttpStatus status, String code,
                                              RescheduleRentalRequest request) {
            LocalDate departure = rental.getDepartureDate();
            LocalDate returnDate = rental.getReturnDate();

            assertApiError(status, code, () -> rentalService.reschedule(RENTAL_ID, request, BRANCH_ID));

            assertEquals(departure, rental.getDepartureDate());
            assertEquals(returnDate, rental.getReturnDate());
        }
    }

    @Nested
    class FindForCustomer {

        @Test
        void ownOnlineRental_isReturned() {
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.of(rental(RentalStatus.REQUESTED)));

            RentalResponse response = rentalService.findForCustomer(RENTAL_ID, USER_ID, OTHER_NIT);

            assertEquals(RENTAL_ID, response.getId());
        }

        @Test
        void counterRentalWithOwnNit_isReturned() {
            RentalEntity rental = rental(RentalStatus.PAID);
            rental.setCustomerUserId(null);
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.of(rental));

            RentalResponse response = rentalService.findForCustomer(RENTAL_ID, USER_ID, NIT);

            assertEquals(RENTAL_ID, response.getId());
        }

        @Test
        void rentalOfOtherCustomer_throwsForbidden() {
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.of(rental(RentalStatus.REQUESTED)));

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_OWNED,
                    () -> rentalService.findForCustomer(RENTAL_ID, OTHER_USER_ID, OTHER_NIT));
        }

        @Test
        void unknownRental_throwsNotFound() {
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.RENTAL_NOT_FOUND,
                    () -> rentalService.findForCustomer(RENTAL_ID, USER_ID, NIT));
        }
    }

    @Nested
    class FindForStaff {

        @Test
        void rentalOfOwnBranch_isReturned() {
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.of(rental(RentalStatus.REQUESTED)));

            assertEquals(RENTAL_ID, rentalService.findForStaff(RENTAL_ID, BRANCH_ID).getId());
        }

        @Test
        void withoutBranchScope_returnsRentalOfAnyBranch() {
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.of(rental(RentalStatus.REQUESTED)));

            assertEquals(BRANCH_ID, rentalService.findForStaff(RENTAL_ID, null).getBranchId());
        }

        @Test
        void rentalOfOtherBranch_throwsForbidden() {
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.of(rental(RentalStatus.REQUESTED)));

            assertApiError(HttpStatus.FORBIDDEN, ErrorCodes.RENTAL_NOT_IN_BRANCH,
                    () -> rentalService.findForStaff(RENTAL_ID, OTHER_BRANCH_ID));
        }

        @Test
        void unknownRental_throwsNotFound() {
            when(rentalRepository.findById(RENTAL_ID)).thenReturn(Optional.empty());

            assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.RENTAL_NOT_FOUND,
                    () -> rentalService.findForStaff(RENTAL_ID, null));
        }
    }

    @Nested
    class Search {

        @Test
        void forCustomer_clampsPagingAndSortsByNewest() {
            when(rentalRepository.findAll(ArgumentMatchers.<Specification<RentalEntity>>any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(rental(RentalStatus.REQUESTED)), PageRequest.of(0, 100), 1));

            PageResponse<RentalResponse> result = rentalService.searchForCustomer(USER_ID, NIT, RentalStatus.REQUESTED,
                    TODAY, TODAY, -1, 500);

            verify(rentalRepository).findAll(ArgumentMatchers.<Specification<RentalEntity>>any(),
                    pageableCaptor.capture());
            Pageable pageable = pageableCaptor.getValue();
            assertEquals(0, pageable.getPageNumber());
            assertEquals(100, pageable.getPageSize());
            assertEquals(Sort.by(Sort.Direction.DESC, "createdAt"), pageable.getSort());
            assertEquals(1, result.getContent().size());
            assertEquals(RENTAL_ID, result.getContent().getFirst().getId());
            assertEquals(1, result.getTotalElements());
        }

        @Test
        void forBranch_withZeroSize_usesSizeOne() {
            when(rentalRepository.findAll(ArgumentMatchers.<Specification<RentalEntity>>any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 1), 0));

            PageResponse<RentalResponse> result = rentalService.searchForBranch(BRANCH_ID, null, null, null, 2, 0);

            verify(rentalRepository).findAll(ArgumentMatchers.<Specification<RentalEntity>>any(),
                    pageableCaptor.capture());
            assertEquals(2, pageableCaptor.getValue().getPageNumber());
            assertEquals(1, pageableCaptor.getValue().getPageSize());
            assertEquals(0, result.getTotalElements());
        }

        @Test
        void forCustomer_withToBeforeFrom_throwsInvalidDateRange() {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_DATE_RANGE,
                    () -> rentalService.searchForCustomer(USER_ID, NIT, null, TODAY, TODAY.minusDays(1), 0, 20));

            verifyNoInteractions(rentalRepository);
        }

        @Test
        void forBranch_withToBeforeFrom_throwsInvalidDateRange() {
            assertApiError(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_DATE_RANGE,
                    () -> rentalService.searchForBranch(BRANCH_ID, null, TODAY, TODAY.minusDays(1), 0, 20));

            verifyNoInteractions(rentalRepository);
        }
    }

    private static RentalEntity rental(RentalStatus status) {
        return RentalEntity.builder()
                .id(RENTAL_ID)
                .channel(RentalChannel.ONLINE)
                .customerNit(NIT)
                .customerUserId(USER_ID)
                .branchId(BRANCH_ID)
                .originText(ORIGIN)
                .destinationText(DESTINATION)
                .departureDate(DEPARTURE)
                .passengers(PASSENGERS)
                .distanceKm(DISTANCE)
                .estimatedPrice(ONE_WAY_PRICE)
                .status(status)
                .build();
    }

    private static RentalEntity roundTrip(RentalStatus status) {
        RentalEntity rental = rental(status);
        rental.setReturnDate(RETURN);
        rental.setEstimatedPrice(ROUND_TRIP_PRICE);
        return rental;
    }

    private static RentalEntity quoted() {
        RentalEntity rental = rental(RentalStatus.QUOTED);
        rental.setConfirmedPrice(CONFIRMED_PRICE);
        return rental;
    }

    private static RentalSettingsEntity settings() {
        return RentalSettingsEntity.builder()
                .branchId(BRANCH_ID)
                .pricePerKm(PRICE_PER_KM)
                .passengerSurcharge(SURCHARGE)
                .build();
    }

    private static CreateRentalRequest createRequest(LocalDate departure, LocalDate returnDate) {
        return new CreateRentalRequest(BRANCH_ID, ORIGIN, DESTINATION, departure, returnDate, PASSENGERS, DISTANCE);
    }

    private static CounterRentalRequest counterRequest(String nit, BigDecimal price, LocalDate paymentDate) {
        return new CounterRentalRequest(nit, " " + ORIGIN + " ", " " + DESTINATION + " ", DEPARTURE, null,
                PASSENGERS, DISTANCE, price, paymentDate);
    }

    private static AssignRentalRequest oneWaySchedule(Instant departure, Instant arrival) {
        return new AssignRentalRequest(BUS_ID, DRIVER_ID, departure, arrival, null, null);
    }

    private static AssignRentalRequest roundTripSchedule(Instant departure, Instant arrival, Instant returnDeparture,
                                                         Instant returnArrival) {
        return new AssignRentalRequest(BUS_ID, DRIVER_ID, departure, arrival, returnDeparture, returnArrival);
    }

    private static Instant at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(ZONE).toInstant();
    }

    private static void assertLeg(RentalAssignedPayload.Leg leg, String origin, String destination, Instant departure,
                                  Instant arrival) {
        assertEquals(origin, leg.getOriginText());
        assertEquals(destination, leg.getDestinationText());
        assertEquals(departure, leg.getDepartureAt());
        assertEquals(arrival, leg.getEstimatedArrival());
    }

    private static Answer<RentalEntity> savedWithId() {
        return invocation -> {
            RentalEntity rental = invocation.getArgument(0);
            rental.setId(RENTAL_ID);
            return rental;
        };
    }

    private void lock(RentalEntity rental) {
        when(rentalRepository.findByIdForUpdate(RENTAL_ID)).thenReturn(Optional.of(rental));
    }

    private void runTransactionsInline() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    private static void assertApiError(HttpStatus status, String code, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(code, exception.getCode());
    }
}

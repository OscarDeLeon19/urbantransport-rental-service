package com.urbantransport.rental.web;

import com.urbantransport.rental.AbstractIntegrationTest;
import com.urbantransport.rental.client.CustomerSummaryResponse;
import com.urbantransport.rental.domain.RentalChannel;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import com.urbantransport.rental.exception.ApiException;
import com.urbantransport.rental.exception.ErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RentalApiIT extends AbstractIntegrationTest {

    private static final UUID OTHER_USER_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID BUS_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID DRIVER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Autowired
    private JsonMapper jsonMapper;

    private UUID branchId;
    private UUID otherBranchId;
    private LocalDate departure;

    @BeforeEach
    void seed() {
        branchId = UUID.randomUUID();
        otherBranchId = UUID.randomUUID();
        departure = today().plusDays(5);
        saveSettings(branchId);
    }

    @Test
    void estimate_roundTrip_returnsPriceWithoutSavingAnything() throws Exception {
        mockMvc.perform(post("/rentals/estimate").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(branchId, departure, departure.plusDays(1), 30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs").value(2))
                .andExpect(jsonPath("$.estimatedPrice").value(2550.0));

        assertEquals(0, rentalRepository.count());
    }

    @Test
    void estimate_forBranchWithoutSettings_returnsUnprocessable() throws Exception {
        mockMvc.perform(post("/rentals/estimate").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(otherBranchId, departure, null, 30)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_SETTINGS_NOT_FOUND));
    }

    @Test
    void request_persistsRequestedRentalAndRecordsEvent() throws Exception {
        MvcResult result = mockMvc.perform(post("/rentals").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(branchId, departure, null, 30)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.estimatedPrice").value(1350.0))
                .andReturn();

        UUID rentalId = idAt(result, "$.id");
        RentalEntity saved = rentalRepository.findById(rentalId).orElseThrow();
        assertEquals(RentalChannel.ONLINE, saved.getChannel());
        assertEquals(USER_ID, saved.getCustomerUserId());
        assertEquals(NIT, saved.getCustomerNit());
        assertEquals("Parque Central", saved.getOriginText());
        assertEquals(0, new BigDecimal("1350.00").compareTo(saved.getEstimatedPrice()));
        assertEquals(List.of("RentalRequested"), outboxEventTypes(rentalId));
    }

    @Test
    void request_withInvalidBody_returnsValidationErrorAndSavesNothing() throws Exception {
        mockMvc.perform(post("/rentals").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\":\"%s\",\"passengers\":0}".formatted(branchId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR))
                .andExpect(jsonPath("$.message", containsString("passengers")))
                .andExpect(jsonPath("$.message", containsString("originText")))
                .andExpect(jsonPath("$.path").value("/rentals"));

        assertEquals(0, rentalRepository.count());
    }

    @Test
    void request_withDepartureInPast_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/rentals").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(branchId, today().minusDays(1), null, 30)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.DEPARTURE_IN_PAST));

        assertEquals(0, rentalRepository.count());
    }

    @Test
    void request_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/rentals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(branchId, departure, null, 30)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void request_asBranchAdmin_returnsAccessDenied() throws Exception {
        mockMvc.perform(post("/rentals").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(branchId, departure, null, 30)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));

        assertEquals(0, rentalRepository.count());
    }

    @Test
    void counter_withRegisteredNit_persistsPaidRentalAndRecordsEvent() throws Exception {
        when(customerClient.getByNit(eq(NIT), anyString()))
                .thenReturn(new CustomerSummaryResponse(CUSTOMER_ID, NIT, "Juan Perez", false, true));

        MvcResult result = mockMvc.perform(post("/rentals/counter").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(counterJson("1234567-8")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.channel").value("COUNTER"))
                .andReturn();

        UUID rentalId = idAt(result, "$.id");
        RentalEntity saved = rentalRepository.findById(rentalId).orElseThrow();
        assertEquals(NIT, saved.getCustomerNit());
        assertNull(saved.getCustomerUserId());
        assertEquals(branchId, saved.getBranchId());
        assertEquals(0, new BigDecimal("1900.00").compareTo(saved.getConfirmedPrice()));
        assertEquals(today(), saved.getPaymentDate());
        assertEquals(List.of("RentalPaid"), outboxEventTypes(rentalId));
    }

    @Test
    void counter_withUnknownCustomer_returnsNotFoundAndSavesNothing() throws Exception {
        when(customerClient.getByNit(eq(NIT), anyString())).thenThrow(new ApiException(HttpStatus.NOT_FOUND,
                ErrorCodes.CUSTOMER_NOT_FOUND, "El cliente no esta registrado"));

        mockMvc.perform(post("/rentals/counter").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(counterJson(NIT)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.CUSTOMER_NOT_FOUND));

        assertEquals(0, rentalRepository.count());
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_events"));
    }

    @Test
    void counter_whenCustomerIsUnavailable_returnsServiceUnavailableAndSavesNothing() throws Exception {
        when(customerClient.getByNit(eq(NIT), anyString())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.CUSTOMER_UNAVAILABLE, "El servicio de clientes no esta disponible"));

        mockMvc.perform(post("/rentals/counter").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(counterJson(NIT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCodes.CUSTOMER_UNAVAILABLE));

        assertEquals(0, rentalRepository.count());
    }

    @Test
    void counter_withGenericNit_doesNotCallCustomer() throws Exception {
        mockMvc.perform(post("/rentals/counter").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(counterJson("cf")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerNit").value("CF"));

        verifyNoInteractions(customerClient);
    }

    @Test
    void counter_withoutBranchClaim_returnsBranchRequired() throws Exception {
        mockMvc.perform(post("/rentals/counter").with(userWithRole("BRANCH_ADMIN", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(counterJson(NIT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_REQUIRED));

        verifyNoInteractions(customerClient);
    }

    @Test
    void quote_withoutPrice_confirmsEstimateAndRecordsEvent() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.REQUESTED, departure, null).getId();

        mockMvc.perform(put("/rentals/{id}/quote", rentalId).with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.confirmedPrice").value(1350.0));

        RentalEntity saved = rentalRepository.findById(rentalId).orElseThrow();
        assertEquals(RentalStatus.QUOTED, saved.getStatus());
        assertEquals(0, new BigDecimal("1350.00").compareTo(saved.getConfirmedPrice()));
        assertEquals(List.of("RentalQuoted"), outboxEventTypes(rentalId));
    }

    @Test
    void quote_rentalOfOtherBranch_returnsForbiddenAndKeepsData() throws Exception {
        UUID rentalId = saveRental(otherBranchId, RentalStatus.REQUESTED, departure, null).getId();

        mockMvc.perform(put("/rentals/{id}/quote", rentalId).with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":2000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_NOT_IN_BRANCH));

        assertEquals(RentalStatus.REQUESTED, rentalRepository.findById(rentalId).orElseThrow().getStatus());
        assertTrue(outboxEventTypes(rentalId).isEmpty());
    }

    @Test
    void quote_alreadyQuoted_returnsUnprocessable() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.QUOTED, departure, null).getId();

        mockMvc.perform(put("/rentals/{id}/quote", rentalId).with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":2000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_NOT_REQUESTED));

        assertEquals(0, CONFIRMED_PRICE.compareTo(rentalRepository.findById(rentalId).orElseThrow().getConfirmedPrice()));
    }

    @Test
    void reject_storesReasonAndRecordsEvent() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.REQUESTED, departure, null).getId();

        mockMvc.perform(put("/rentals/{id}/reject", rentalId).with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" Sin buses \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        RentalEntity saved = rentalRepository.findById(rentalId).orElseThrow();
        assertEquals("Sin buses", saved.getRejectionReason());
        assertEquals(List.of("RentalRejected"), outboxEventTypes(rentalId));
    }

    @Test
    void pay_quotedRental_recordsPaymentRequestedWithCustomerData() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.QUOTED, departure, null).getId();

        mockMvc.perform(post("/rentals/{id}/pay", rentalId).with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentDate\":\"%s\"}".formatted(today())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUOTED"));

        RentalEntity saved = rentalRepository.findById(rentalId).orElseThrow();
        assertEquals(today(), saved.getPaymentDate());
        assertEquals(CUSTOMER_ID, saved.getCustomerId());
        assertNotNull(saved.getPaymentRequestedAt());
        JsonNode payload = jsonMapper.readTree(outboxPayload(rentalId, "PaymentRequested")).get("payload");
        assertEquals("RENTAL", payload.get("referenceType").asString());
        assertEquals(rentalId.toString(), payload.get("referenceId").asString());
        assertEquals(CUSTOMER_ID.toString(), payload.get("customerId").asString());
        assertEquals(NIT, payload.get("customerNit").asString());
        assertEquals(0, CONFIRMED_PRICE.compareTo(payload.get("amount").decimalValue()));
        assertEquals(today().toString(), payload.get("paymentDate").asString());
    }

    @Test
    void pay_twice_returnsPaymentInProgressAndRecordsOneEvent() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.QUOTED, departure, null).getId();

        mockMvc.perform(post("/rentals/{id}/pay", rentalId).with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/rentals/{id}/pay", rentalId).with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(ErrorCodes.PAYMENT_IN_PROGRESS));

        assertEquals(List.of("PaymentRequested"), outboxEventTypes(rentalId));
    }

    @Test
    void pay_rentalOfOtherCustomer_returnsForbidden() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.QUOTED, departure, null).getId();

        mockMvc.perform(post("/rentals/{id}/pay", rentalId).with(customer(OTHER_USER_ID, "87654321", CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_NOT_OWNED));

        assertNull(rentalRepository.findById(rentalId).orElseThrow().getPaymentDate());
        assertTrue(outboxEventTypes(rentalId).isEmpty());
    }

    @Test
    void assign_roundTrip_recordsAssignedEventWithBothLegs() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.PAID, departure, departure.plusDays(1)).getId();
        Instant outbound = at(departure, 8);
        Instant back = at(departure.plusDays(1), 15);

        mockMvc.perform(put("/rentals/{id}/assign", rentalId).with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignJson(outbound, outbound.plusSeconds(4 * 3600), back,
                                back.plusSeconds(4 * 3600))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));

        RentalEntity saved = rentalRepository.findById(rentalId).orElseThrow();
        assertEquals(BUS_ID, saved.getBusId());
        assertEquals(DRIVER_ID, saved.getDriverId());
        JsonNode payload = jsonMapper.readTree(outboxPayload(rentalId, "RentalAssigned")).get("payload");
        assertEquals(branchId.toString(), payload.get("branchId").asString());
        assertEquals(USER_ID.toString(), payload.get("assignedBy").asString());
        assertEquals(2, payload.get("legs").size());
        assertEquals("Panajachel", payload.get("legs").get(1).get("originText").asString());
        assertEquals(back, Instant.parse(payload.get("legs").get(1).get("departureAt").asString()));
    }

    @Test
    void assign_onOtherDate_returnsScheduleDateMismatchAndKeepsPaid() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.PAID, departure, null).getId();
        Instant wrongDay = at(departure.plusDays(1), 8);

        mockMvc.perform(put("/rentals/{id}/assign", rentalId).with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignJson(wrongDay, wrongDay.plusSeconds(3600), null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.SCHEDULE_DATE_MISMATCH));

        RentalEntity saved = rentalRepository.findById(rentalId).orElseThrow();
        assertEquals(RentalStatus.PAID, saved.getStatus());
        assertNull(saved.getBusId());
        assertTrue(outboxEventTypes(rentalId).isEmpty());
    }

    @Test
    void cancel_asCustomer_cancelsRequestedRental() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.REQUESTED, departure, null).getId();

        mockMvc.perform(put("/rentals/{id}/cancel", rentalId).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertEquals(RentalStatus.CANCELLED, rentalRepository.findById(rentalId).orElseThrow().getStatus());
    }

    @Test
    void cancel_paidRental_returnsNotCancellable() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.PAID, departure, null).getId();

        mockMvc.perform(put("/rentals/{id}/cancel", rentalId).with(branchAdmin(branchId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_NOT_CANCELLABLE));

        assertEquals(RentalStatus.PAID, rentalRepository.findById(rentalId).orElseThrow().getStatus());
    }

    @Test
    void cancel_asAdminOfOtherBranch_returnsForbidden() throws Exception {
        UUID rentalId = saveRental(otherBranchId, RentalStatus.REQUESTED, departure, null).getId();

        mockMvc.perform(put("/rentals/{id}/cancel", rentalId).with(branchAdmin(branchId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_NOT_IN_BRANCH));

        assertEquals(RentalStatus.REQUESTED, rentalRepository.findById(rentalId).orElseThrow().getStatus());
    }

    @Test
    void reschedule_paidRental_updatesDates() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.PAID, departure, departure.plusDays(1)).getId();
        LocalDate newDeparture = departure.plusDays(10);

        mockMvc.perform(put("/rentals/{id}/reschedule", rentalId).with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departureDate\":\"%s\",\"returnDate\":\"%s\"}"
                                .formatted(newDeparture, newDeparture.plusDays(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departureDate").value(newDeparture.toString()));

        RentalEntity saved = rentalRepository.findById(rentalId).orElseThrow();
        assertEquals(newDeparture, saved.getDepartureDate());
        assertEquals(newDeparture.plusDays(1), saved.getReturnDate());
    }

    @Test
    void searchMine_returnsOnlineAndCounterRentalsOfCustomer() throws Exception {
        saveRental(branchId, RentalStatus.REQUESTED, departure, null);
        RentalEntity counter = saveRental(otherBranchId, RentalStatus.PAID, departure, null);
        counter.setChannel(RentalChannel.COUNTER);
        counter.setCustomerUserId(null);
        rentalRepository.save(counter);
        RentalEntity foreign = saveRental(branchId, RentalStatus.REQUESTED, departure, null);
        foreign.setCustomerUserId(OTHER_USER_ID);
        foreign.setCustomerNit("87654321");
        rentalRepository.save(foreign);

        mockMvc.perform(get("/rentals/me").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/rentals/me").with(customer(OTHER_USER_ID, "87654321", CUSTOMER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(foreign.getId().toString()));
    }

    @Test
    void searchMine_filtersByStatusAndDepartureRangeNewestFirst() throws Exception {
        saveRental(branchId, RentalStatus.REQUESTED, departure, null);
        saveRental(branchId, RentalStatus.QUOTED, departure.plusDays(10), null);
        UUID newest = saveRental(branchId, RentalStatus.QUOTED, departure.plusDays(20), null).getId();

        mockMvc.perform(get("/rentals/me").with(customer()).param("status", "QUOTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newest.toString()));

        mockMvc.perform(get("/rentals/me").with(customer())
                        .param("from", departure.toString())
                        .param("to", departure.plusDays(10).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/rentals/me").with(customer()).param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void searchMine_withToBeforeFrom_returnsInvalidDateRange() throws Exception {
        mockMvc.perform(get("/rentals/me").with(customer())
                        .param("from", departure.toString())
                        .param("to", departure.minusDays(1).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.INVALID_DATE_RANGE));
    }

    @Test
    void searchBranch_asBranchAdmin_returnsOnlyOwnBranch() throws Exception {
        saveRental(branchId, RentalStatus.REQUESTED, departure, null);
        saveRental(branchId, RentalStatus.PAID, departure, null);
        saveRental(otherBranchId, RentalStatus.REQUESTED, departure, null);

        mockMvc.perform(get("/rentals").with(branchAdmin(branchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/rentals").with(branchAdmin(branchId)).param("status", "PAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void searchBranch_asBranchAdminForOtherBranch_returnsBranchForbidden() throws Exception {
        mockMvc.perform(get("/rentals").with(branchAdmin(branchId)).param("branchId", otherBranchId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_FORBIDDEN));
    }

    @Test
    void searchBranch_asSystemAdmin_canFilterAnyBranch() throws Exception {
        saveRental(branchId, RentalStatus.REQUESTED, departure, null);
        UUID other = saveRental(otherBranchId, RentalStatus.REQUESTED, departure, null).getId();

        mockMvc.perform(get("/rentals").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/rentals").with(systemAdmin()).param("branchId", otherBranchId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(other.toString()));
    }

    @Test
    void findById_isVisibleOnlyToOwnerBranchAndSystemAdmin() throws Exception {
        UUID rentalId = saveRental(branchId, RentalStatus.REQUESTED, departure, null).getId();

        mockMvc.perform(get("/rentals/{id}", rentalId).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(rentalId.toString()));
        mockMvc.perform(get("/rentals/{id}", rentalId).with(customer(OTHER_USER_ID, "87654321", CUSTOMER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_NOT_OWNED));
        mockMvc.perform(get("/rentals/{id}", rentalId).with(branchAdmin(otherBranchId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_NOT_IN_BRANCH));
        mockMvc.perform(get("/rentals/{id}", rentalId).with(systemAdmin()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/rentals/{id}", UUID.randomUUID()).with(systemAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_NOT_FOUND));
    }

    private static Instant at(LocalDate date, int hour) {
        return date.atTime(hour, 0).atZone(ZONE).toInstant();
    }

    private static String createJson(UUID branchId, LocalDate departure, LocalDate returnDate, int passengers) {
        return """
                {"branchId":"%s","originText":" Parque Central ","destinationText":"Panajachel",
                 "departureDate":"%s","returnDate":%s,"passengers":%d,"distanceKm":120}
                """.formatted(branchId, departure, returnDate == null ? "null" : "\"" + returnDate + "\"", passengers);
    }

    private String counterJson(String nit) {
        return """
                {"customerNit":"%s","originText":"Parque Central","destinationText":"Antigua",
                 "departureDate":"%s","passengers":40,"distanceKm":180,"price":1900.00}
                """.formatted(nit, departure);
    }

    private static String assignJson(Instant departure, Instant arrival, Instant returnDeparture,
                                     Instant returnArrival) {
        return """
                {"busId":"%s","driverId":"%s","departureAt":"%s","estimatedArrival":"%s",
                 "returnDepartureAt":%s,"returnEstimatedArrival":%s}
                """.formatted(BUS_ID, DRIVER_ID, departure, arrival,
                returnDeparture == null ? "null" : "\"" + returnDeparture + "\"",
                returnArrival == null ? "null" : "\"" + returnArrival + "\"");
    }
}

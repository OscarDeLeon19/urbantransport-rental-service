package com.urbantransport.rental.web;

import com.urbantransport.rental.config.SecurityConfig;
import com.urbantransport.rental.domain.RentalChannel;
import com.urbantransport.rental.domain.RentalEntity;
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
import com.urbantransport.rental.exception.ApiException;
import com.urbantransport.rental.exception.ErrorCodes;
import com.urbantransport.rental.service.RentalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RentalController.class)
@Import(SecurityConfig.class)
class RentalControllerTest {

    private static final ZoneId ZONE = ZoneId.of("America/Guatemala");
    private static final LocalDate DEPARTURE = LocalDate.now(ZONE).plusDays(5);
    private static final LocalDate YESTERDAY = LocalDate.now(ZONE).minusDays(1);
    private static final UUID RENTAL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BRANCH_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_BRANCH_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CUSTOMER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID BUS_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID DRIVER_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final String TOKEN = "token-de-prueba";
    private static final String NIT = "1234567K";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RentalService rentalService;

    @Test
    void estimate_asCustomer_returnsBreakdown() throws Exception {
        CreateRentalRequest request = createRequest();
        when(rentalService.estimate(request)).thenReturn(new RentalEstimateResponse(BRANCH_ID,
                new BigDecimal("120.5"), 1, 30, new BigDecimal("10.00"), new BigDecimal("5.00"),
                new BigDecimal("1355.00")));

        mockMvc.perform(post("/rentals/estimate").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs").value(1))
                .andExpect(jsonPath("$.estimatedPrice").value(1355.0));
    }

    @Test
    void estimate_asBranchAdmin_returnsAccessDenied() throws Exception {
        mockMvc.perform(post("/rentals/estimate").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));

        verifyNoInteractions(rentalService);
    }

    @Test
    void estimate_withInvalidBody_returnsValidationErrorWithFields() throws Exception {
        mockMvc.perform(post("/rentals/estimate").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passengers\":0,\"distanceKm\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR))
                .andExpect(jsonPath("$.message", containsString("passengers")))
                .andExpect(jsonPath("$.message", containsString("distanceKm")))
                .andExpect(jsonPath("$.message", containsString("branchId")));

        verifyNoInteractions(rentalService);
    }

    @Test
    void estimate_withUnreadableBody_returnsValidationError() throws Exception {
        mockMvc.perform(post("/rentals/estimate").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR));

        verifyNoInteractions(rentalService);
    }

    @Test
    void request_asCustomer_returnsCreatedWithUserAndNormalizedNit() throws Exception {
        when(rentalService.requestOnline(createRequest(), USER_ID, NIT)).thenReturn(response(RentalStatus.REQUESTED));

        mockMvc.perform(post("/rentals").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(RENTAL_ID.toString()))
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void request_withoutNitClaim_returnsCustomerNitRequired() throws Exception {
        mockMvc.perform(post("/rentals").with(user("CUSTOMER", null, null, CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.CUSTOMER_NIT_REQUIRED));

        verifyNoInteractions(rentalService);
    }

    @Test
    void request_withBlankNitClaim_returnsCustomerNitRequired() throws Exception {
        mockMvc.perform(post("/rentals").with(user("CUSTOMER", null, "  ", CUSTOMER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.CUSTOMER_NIT_REQUIRED));
    }

    @Test
    void request_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/rentals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(rentalService);
    }

    @Test
    void counter_asBranchAdmin_forwardsBranchAndUserToken() throws Exception {
        CounterRentalRequest request = new CounterRentalRequest("1234567-8", "Parque Central", "Panajachel",
                DEPARTURE, null, 40, new BigDecimal("180"), new BigDecimal("1900.00"), YESTERDAY);
        when(rentalService.registerCounter(request, BRANCH_ID, TOKEN)).thenReturn(response(RentalStatus.PAID));

        mockMvc.perform(post("/rentals/counter").with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerNit":"1234567-8","originText":"Parque Central","destinationText":"Panajachel",
                                 "departureDate":"%s","passengers":40,"distanceKm":180,"price":1900.00,
                                 "paymentDate":"%s"}
                                """.formatted(DEPARTURE, YESTERDAY)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void counter_withoutBranchClaim_returnsBranchRequired() throws Exception {
        mockMvc.perform(post("/rentals/counter").with(branchAdmin(null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerNit":"CF","originText":"A","destinationText":"B","departureDate":"%s",
                                 "passengers":40,"distanceKm":180,"price":1900.00}
                                """.formatted(DEPARTURE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_REQUIRED));

        verifyNoInteractions(rentalService);
    }

    @Test
    void quote_withEmptyBody_acceptsEstimate() throws Exception {
        when(rentalService.quote(RENTAL_ID, new QuoteRentalRequest(null), BRANCH_ID))
                .thenReturn(response(RentalStatus.QUOTED));

        mockMvc.perform(put("/rentals/{id}/quote", RENTAL_ID).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"));
    }

    @Test
    void quote_withNonPositivePrice_returnsValidationError() throws Exception {
        mockMvc.perform(put("/rentals/{id}/quote", RENTAL_ID).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR));

        verifyNoInteractions(rentalService);
    }

    @Test
    void reject_asBranchAdmin_delegatesReason() throws Exception {
        when(rentalService.reject(RENTAL_ID, new RejectRentalRequest("Sin buses"), BRANCH_ID))
                .thenReturn(response(RentalStatus.REJECTED));

        mockMvc.perform(put("/rentals/{id}/reject", RENTAL_ID).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Sin buses\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void assign_asBranchAdmin_passesAdminAsAssigner() throws Exception {
        Instant departure = DEPARTURE.atTime(8, 0).atZone(ZONE).toInstant();
        Instant arrival = DEPARTURE.atTime(12, 0).atZone(ZONE).toInstant();
        AssignRentalRequest request = new AssignRentalRequest(BUS_ID, DRIVER_ID, departure, arrival, null, null);
        when(rentalService.assign(RENTAL_ID, request, BRANCH_ID, USER_ID)).thenReturn(response(RentalStatus.ASSIGNED));

        mockMvc.perform(put("/rentals/{id}/assign", RENTAL_ID).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"busId":"%s","driverId":"%s","departureAt":"%s","estimatedArrival":"%s"}
                                """.formatted(BUS_ID, DRIVER_ID, departure, arrival)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    @Test
    void pay_asCustomer_returnsAcceptedWithCustomerFromToken() throws Exception {
        when(rentalService.pay(RENTAL_ID, new PayRentalRequest(YESTERDAY), USER_ID, CUSTOMER_ID))
                .thenReturn(response(RentalStatus.QUOTED));

        mockMvc.perform(post("/rentals/{id}/pay", RENTAL_ID).with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentDate\":\"%s\"}".formatted(YESTERDAY)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUOTED"));
    }

    @Test
    void pay_withoutCustomerIdClaim_returnsCustomerIdRequired() throws Exception {
        mockMvc.perform(post("/rentals/{id}/pay", RENTAL_ID).with(user("CUSTOMER", null, NIT, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.CUSTOMER_ID_REQUIRED));

        verifyNoInteractions(rentalService);
    }

    @Test
    void cancel_asCustomer_cancelsOwnRental() throws Exception {
        when(rentalService.cancelAsCustomer(RENTAL_ID, USER_ID)).thenReturn(response(RentalStatus.CANCELLED));

        mockMvc.perform(put("/rentals/{id}/cancel", RENTAL_ID).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(rentalService, never()).cancelAsAdmin(any(), any());
    }

    @Test
    void cancel_asBranchAdmin_cancelsInOwnBranch() throws Exception {
        when(rentalService.cancelAsAdmin(RENTAL_ID, BRANCH_ID)).thenReturn(response(RentalStatus.CANCELLED));

        mockMvc.perform(put("/rentals/{id}/cancel", RENTAL_ID).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isOk());

        verify(rentalService, never()).cancelAsCustomer(any(), any());
    }

    @Test
    void cancel_asSystemAdmin_returnsAccessDenied() throws Exception {
        mockMvc.perform(put("/rentals/{id}/cancel", RENTAL_ID).with(systemAdmin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));

        verifyNoInteractions(rentalService);
    }

    @Test
    void cancel_withAuthorityButWithoutRolesClaim_isTreatedAsStaff() throws Exception {
        when(rentalService.cancelAsAdmin(RENTAL_ID, BRANCH_ID)).thenReturn(response(RentalStatus.CANCELLED));

        mockMvc.perform(put("/rentals/{id}/cancel", RENTAL_ID).with(jwt()
                        .jwt(token -> token.subject(USER_ID.toString()).claim("branchId", BRANCH_ID.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk());

        verify(rentalService, never()).cancelAsCustomer(any(), any());
    }

    @Test
    void searchMine_passesFiltersPagingAndNitFromToken() throws Exception {
        when(rentalService.searchForCustomer(USER_ID, NIT, RentalStatus.QUOTED, YESTERDAY, DEPARTURE, 1, 5))
                .thenReturn(page());

        mockMvc.perform(get("/rentals/me").with(customer())
                        .param("status", "QUOTED")
                        .param("from", YESTERDAY.toString())
                        .param("to", DEPARTURE.toString())
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(RENTAL_ID.toString()));
    }

    @Test
    void searchMine_withoutParams_usesDefaults() throws Exception {
        when(rentalService.searchForCustomer(USER_ID, NIT, null, null, null, 0, 20)).thenReturn(page());

        mockMvc.perform(get("/rentals/me").with(customer()))
                .andExpect(status().isOk());
    }

    @Test
    void searchMine_withUnknownStatus_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/rentals/me").with(customer()).param("status", "PENDIENTE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rentalService);
    }

    @Test
    void searchBranch_asBranchAdmin_isFixedToOwnBranch() throws Exception {
        when(rentalService.searchForBranch(BRANCH_ID, null, null, null, 0, 20)).thenReturn(page());

        mockMvc.perform(get("/rentals").with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/rentals").with(branchAdmin(BRANCH_ID)).param("branchId", BRANCH_ID.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void searchBranch_asBranchAdminForOtherBranch_returnsBranchForbidden() throws Exception {
        mockMvc.perform(get("/rentals").with(branchAdmin(BRANCH_ID)).param("branchId", OTHER_BRANCH_ID.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_FORBIDDEN));

        verifyNoInteractions(rentalService);
    }

    @Test
    void searchBranch_asSystemAdmin_filtersFreely() throws Exception {
        when(rentalService.searchForBranch(null, RentalStatus.PAID, null, null, 0, 20)).thenReturn(page());
        when(rentalService.searchForBranch(OTHER_BRANCH_ID, null, null, null, 0, 20)).thenReturn(page());

        mockMvc.perform(get("/rentals").with(systemAdmin()).param("status", "PAID"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/rentals").with(systemAdmin()).param("branchId", OTHER_BRANCH_ID.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void searchBranch_asCustomer_returnsAccessDenied() throws Exception {
        mockMvc.perform(get("/rentals").with(customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));

        verifyNoInteractions(rentalService);
    }

    @Test
    void findById_asCustomer_usesUserAndNit() throws Exception {
        when(rentalService.findForCustomer(RENTAL_ID, USER_ID, NIT)).thenReturn(response(RentalStatus.REQUESTED));

        mockMvc.perform(get("/rentals/{id}", RENTAL_ID).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(RENTAL_ID.toString()));
    }

    @Test
    void findById_asBranchAdmin_isScopedToOwnBranch() throws Exception {
        when(rentalService.findForStaff(RENTAL_ID, BRANCH_ID)).thenReturn(response(RentalStatus.REQUESTED));

        mockMvc.perform(get("/rentals/{id}", RENTAL_ID).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void findById_asSystemAdmin_isNotScoped() throws Exception {
        when(rentalService.findForStaff(RENTAL_ID, null)).thenReturn(response(RentalStatus.REQUESTED));

        mockMvc.perform(get("/rentals/{id}", RENTAL_ID).with(systemAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void findById_whenServiceRejects_returnsItsStatusAndCode() throws Exception {
        when(rentalService.findForStaff(RENTAL_ID, BRANCH_ID)).thenThrow(new ApiException(HttpStatus.NOT_FOUND,
                ErrorCodes.RENTAL_NOT_FOUND, "Alquiler no encontrado"));

        mockMvc.perform(get("/rentals/{id}", RENTAL_ID).with(branchAdmin(BRANCH_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_NOT_FOUND))
                .andExpect(jsonPath("$.path").value("/rentals/" + RENTAL_ID));
    }

    @Test
    void reschedule_asBranchAdmin_delegatesNewDates() throws Exception {
        when(rentalService.reschedule(RENTAL_ID, new RescheduleRentalRequest(DEPARTURE, null), BRANCH_ID))
                .thenReturn(response(RentalStatus.PAID));

        mockMvc.perform(put("/rentals/{id}/reschedule", RENTAL_ID).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departureDate\":\"%s\"}".formatted(DEPARTURE)))
                .andExpect(status().isOk());
    }

    @Test
    void reschedule_withoutDepartureDate_returnsValidationError() throws Exception {
        mockMvc.perform(put("/rentals/{id}/reschedule", RENTAL_ID).with(branchAdmin(BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("departureDate")));

        verifyNoInteractions(rentalService);
    }

    private static CreateRentalRequest createRequest() {
        return new CreateRentalRequest(BRANCH_ID, "Parque Central", "Panajachel", DEPARTURE, null, 30,
                new BigDecimal("120.5"));
    }

    private static String createJson() {
        return """
                {"branchId":"%s","originText":"Parque Central","destinationText":"Panajachel",
                 "departureDate":"%s","passengers":30,"distanceKm":120.5}
                """.formatted(BRANCH_ID, DEPARTURE);
    }

    private static RentalResponse response(RentalStatus status) {
        return RentalResponse.fromEntity(RentalEntity.builder()
                .id(RENTAL_ID)
                .channel(RentalChannel.ONLINE)
                .customerNit(NIT)
                .customerUserId(USER_ID)
                .branchId(BRANCH_ID)
                .originText("Parque Central")
                .destinationText("Panajachel")
                .departureDate(DEPARTURE)
                .passengers(30)
                .distanceKm(new BigDecimal("120.50"))
                .estimatedPrice(new BigDecimal("1355.00"))
                .status(status)
                .build());
    }

    private static PageResponse<RentalResponse> page() {
        return PageResponse.of(new PageImpl<>(List.of(response(RentalStatus.QUOTED))));
    }

    private static RequestPostProcessor customer() {
        return user("CUSTOMER", null, " 1234567k ", CUSTOMER_ID);
    }

    private static RequestPostProcessor branchAdmin(UUID branchId) {
        return user("BRANCH_ADMIN", branchId, null, null);
    }

    private static RequestPostProcessor systemAdmin() {
        return user("SYSTEM_ADMIN", null, null, null);
    }

    private static RequestPostProcessor user(String role, UUID branchId, String nit, UUID customerId) {
        return jwt()
                .jwt(token -> {
                    token.tokenValue(TOKEN).subject(USER_ID.toString()).claim("roles", List.of(role));
                    if (branchId != null) {
                        token.claim("branchId", branchId.toString());
                    }
                    if (nit != null) {
                        token.claim("nit", nit);
                    }
                    if (customerId != null) {
                        token.claim("customerId", customerId.toString());
                    }
                })
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}

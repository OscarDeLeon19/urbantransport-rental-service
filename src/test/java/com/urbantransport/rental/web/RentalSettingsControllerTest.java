package com.urbantransport.rental.web;

import com.urbantransport.rental.config.SecurityConfig;
import com.urbantransport.rental.dto.RentalSettingsResponse;
import com.urbantransport.rental.dto.UpdateRentalSettingsRequest;
import com.urbantransport.rental.exception.ErrorCodes;
import com.urbantransport.rental.service.RentalSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RentalSettingsController.class)
@Import(SecurityConfig.class)
class RentalSettingsControllerTest {

    private static final UUID BRANCH_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RentalSettingsService rentalSettingsService;

    @Test
    void find_asBranchAdmin_returnsOwnBranchRates() throws Exception {
        when(rentalSettingsService.findByBranch(BRANCH_ID)).thenReturn(settings());

        mockMvc.perform(get("/rental-settings").with(user("BRANCH_ADMIN", BRANCH_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value(BRANCH_ID.toString()))
                .andExpect(jsonPath("$.pricePerKm").value(10.0));
    }

    @Test
    void update_asBranchAdmin_savesRatesForOwnBranch() throws Exception {
        when(rentalSettingsService.update(BRANCH_ID,
                new UpdateRentalSettingsRequest(new BigDecimal("10.00"), new BigDecimal("5.00")))).thenReturn(settings());

        mockMvc.perform(put("/rental-settings").with(user("BRANCH_ADMIN", BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerKm\":10.00,\"passengerSurcharge\":5.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passengerSurcharge").value(5.0));
    }

    @Test
    void update_withInvalidRates_returnsValidationError() throws Exception {
        mockMvc.perform(put("/rental-settings").with(user("BRANCH_ADMIN", BRANCH_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerKm\":0,\"passengerSurcharge\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR))
                .andExpect(jsonPath("$.message", containsString("pricePerKm")))
                .andExpect(jsonPath("$.message", containsString("passengerSurcharge")));

        verifyNoInteractions(rentalSettingsService);
    }

    @Test
    void find_withoutBranchClaim_returnsBranchRequired() throws Exception {
        mockMvc.perform(get("/rental-settings").with(user("BRANCH_ADMIN", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_REQUIRED));

        verifyNoInteractions(rentalSettingsService);
    }

    @Test
    void update_asCustomer_returnsAccessDenied() throws Exception {
        mockMvc.perform(put("/rental-settings").with(user("CUSTOMER", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerKm\":10.00,\"passengerSurcharge\":5.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));

        verifyNoInteractions(rentalSettingsService);
    }

    @Test
    void find_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/rental-settings"))
                .andExpect(status().isUnauthorized());
    }

    private static RentalSettingsResponse settings() {
        return new RentalSettingsResponse(BRANCH_ID, new BigDecimal("10.00"), new BigDecimal("5.00"));
    }

    private static RequestPostProcessor user(String role, UUID branchId) {
        return jwt()
                .jwt(token -> {
                    token.subject("99999999-9999-9999-9999-999999999999").claim("roles", List.of(role));
                    if (branchId != null) {
                        token.claim("branchId", branchId.toString());
                    }
                })
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}

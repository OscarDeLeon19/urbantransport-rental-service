package com.urbantransport.rental.web;

import com.urbantransport.rental.AbstractIntegrationTest;
import com.urbantransport.rental.domain.RentalSettingsEntity;
import com.urbantransport.rental.exception.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RentalSettingsApiIT extends AbstractIntegrationTest {

    private final UUID branchId = UUID.randomUUID();

    @Test
    void find_forBranchWithoutSettings_returnsNotFound() throws Exception {
        mockMvc.perform(get("/rental-settings").with(branchAdmin(branchId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCodes.RENTAL_SETTINGS_NOT_FOUND));
    }

    @Test
    void update_createsThenUpdatesTheSameRow() throws Exception {
        mockMvc.perform(put("/rental-settings").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerKm\":8.5,\"passengerSurcharge\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchId").value(branchId.toString()))
                .andExpect(jsonPath("$.pricePerKm").value(8.5));

        mockMvc.perform(put("/rental-settings").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerKm\":12,\"passengerSurcharge\":4.75}"))
                .andExpect(status().isOk());

        assertEquals(1, rentalSettingsRepository.count());
        RentalSettingsEntity saved = rentalSettingsRepository.findById(branchId).orElseThrow();
        assertEquals(0, new BigDecimal("12.00").compareTo(saved.getPricePerKm()));
        assertEquals(0, new BigDecimal("4.75").compareTo(saved.getPassengerSurcharge()));

        mockMvc.perform(get("/rental-settings").with(branchAdmin(branchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passengerSurcharge").value(4.75));
    }

    @Test
    void update_enablesEstimatesForTheBranch() throws Exception {
        mockMvc.perform(put("/rental-settings").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerKm\":10,\"passengerSurcharge\":5}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/rentals/estimate").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"branchId":"%s","originText":"A","destinationText":"B","departureDate":"%s",
                                 "passengers":20,"distanceKm":100}
                                """.formatted(branchId, today())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedPrice").value(1100.0));
    }

    @Test
    void update_withInvalidRates_returnsValidationErrorAndSavesNothing() throws Exception {
        mockMvc.perform(put("/rental-settings").with(branchAdmin(branchId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerKm\":0,\"passengerSurcharge\":1.234}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR));

        assertEquals(0, rentalSettingsRepository.count());
    }

    @Test
    void update_asCustomer_returnsAccessDenied() throws Exception {
        mockMvc.perform(put("/rental-settings").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerKm\":10,\"passengerSurcharge\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.ACCESS_DENIED));

        assertEquals(0, rentalSettingsRepository.count());
    }

    @Test
    void update_withoutBranchClaim_returnsBranchRequired() throws Exception {
        mockMvc.perform(put("/rental-settings").with(userWithRole("BRANCH_ADMIN", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerKm\":10,\"passengerSurcharge\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.BRANCH_REQUIRED));
    }

    @Test
    void find_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/rental-settings"))
                .andExpect(status().isUnauthorized());
    }
}

package com.urbantransport.rental.web;

import com.urbantransport.rental.dto.RentalSettingsResponse;
import com.urbantransport.rental.dto.UpdateRentalSettingsRequest;
import com.urbantransport.rental.exception.ApiException;
import com.urbantransport.rental.exception.ErrorCodes;
import com.urbantransport.rental.service.RentalSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/rental-settings")
@RequiredArgsConstructor
public class RentalSettingsController {

    private final RentalSettingsService rentalSettingsService;

    @GetMapping
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public RentalSettingsResponse find(@AuthenticationPrincipal Jwt jwt) {
        return rentalSettingsService.findByBranch(branchIdOf(jwt));
    }

    @PutMapping
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public RentalSettingsResponse update(@Valid @RequestBody UpdateRentalSettingsRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return rentalSettingsService.update(branchIdOf(jwt), request);
    }

    private UUID branchIdOf(Jwt jwt) {
        String branchId = jwt.getClaimAsString("branchId");
        if (branchId == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.BRANCH_REQUIRED, "El usuario no tiene sucursal asignada");
        }
        return UUID.fromString(branchId);
    }
}

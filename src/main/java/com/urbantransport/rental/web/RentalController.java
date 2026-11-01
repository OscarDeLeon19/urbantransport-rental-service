package com.urbantransport.rental.web;

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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/rentals")
@RequiredArgsConstructor
public class RentalController {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    private final RentalService rentalService;

    @PostMapping("/estimate")
    @PreAuthorize("hasRole('CUSTOMER')")
    public RentalEstimateResponse estimate(@Valid @RequestBody CreateRentalRequest request) {
        return rentalService.estimate(request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public RentalResponse request(@Valid @RequestBody CreateRentalRequest request, @AuthenticationPrincipal Jwt jwt) {
        return rentalService.requestOnline(request, UUID.fromString(jwt.getSubject()), nitOf(jwt));
    }

    @PostMapping("/counter")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public RentalResponse registerCounter(@Valid @RequestBody CounterRentalRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        return rentalService.registerCounter(request, branchIdOf(jwt), jwt.getTokenValue());
    }

    @PutMapping("/{id}/quote")
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public RentalResponse quote(@PathVariable UUID id, @Valid @RequestBody QuoteRentalRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        return rentalService.quote(id, request, branchIdOf(jwt));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public RentalResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectRentalRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        return rentalService.reject(id, request, branchIdOf(jwt));
    }

    @PutMapping("/{id}/assign")
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public RentalResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignRentalRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        return rentalService.assign(id, request, branchIdOf(jwt), UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/{id}/pay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public RentalResponse pay(@PathVariable UUID id, @Valid @RequestBody PayRentalRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        return rentalService.pay(id, request, UUID.fromString(jwt.getSubject()), customerIdOf(jwt));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRANCH_ADMIN')")
    public RentalResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        if (isCustomer(jwt)) {
            return rentalService.cancelAsCustomer(id, UUID.fromString(jwt.getSubject()));
        }
        return rentalService.cancelAsAdmin(id, branchIdOf(jwt));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PageResponse<RentalResponse> searchMine(@RequestParam(required = false) RentalStatus status,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return rentalService.searchForCustomer(UUID.fromString(jwt.getSubject()), nitOf(jwt), status, from, to,
                page, size);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BRANCH_ADMIN', 'SYSTEM_ADMIN')")
    public PageResponse<RentalResponse> searchBranch(@RequestParam(required = false) RentalStatus status,
                                                     @RequestParam(required = false) UUID branchId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return rentalService.searchForBranch(resolveBranchFilter(jwt, branchId), status, from, to, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRANCH_ADMIN', 'SYSTEM_ADMIN')")
    public RentalResponse findById(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        if (isCustomer(jwt)) {
            return rentalService.findForCustomer(id, UUID.fromString(jwt.getSubject()), nitOf(jwt));
        }
        return rentalService.findForStaff(id, isSystemAdmin(jwt) ? null : branchIdOf(jwt));
    }

    @PutMapping("/{id}/reschedule")
    @PreAuthorize("hasRole('BRANCH_ADMIN')")
    public RentalResponse reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRentalRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        return rentalService.reschedule(id, request, branchIdOf(jwt));
    }

    private UUID resolveBranchFilter(Jwt jwt, UUID requestedBranchId) {
        if (isSystemAdmin(jwt)) {
            return requestedBranchId;
        }
        UUID ownBranchId = branchIdOf(jwt);
        if (requestedBranchId != null && !requestedBranchId.equals(ownBranchId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.BRANCH_FORBIDDEN,
                    "Solo puedes consultar alquileres de tu sucursal");
        }
        return ownBranchId;
    }

    private boolean isCustomer(Jwt jwt) {
        return hasRole(jwt, CUSTOMER);
    }

    private boolean isSystemAdmin(Jwt jwt) {
        return hasRole(jwt, SYSTEM_ADMIN);
    }

    private boolean hasRole(Jwt jwt, String role) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains(role);
    }

    private UUID customerIdOf(Jwt jwt) {
        String customerId = jwt.getClaimAsString("customerId");
        if (customerId == null || customerId.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.CUSTOMER_ID_REQUIRED,
                    "El token no pertenece a un cliente");
        }
        return UUID.fromString(customerId);
    }

    private UUID branchIdOf(Jwt jwt) {
        String branchId = jwt.getClaimAsString("branchId");
        if (branchId == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.BRANCH_REQUIRED, "El usuario no tiene sucursal asignada");
        }
        return UUID.fromString(branchId);
    }

    private String nitOf(Jwt jwt) {
        String nit = jwt.getClaimAsString("nit");
        if (nit == null || nit.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.CUSTOMER_NIT_REQUIRED,
                    "El token no incluye el NIT del cliente");
        }
        return nit.trim().toUpperCase(Locale.ROOT);
    }
}

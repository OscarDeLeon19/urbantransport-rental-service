package com.urbantransport.rental.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateRentalRequest(
        @NotNull UUID branchId,
        @NotBlank @Size(max = 200) String originText,
        @NotBlank @Size(max = 200) String destinationText,
        @NotNull LocalDate departureDate,
        LocalDate returnDate,
        @NotNull @Positive Integer passengers,
        @NotNull @Positive @Digits(integer = 6, fraction = 2) BigDecimal distanceKm) {
}

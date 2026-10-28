package com.urbantransport.rental.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record UpdateRentalSettingsRequest(
        @NotNull @Positive @Digits(integer = 8, fraction = 2) BigDecimal pricePerKm,
        @NotNull @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal passengerSurcharge) {
}

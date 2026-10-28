package com.urbantransport.rental.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record QuoteRentalRequest(
        @Positive @Digits(integer = 8, fraction = 2) BigDecimal price) {
}

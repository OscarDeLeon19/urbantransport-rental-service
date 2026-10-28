package com.urbantransport.rental.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record RescheduleRentalRequest(
        @NotNull LocalDate departureDate,
        LocalDate returnDate) {
}

package com.urbantransport.rental.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record AssignRentalRequest(
        @NotNull UUID busId,
        @NotNull UUID driverId,
        @NotNull Instant departureAt,
        @NotNull Instant estimatedArrival,
        Instant returnDepartureAt,
        Instant returnEstimatedArrival) {
}

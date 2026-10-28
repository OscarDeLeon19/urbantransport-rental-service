package com.urbantransport.rental.dto;

import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
public class RentalEstimateResponse {
    UUID branchId;
    BigDecimal distanceKm;
    int legs;
    Integer passengers;
    BigDecimal pricePerKm;
    BigDecimal passengerSurcharge;
    BigDecimal estimatedPrice;
}

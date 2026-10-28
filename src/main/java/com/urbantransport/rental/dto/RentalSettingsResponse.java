package com.urbantransport.rental.dto;

import com.urbantransport.rental.domain.RentalSettingsEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
public class RentalSettingsResponse {
    UUID branchId;
    BigDecimal pricePerKm;
    BigDecimal passengerSurcharge;

    public static RentalSettingsResponse fromEntity(RentalSettingsEntity settings) {
        return new RentalSettingsResponse(
                settings.getBranchId(),
                settings.getPricePerKm(),
                settings.getPassengerSurcharge());
    }
}

package com.urbantransport.rental.service.impl;

import com.urbantransport.rental.domain.RentalSettingsEntity;
import com.urbantransport.rental.dto.RentalSettingsResponse;
import com.urbantransport.rental.dto.UpdateRentalSettingsRequest;
import com.urbantransport.rental.exception.ApiException;
import com.urbantransport.rental.exception.ErrorCodes;
import com.urbantransport.rental.repository.RentalSettingsRepository;
import com.urbantransport.rental.service.RentalSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RentalSettingsServiceImpl implements RentalSettingsService {

    private final RentalSettingsRepository rentalSettingsRepository;

    @Override
    @Transactional(readOnly = true)
    public RentalSettingsResponse findByBranch(UUID branchId) {
        return rentalSettingsRepository.findById(branchId)
                .map(RentalSettingsResponse::fromEntity)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RENTAL_SETTINGS_NOT_FOUND,
                        "La sucursal no tiene configuradas las tarifas de alquiler"));
    }

    @Override
    @Transactional
    public RentalSettingsResponse update(UUID branchId, UpdateRentalSettingsRequest request) {
        RentalSettingsEntity settings = rentalSettingsRepository.findById(branchId)
                .orElseGet(() -> RentalSettingsEntity.builder().branchId(branchId).build());
        settings.setPricePerKm(request.pricePerKm().setScale(2, RoundingMode.HALF_UP));
        settings.setPassengerSurcharge(request.passengerSurcharge().setScale(2, RoundingMode.HALF_UP));
        return RentalSettingsResponse.fromEntity(rentalSettingsRepository.save(settings));
    }
}

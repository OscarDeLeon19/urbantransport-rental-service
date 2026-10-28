package com.urbantransport.rental.service;

import com.urbantransport.rental.dto.RentalSettingsResponse;
import com.urbantransport.rental.dto.UpdateRentalSettingsRequest;

import java.util.UUID;

public interface RentalSettingsService {

    RentalSettingsResponse findByBranch(UUID branchId);

    RentalSettingsResponse update(UUID branchId, UpdateRentalSettingsRequest request);
}

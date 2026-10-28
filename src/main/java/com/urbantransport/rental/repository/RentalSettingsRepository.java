package com.urbantransport.rental.repository;

import com.urbantransport.rental.domain.RentalSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RentalSettingsRepository extends JpaRepository<RentalSettingsEntity, UUID> {
}

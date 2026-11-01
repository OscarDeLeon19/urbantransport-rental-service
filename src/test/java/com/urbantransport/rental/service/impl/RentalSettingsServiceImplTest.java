package com.urbantransport.rental.service.impl;

import com.urbantransport.rental.domain.RentalSettingsEntity;
import com.urbantransport.rental.dto.RentalSettingsResponse;
import com.urbantransport.rental.dto.UpdateRentalSettingsRequest;
import com.urbantransport.rental.exception.ApiException;
import com.urbantransport.rental.exception.ErrorCodes;
import com.urbantransport.rental.repository.RentalSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RentalSettingsServiceImplTest {

    private static final UUID BRANCH_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private RentalSettingsRepository rentalSettingsRepository;

    @Captor
    private ArgumentCaptor<RentalSettingsEntity> settingsCaptor;

    private RentalSettingsServiceImpl settingsService;

    @BeforeEach
    void setUp() {
        settingsService = new RentalSettingsServiceImpl(rentalSettingsRepository);
    }

    @Nested
    class FindByBranch {

        @Test
        void configuredBranch_returnsItsRates() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.of(settings()));

            RentalSettingsResponse response = settingsService.findByBranch(BRANCH_ID);

            assertEquals(BRANCH_ID, response.getBranchId());
            assertEquals(new BigDecimal("10.00"), response.getPricePerKm());
            assertEquals(new BigDecimal("5.00"), response.getPassengerSurcharge());
        }

        @Test
        void branchWithoutSettings_throwsNotFound() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.empty());

            ApiException exception = assertThrows(ApiException.class, () -> settingsService.findByBranch(BRANCH_ID));

            assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
            assertEquals(ErrorCodes.RENTAL_SETTINGS_NOT_FOUND, exception.getCode());
        }
    }

    @Nested
    class Update {

        @Test
        void branchWithoutSettings_createsRoundedRates() {
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.empty());
            when(rentalSettingsRepository.save(any(RentalSettingsEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            RentalSettingsResponse response = settingsService.update(BRANCH_ID,
                    new UpdateRentalSettingsRequest(new BigDecimal("8.5"), BigDecimal.ZERO));

            verify(rentalSettingsRepository).save(settingsCaptor.capture());
            RentalSettingsEntity saved = settingsCaptor.getValue();
            assertEquals(BRANCH_ID, saved.getBranchId());
            assertEquals(new BigDecimal("8.50"), saved.getPricePerKm());
            assertEquals(new BigDecimal("0.00"), saved.getPassengerSurcharge());
            assertEquals(new BigDecimal("8.50"), response.getPricePerKm());
        }

        @Test
        void configuredBranch_updatesExistingRates() {
            RentalSettingsEntity existing = settings();
            when(rentalSettingsRepository.findById(BRANCH_ID)).thenReturn(Optional.of(existing));
            when(rentalSettingsRepository.save(any(RentalSettingsEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            settingsService.update(BRANCH_ID, new UpdateRentalSettingsRequest(new BigDecimal("12"),
                    new BigDecimal("7.25")));

            verify(rentalSettingsRepository).save(settingsCaptor.capture());
            assertSame(existing, settingsCaptor.getValue());
            assertEquals(new BigDecimal("12.00"), existing.getPricePerKm());
            assertEquals(new BigDecimal("7.25"), existing.getPassengerSurcharge());
        }
    }

    private static RentalSettingsEntity settings() {
        return RentalSettingsEntity.builder()
                .branchId(BRANCH_ID)
                .pricePerKm(new BigDecimal("10.00"))
                .passengerSurcharge(new BigDecimal("5.00"))
                .build();
    }
}

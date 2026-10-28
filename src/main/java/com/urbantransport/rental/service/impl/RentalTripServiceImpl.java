package com.urbantransport.rental.service.impl;

import com.urbantransport.rental.domain.ProcessedEventEntity;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import com.urbantransport.rental.repository.ProcessedEventRepository;
import com.urbantransport.rental.repository.RentalRepository;
import com.urbantransport.rental.service.RentalTripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RentalTripServiceImpl implements RentalTripService {

    private static final int MAX_REASON = 255;
    private static final String UNKNOWN_REASON = "trip-service rechazó la asignación";
    private static final int ONE_WAY_TRIPS = 1;
    private static final int ROUND_TRIP_TRIPS = 2;

    private final RentalRepository rentalRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Override
    @Transactional
    public void applyPrivateTripRejected(UUID eventId, String eventType, UUID rentalId, String reason) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }

        RentalEntity rental = rentalRepository.findByIdForUpdate(rentalId)
                .orElseThrow(() -> new IllegalStateException("Rechazo de viaje de un alquiler inexistente: " + rentalId));
        if (rental.getStatus() == RentalStatus.ASSIGNED) {
            rental.setStatus(RentalStatus.PAID);
            rental.setBusId(null);
            rental.setDriverId(null);
            rental.setAssignmentRejectionReason(rejectionReason(reason));
        } else {
            log.warn("Rechazo de viaje para el alquiler {} en estado {}; no se cambia su estado",
                    rentalId, rental.getStatus());
        }

        markProcessed(eventId, eventType);
    }

    @Override
    @Transactional
    public void applyTripCompleted(UUID eventId, String eventType, UUID rentalId, UUID tripId) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }

        RentalEntity rental = rentalRepository.findByIdForUpdate(rentalId)
                .orElseThrow(() -> new IllegalStateException("Viaje completado de un alquiler inexistente: " + rentalId));
        if (rental.getStatus() == RentalStatus.ASSIGNED) {
            int completed = (rental.getCompletedTrips() == null ? 0 : rental.getCompletedTrips()) + 1;
            rental.setCompletedTrips(completed);
            if (completed >= expectedTrips(rental)) {
                rental.setStatus(RentalStatus.COMPLETED);
                log.info("Alquiler {} completado con el viaje {}", rentalId, tripId);
            }
        } else {
            log.warn("Viaje {} completado para el alquiler {} en estado {}; no se cambia su estado",
                    tripId, rentalId, rental.getStatus());
        }

        markProcessed(eventId, eventType);
    }

    private int expectedTrips(RentalEntity rental) {
        return rental.getReturnDate() == null ? ONE_WAY_TRIPS : ROUND_TRIP_TRIPS;
    }

    private String rejectionReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return UNKNOWN_REASON;
        }
        return reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
    }

    private void markProcessed(UUID eventId, String eventType) {
        processedEventRepository.save(ProcessedEventEntity.builder()
                .eventId(eventId)
                .eventType(eventType)
                .build());
    }
}

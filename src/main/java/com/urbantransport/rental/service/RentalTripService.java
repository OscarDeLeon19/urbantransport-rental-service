package com.urbantransport.rental.service;

import java.util.UUID;

public interface RentalTripService {

    void applyPrivateTripRejected(UUID eventId, String eventType, UUID rentalId, String reason);

    void applyTripCompleted(UUID eventId, String eventType, UUID rentalId, UUID tripId);
}

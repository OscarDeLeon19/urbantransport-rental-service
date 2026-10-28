package com.urbantransport.rental.service;

import java.time.Instant;
import java.util.UUID;

public interface RentalPaymentService {

    void applyPaymentCompleted(UUID eventId, String eventType, UUID rentalId);

    void applyPaymentFailed(UUID eventId, String eventType, UUID rentalId, String reason);

    int resendStalePayments(Instant requestedBefore);
}

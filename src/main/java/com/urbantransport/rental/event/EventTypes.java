package com.urbantransport.rental.event;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EventTypes {

    public static final String AGGREGATE_RENTAL = "Rental";
    public static final String REFERENCE_RENTAL = "RENTAL";

    public static final String RENTAL_REQUESTED = "RentalRequested";
    public static final String RENTAL_QUOTED = "RentalQuoted";
    public static final String PAYMENT_REQUESTED = "PaymentRequested";
    public static final String RENTAL_PAID = "RentalPaid";
    public static final String RENTAL_ASSIGNED = "RentalAssigned";
    public static final String RENTAL_REJECTED = "RentalRejected";

    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String PRIVATE_TRIP_REJECTED = "PrivateTripRejected";
    public static final String TRIP_COMPLETED = "TripCompleted";
}

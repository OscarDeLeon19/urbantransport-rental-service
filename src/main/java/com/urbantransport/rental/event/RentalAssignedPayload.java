package com.urbantransport.rental.event;

import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.dto.AssignRentalRequest;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Value
public class RentalAssignedPayload {
    UUID rentalId;
    UUID branchId;
    UUID busId;
    UUID driverId;
    Integer passengers;
    BigDecimal distanceKm;
    String customerNit;
    UUID customerUserId;
    UUID assignedBy;
    List<Leg> legs;

    public static RentalAssignedPayload from(RentalEntity rental, AssignRentalRequest request, UUID assignedBy) {
        List<Leg> legs = new ArrayList<>();
        legs.add(new Leg(rental.getOriginText(), rental.getDestinationText(),
                request.departureAt(), request.estimatedArrival()));
        if (rental.getReturnDate() != null) {
            legs.add(new Leg(rental.getDestinationText(), rental.getOriginText(),
                    request.returnDepartureAt(), request.returnEstimatedArrival()));
        }
        return new RentalAssignedPayload(
                rental.getId(),
                rental.getBranchId(),
                rental.getBusId(),
                rental.getDriverId(),
                rental.getPassengers(),
                rental.getDistanceKm(),
                rental.getCustomerNit(),
                rental.getCustomerUserId(),
                assignedBy,
                legs);
    }

    @Value
    public static class Leg {
        String originText;
        String destinationText;
        Instant departureAt;
        Instant estimatedArrival;
    }
}

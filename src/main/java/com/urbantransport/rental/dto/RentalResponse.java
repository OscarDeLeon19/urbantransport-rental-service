package com.urbantransport.rental.dto;

import com.urbantransport.rental.domain.RentalChannel;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Value
public class RentalResponse {
    UUID id;
    RentalChannel channel;
    String customerNit;
    UUID customerUserId;
    UUID branchId;
    String originText;
    String destinationText;
    LocalDate departureDate;
    LocalDate returnDate;
    Integer passengers;
    BigDecimal distanceKm;
    BigDecimal estimatedPrice;
    BigDecimal confirmedPrice;
    RentalStatus status;
    UUID busId;
    UUID driverId;
    LocalDate paymentDate;
    String rejectionReason;
    String paymentFailureReason;
    String assignmentRejectionReason;
    Integer completedTrips;
    Instant createdAt;

    public static RentalResponse fromEntity(RentalEntity rental) {
        return new RentalResponse(
                rental.getId(),
                rental.getChannel(),
                rental.getCustomerNit(),
                rental.getCustomerUserId(),
                rental.getBranchId(),
                rental.getOriginText(),
                rental.getDestinationText(),
                rental.getDepartureDate(),
                rental.getReturnDate(),
                rental.getPassengers(),
                rental.getDistanceKm(),
                rental.getEstimatedPrice(),
                rental.getConfirmedPrice(),
                rental.getStatus(),
                rental.getBusId(),
                rental.getDriverId(),
                rental.getPaymentDate(),
                rental.getRejectionReason(),
                rental.getPaymentFailureReason(),
                rental.getAssignmentRejectionReason(),
                rental.getCompletedTrips(),
                rental.getCreatedAt());
    }
}

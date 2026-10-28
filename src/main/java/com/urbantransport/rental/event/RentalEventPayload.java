package com.urbantransport.rental.event;

import com.urbantransport.rental.domain.RentalChannel;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Value
public class RentalEventPayload {
    UUID rentalId;
    RentalChannel channel;
    UUID branchId;
    String customerNit;
    UUID customerUserId;
    String originText;
    String destinationText;
    LocalDate departureDate;
    LocalDate returnDate;
    Integer passengers;
    BigDecimal distanceKm;
    BigDecimal estimatedPrice;
    BigDecimal confirmedPrice;
    LocalDate paymentDate;
    String rejectionReason;
    RentalStatus status;

    public static RentalEventPayload fromEntity(RentalEntity rental) {
        return new RentalEventPayload(
                rental.getId(),
                rental.getChannel(),
                rental.getBranchId(),
                rental.getCustomerNit(),
                rental.getCustomerUserId(),
                rental.getOriginText(),
                rental.getDestinationText(),
                rental.getDepartureDate(),
                rental.getReturnDate(),
                rental.getPassengers(),
                rental.getDistanceKm(),
                rental.getEstimatedPrice(),
                rental.getConfirmedPrice(),
                rental.getPaymentDate(),
                rental.getRejectionReason(),
                rental.getStatus());
    }
}

package com.urbantransport.rental.event;

import com.urbantransport.rental.domain.RentalEntity;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Value
public class PaymentRequestedPayload {
    String referenceType;
    UUID referenceId;
    UUID customerId;
    String customerNit;
    BigDecimal amount;
    LocalDate paymentDate;

    public static PaymentRequestedPayload from(RentalEntity rental) {
        return new PaymentRequestedPayload(
                EventTypes.REFERENCE_RENTAL,
                rental.getId(),
                rental.getCustomerId(),
                rental.getCustomerNit(),
                rental.getConfirmedPrice(),
                rental.getPaymentDate());
    }
}

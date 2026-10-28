package com.urbantransport.rental.dto;

import java.time.LocalDate;

public record PayRentalRequest(LocalDate paymentDate) {
}

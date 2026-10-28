package com.urbantransport.rental.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rentals")
public record RentalProperties(Duration paymentResendAfter) {
}

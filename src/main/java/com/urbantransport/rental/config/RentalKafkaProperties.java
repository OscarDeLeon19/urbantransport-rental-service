package com.urbantransport.rental.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.kafka.topic")
public record RentalKafkaProperties(String rentalEvents, String paymentEvents, String tripEvents) {
}

package com.urbantransport.rental.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.customer")
public record CustomerClientProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        CircuitBreakerProperties circuitBreaker) {

    public record CircuitBreakerProperties(
            int slidingWindowSize,
            int minimumCalls,
            float failureRateThreshold,
            Duration waitInOpenState) {
    }
}

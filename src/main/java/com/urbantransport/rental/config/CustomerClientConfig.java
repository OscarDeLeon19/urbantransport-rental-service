package com.urbantransport.rental.config;

import com.urbantransport.rental.exception.CustomerUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Slf4j
@Configuration
public class CustomerClientConfig {

    @Bean
    public RestClient customerRestClient(CustomerClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    public CircuitBreaker customerCircuitBreaker(CustomerClientProperties properties) {
        CustomerClientProperties.CircuitBreakerProperties settings = properties.circuitBreaker();
        CircuitBreaker circuitBreaker = CircuitBreaker.of("customer", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitInOpenState())
                .recordExceptions(CustomerUnavailableException.class)
                .build());
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Circuit breaker de customer: {}", event.getStateTransition()));
        return circuitBreaker;
    }
}

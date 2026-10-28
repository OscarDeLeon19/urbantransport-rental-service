package com.urbantransport.rental.client;

import com.urbantransport.rental.exception.ApiException;
import com.urbantransport.rental.exception.CustomerUnavailableException;
import com.urbantransport.rental.exception.ErrorCodes;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerClientImpl implements CustomerClient {

    private static final String BY_NIT_PATH = "/customers/by-nit/{nit}";
    private static final String UNAVAILABLE_MESSAGE =
            "El servicio de clientes no está disponible, intenta más tarde";
    private static final String ERROR_MESSAGE = "No se pudo consultar al cliente, intenta más tarde";

    private final RestClient customerRestClient;
    private final CircuitBreaker customerCircuitBreaker;

    @Override
    public CustomerSummaryResponse getByNit(String nit, String accessToken) {
        try {
            return customerCircuitBreaker.executeSupplier(() -> fetchByNit(nit, accessToken));
        } catch (CallNotPermittedException | CustomerUnavailableException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CUSTOMER_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        }
    }

    private CustomerSummaryResponse fetchByNit(String nit, String accessToken) {
        try {
            CustomerSummaryResponse customer = customerRestClient.get()
                    .uri(BY_NIT_PATH, nit)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .onStatus(status -> status.is5xxServerError(), (req, res) -> {
                        throw new CustomerUnavailableException("Customer respondió " + res.getStatusCode());
                    })
                    .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (req, res) -> {
                        throw new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.CUSTOMER_NOT_FOUND,
                                "El cliente con NIT " + nit + " no está registrado; regístralo primero");
                    })
                    .onStatus(status -> status.is4xxClientError(), (req, res) -> {
                        log.error("Customer rechazó la consulta del NIT {}: {}", nit, res.getStatusCode());
                        throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CUSTOMER_ERROR, ERROR_MESSAGE);
                    })
                    .body(CustomerSummaryResponse.class);
            if (customer == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.CUSTOMER_ERROR, ERROR_MESSAGE);
            }
            return customer;
        } catch (ResourceAccessException exception) {
            throw new CustomerUnavailableException("No se pudo contactar a customer", exception);
        }
    }
}

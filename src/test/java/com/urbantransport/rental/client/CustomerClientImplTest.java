package com.urbantransport.rental.client;

import com.urbantransport.rental.exception.ApiException;
import com.urbantransport.rental.exception.CustomerUnavailableException;
import com.urbantransport.rental.exception.ErrorCodes;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CustomerClientImplTest {

    private static final String BASE_URL = "http://customer.test";
    private static final String NIT = "12345678";
    private static final String TOKEN = "token-del-admin";
    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BY_NIT_URL = BASE_URL + "/customers/by-nit/" + NIT;

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;
    private CustomerClientImpl customerClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        circuitBreaker = CircuitBreaker.of("customer", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .recordExceptions(CustomerUnavailableException.class)
                .build());
        customerClient = new CustomerClientImpl(builder.build(), circuitBreaker);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void getByNit_forwardsUserTokenAndReturnsCustomer() {
        server.expect(requestTo(BY_NIT_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess("""
                        {"id":"%s","nit":"%s","fullName":"Juan Perez","generic":false,"hasAccount":true,"extra":1}
                        """.formatted(CUSTOMER_ID, NIT), MediaType.APPLICATION_JSON));

        CustomerSummaryResponse customer = customerClient.getByNit(NIT, TOKEN);

        assertEquals(CUSTOMER_ID, customer.id());
        assertEquals(NIT, customer.nit());
        assertEquals("Juan Perez", customer.fullName());
        assertFalse(customer.generic());
        assertTrue(customer.hasAccount());
    }

    @Test
    void unknownNit_throwsCustomerNotFound() {
        server.expect(requestTo(BY_NIT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.CUSTOMER_NOT_FOUND, () -> customerClient.getByNit(NIT, TOKEN));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 409})
    void otherClientError_throwsBadGateway(int status) {
        server.expect(requestTo(BY_NIT_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.CUSTOMER_ERROR, () -> customerClient.getByNit(NIT, TOKEN));
    }

    @Test
    void emptyBody_throwsBadGateway() {
        server.expect(requestTo(BY_NIT_URL)).andRespond(withSuccess());

        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.CUSTOMER_ERROR, () -> customerClient.getByNit(NIT, TOKEN));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void serverError_throwsServiceUnavailable(int status) {
        server.expect(requestTo(BY_NIT_URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CUSTOMER_UNAVAILABLE,
                () -> customerClient.getByNit(NIT, TOKEN));
    }

    @Test
    void timeout_throwsServiceUnavailable() {
        server.expect(requestTo(BY_NIT_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CUSTOMER_UNAVAILABLE,
                () -> customerClient.getByNit(NIT, TOKEN));
    }

    @Test
    void openCircuit_failsFastWithoutCallingCustomer() {
        circuitBreaker.transitionToOpenState();

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CUSTOMER_UNAVAILABLE,
                () -> customerClient.getByNit(NIT, TOKEN));
    }

    @Test
    void repeatedServerErrors_openCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(BY_NIT_URL)).andRespond(withServerError());

        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CUSTOMER_UNAVAILABLE,
                () -> customerClient.getByNit(NIT, TOKEN));
        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CUSTOMER_UNAVAILABLE,
                () -> customerClient.getByNit(NIT, TOKEN));

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertApiError(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.CUSTOMER_UNAVAILABLE,
                () -> customerClient.getByNit(NIT, TOKEN));
    }

    @Test
    void repeatedNotFound_doesNotOpenCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(BY_NIT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.CUSTOMER_NOT_FOUND, () -> customerClient.getByNit(NIT, TOKEN));
        assertApiError(HttpStatus.NOT_FOUND, ErrorCodes.CUSTOMER_NOT_FOUND, () -> customerClient.getByNit(NIT, TOKEN));

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void repeatedClientErrors_doNotOpenCircuit() {
        server.expect(ExpectedCount.times(2), requestTo(BY_NIT_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.CUSTOMER_ERROR, () -> customerClient.getByNit(NIT, TOKEN));
        assertApiError(HttpStatus.BAD_GATEWAY, ErrorCodes.CUSTOMER_ERROR, () -> customerClient.getByNit(NIT, TOKEN));

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    private static void assertApiError(HttpStatus status, String code, Executable action) {
        ApiException exception = assertThrows(ApiException.class, action);
        assertEquals(status, exception.getStatus());
        assertEquals(code, exception.getCode());
    }
}

package com.urbantransport.rental.event;

import com.urbantransport.rental.service.RentalPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentEventsListenerTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RENTAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CUSTOMER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private RentalPaymentService rentalPaymentService;

    private PaymentEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new PaymentEventsListener(jsonMapper, rentalPaymentService);
    }

    @Test
    void rentalPaymentCompleted_isApplied() {
        listener.onPaymentEvent(message(EventTypes.PAYMENT_COMPLETED, result("RENTAL", null)));

        verify(rentalPaymentService).applyPaymentCompleted(EVENT_ID, EventTypes.PAYMENT_COMPLETED, RENTAL_ID);
    }

    @Test
    void rentalPaymentFailed_passesReason() {
        listener.onPaymentEvent(message(EventTypes.PAYMENT_FAILED, result("RENTAL", "INSUFFICIENT_FUNDS")));

        verify(rentalPaymentService).applyPaymentFailed(EVENT_ID, EventTypes.PAYMENT_FAILED, RENTAL_ID,
                "INSUFFICIENT_FUNDS");
    }

    @Test
    void rentalPaymentFailedWithoutReason_passesNull() {
        listener.onPaymentEvent(message(EventTypes.PAYMENT_FAILED, result("RENTAL", null)));

        verify(rentalPaymentService).applyPaymentFailed(EVENT_ID, EventTypes.PAYMENT_FAILED, RENTAL_ID, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER", "TICKET", "rental"})
    void paymentOfOtherReference_isIgnored(String referenceType) {
        listener.onPaymentEvent(message(EventTypes.PAYMENT_COMPLETED, result(referenceType, null)));

        verifyNoInteractions(rentalPaymentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RefundCompleted", "WalletRecharged", "PaymentRequested"})
    void otherPaymentEvent_isIgnored(String eventType) {
        listener.onPaymentEvent(message(eventType, Map.of("customerId", CUSTOMER_ID.toString())));

        verifyNoInteractions(rentalPaymentService);
    }

    @Test
    void eventWithoutPayload_isIgnored() {
        listener.onPaymentEvent("{\"eventId\":\"%s\",\"eventType\":\"PaymentCompleted\"}".formatted(EVENT_ID));

        verifyNoInteractions(rentalPaymentService);
    }

    @Test
    void eventWithoutType_isIgnored() {
        listener.onPaymentEvent("{\"eventId\":\"%s\",\"payload\":{\"referenceType\":\"RENTAL\"}}".formatted(EVENT_ID));

        verifyNoInteractions(rentalPaymentService);
    }

    @Test
    void malformedMessage_throwsSoItGoesToRetryAndDlt() {
        assertThrows(JacksonException.class, () -> listener.onPaymentEvent("{no es json"));

        verifyNoInteractions(rentalPaymentService);
    }

    private static Map<String, Object> result(String referenceType, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("referenceType", referenceType);
        payload.put("referenceId", RENTAL_ID.toString());
        payload.put("reason", reason);
        return payload;
    }

    private String message(String eventType, Map<String, Object> payload) {
        return jsonMapper.writeValueAsString(Map.of(
                "eventId", EVENT_ID.toString(),
                "eventType", eventType,
                "occurredAt", "2026-10-29T12:00:00Z",
                "aggregateId", CUSTOMER_ID.toString(),
                "version", 1,
                "payload", payload));
    }
}

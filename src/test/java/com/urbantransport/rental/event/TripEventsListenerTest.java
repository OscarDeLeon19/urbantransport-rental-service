package com.urbantransport.rental.event;

import com.urbantransport.rental.service.RentalTripService;
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
class TripEventsListenerTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RENTAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRIP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String REASON = "El bus ya tiene un viaje en ese horario";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private RentalTripService rentalTripService;

    private TripEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new TripEventsListener(jsonMapper, rentalTripService);
    }

    @Test
    void privateTripRejected_passesRentalAndReason() {
        listener.onTripEvent(message(EventTypes.PRIVATE_TRIP_REJECTED, Map.of("rentalId", RENTAL_ID.toString(),
                "code", "RESOURCE_OVERLAP", "reason", REASON)));

        verify(rentalTripService).applyPrivateTripRejected(EVENT_ID, EventTypes.PRIVATE_TRIP_REJECTED, RENTAL_ID,
                REASON);
    }

    @Test
    void privateTripRejectedWithoutReason_passesNull() {
        listener.onTripEvent(message(EventTypes.PRIVATE_TRIP_REJECTED, Map.of("rentalId", RENTAL_ID.toString())));

        verify(rentalTripService).applyPrivateTripRejected(EVENT_ID, EventTypes.PRIVATE_TRIP_REJECTED, RENTAL_ID,
                null);
    }

    @Test
    void privateTripCompleted_passesRentalAndTrip() {
        listener.onTripEvent(message(EventTypes.TRIP_COMPLETED, tripCompleted("PRIVATE", RENTAL_ID)));

        verify(rentalTripService).applyTripCompleted(EVENT_ID, EventTypes.TRIP_COMPLETED, RENTAL_ID, TRIP_ID);
    }

    @Test
    void regularTripCompleted_isIgnored() {
        listener.onTripEvent(message(EventTypes.TRIP_COMPLETED, tripCompleted("REGULAR", null)));

        verifyNoInteractions(rentalTripService);
    }

    @Test
    void privateTripCompletedWithoutRental_isIgnored() {
        listener.onTripEvent(message(EventTypes.TRIP_COMPLETED, tripCompleted("PRIVATE", null)));

        verifyNoInteractions(rentalTripService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TripScheduled", "TripUpdated", "TripDeleted", "TripStarted"})
    void otherTripEvent_isIgnored(String eventType) {
        listener.onTripEvent(message(eventType, tripCompleted("PRIVATE", RENTAL_ID)));

        verifyNoInteractions(rentalTripService);
    }

    @Test
    void eventWithoutType_isIgnored() {
        listener.onTripEvent("{\"eventId\":\"%s\",\"payload\":{}}".formatted(EVENT_ID));

        verifyNoInteractions(rentalTripService);
    }

    @Test
    void malformedMessage_throwsSoItGoesToRetryAndDlt() {
        assertThrows(JacksonException.class, () -> listener.onTripEvent("{no es json"));

        verifyNoInteractions(rentalTripService);
    }

    private static Map<String, Object> tripCompleted(String type, UUID rentalId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tripId", TRIP_ID.toString());
        payload.put("type", type);
        payload.put("rentalId", rentalId == null ? null : rentalId.toString());
        payload.put("kmTraveled", 120.5);
        return payload;
    }

    private String message(String eventType, Map<String, Object> payload) {
        return jsonMapper.writeValueAsString(Map.of(
                "eventId", EVENT_ID.toString(),
                "eventType", eventType,
                "occurredAt", "2026-10-29T12:00:00Z",
                "aggregateId", TRIP_ID.toString(),
                "version", 1,
                "payload", payload));
    }
}

package com.urbantransport.rental.event;

import com.urbantransport.rental.service.RentalTripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TripEventsListener {

    private static final String PRIVATE_TRIP = "PRIVATE";

    private final JsonMapper jsonMapper;
    private final RentalTripService rentalTripService;

    @KafkaListener(topics = "${app.kafka.topic.trip-events}")
    public void onTripEvent(String message) {
        IncomingEvent event = jsonMapper.readValue(message, IncomingEvent.class);
        String eventType = event.eventType() == null ? "" : event.eventType();
        Map<String, Object> payload = event.payload();

        switch (eventType) {
            case EventTypes.PRIVATE_TRIP_REJECTED -> rentalTripService.applyPrivateTripRejected(event.eventId(),
                    eventType, UUID.fromString(String.valueOf(payload.get("rentalId"))),
                    payload.get("reason") == null ? null : payload.get("reason").toString());
            case EventTypes.TRIP_COMPLETED -> onTripCompleted(event.eventId(), eventType, payload);
            default -> log.debug("Evento {} ignorado", eventType);
        }
    }

    private void onTripCompleted(UUID eventId, String eventType, Map<String, Object> payload) {
        Object rentalId = payload.get("rentalId");
        if (!PRIVATE_TRIP.equals(payload.get("type")) || rentalId == null) {
            log.debug("Viaje {} no es de un alquiler, evento {} ignorado", payload.get("tripId"), eventId);
            return;
        }
        rentalTripService.applyTripCompleted(eventId, eventType, UUID.fromString(rentalId.toString()),
                UUID.fromString(String.valueOf(payload.get("tripId"))));
    }
}

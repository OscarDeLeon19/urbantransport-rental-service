package com.urbantransport.rental.event;

import com.urbantransport.rental.service.RentalPaymentService;
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
public class PaymentEventsListener {

    private final JsonMapper jsonMapper;
    private final RentalPaymentService rentalPaymentService;

    @KafkaListener(topics = "${app.kafka.topic.payment-events}")
    public void onPaymentEvent(String message) {
        IncomingEvent event = jsonMapper.readValue(message, IncomingEvent.class);
        String eventType = event.eventType() == null ? "" : event.eventType();
        Map<String, Object> payload = event.payload();

        if (!EventTypes.PAYMENT_COMPLETED.equals(eventType) && !EventTypes.PAYMENT_FAILED.equals(eventType)) {
            log.debug("Evento {} ignorado", eventType);
            return;
        }
        if (payload == null || !EventTypes.REFERENCE_RENTAL.equals(payload.get("referenceType"))) {
            log.debug("Evento {} {} ignorado: no es un alquiler", eventType, event.eventId());
            return;
        }

        UUID rentalId = UUID.fromString(String.valueOf(payload.get("referenceId")));
        if (EventTypes.PAYMENT_COMPLETED.equals(eventType)) {
            rentalPaymentService.applyPaymentCompleted(event.eventId(), eventType, rentalId);
        } else {
            Object reason = payload.get("reason");
            rentalPaymentService.applyPaymentFailed(event.eventId(), eventType, rentalId,
                    reason == null ? null : reason.toString());
        }
    }
}

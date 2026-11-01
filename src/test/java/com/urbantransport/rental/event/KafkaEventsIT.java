package com.urbantransport.rental.event;

import com.urbantransport.rental.AbstractIntegrationTest;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KafkaEventsIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private JsonMapper jsonMapper;

    @Value("${app.kafka.topic.payment-events}")
    private String paymentTopic;

    @Value("${app.kafka.topic.trip-events}")
    private String tripTopic;

    @Value("${app.kafka.topic.rental-events}")
    private String rentalTopic;

    @Test
    void paymentCompleted_marksRentalPaidAndDuplicatedEventIsIgnored() throws Exception {
        RentalEntity rental = pendingPayment();
        UUID completedEventId = UUID.randomUUID();
        UUID failedEventId = UUID.randomUUID();

        send(paymentTopic, CUSTOMER_ID, envelope(completedEventId, EventTypes.PAYMENT_COMPLETED, CUSTOMER_ID,
                paymentResult("RENTAL", rental.getId(), null)));
        send(paymentTopic, CUSTOMER_ID, envelope(completedEventId, EventTypes.PAYMENT_COMPLETED, CUSTOMER_ID,
                paymentResult("RENTAL", rental.getId(), null)));
        send(paymentTopic, CUSTOMER_ID, envelope(failedEventId, EventTypes.PAYMENT_FAILED, CUSTOMER_ID,
                paymentResult("RENTAL", rental.getId(), "INSUFFICIENT_FUNDS")));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(1, count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", failedEventId)));
        RentalEntity saved = rentalRepository.findById(rental.getId()).orElseThrow();
        assertEquals(RentalStatus.PAID, saved.getStatus());
        assertNull(saved.getPaymentFailureReason());
        assertEquals(today(), saved.getPaymentDate());
        assertEquals(1, count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", completedEventId));
        assertEquals(List.of("RentalPaid"), outboxEventTypes(rental.getId()));
    }

    @Test
    void paymentFailed_keepsRentalQuotedWithReason() throws Exception {
        RentalEntity rental = pendingPayment();

        send(paymentTopic, CUSTOMER_ID, envelope(UUID.randomUUID(), EventTypes.PAYMENT_FAILED, CUSTOMER_ID,
                paymentResult("RENTAL", rental.getId(), "INSUFFICIENT_FUNDS")));

        await().atMost(TIMEOUT).untilAsserted(() -> assertEquals("INSUFFICIENT_FUNDS",
                rentalRepository.findById(rental.getId()).orElseThrow().getPaymentFailureReason()));
        RentalEntity saved = rentalRepository.findById(rental.getId()).orElseThrow();
        assertEquals(RentalStatus.QUOTED, saved.getStatus());
        assertNull(saved.getPaymentDate());
        assertNull(saved.getPaymentRequestedAt());
        assertTrue(outboxEventTypes(rental.getId()).isEmpty());
    }

    @Test
    void paymentOfOtherReference_isIgnoredWithoutMarkingIt() throws Exception {
        RentalEntity rental = pendingPayment();
        UUID orderEventId = UUID.randomUUID();
        UUID rentalEventId = UUID.randomUUID();

        send(paymentTopic, CUSTOMER_ID, envelope(orderEventId, EventTypes.PAYMENT_COMPLETED, CUSTOMER_ID,
                paymentResult("ORDER", rental.getId(), null)));
        send(paymentTopic, CUSTOMER_ID, envelope(rentalEventId, EventTypes.PAYMENT_FAILED, CUSTOMER_ID,
                paymentResult("RENTAL", rental.getId(), "CUSTOMER_WITHOUT_ACCOUNT")));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(1, count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", rentalEventId)));
        assertEquals(RentalStatus.QUOTED, rentalRepository.findById(rental.getId()).orElseThrow().getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", orderEventId));
    }

    @Test
    void privateTripRejected_returnsRentalToPaidWithReason() throws Exception {
        RentalEntity rental = saveRental(UUID.randomUUID(), RentalStatus.ASSIGNED, today().plusDays(5), null);
        rental.setBusId(UUID.randomUUID());
        rental.setDriverId(UUID.randomUUID());
        rentalRepository.save(rental);

        send(tripTopic, rental.getId(), envelope(UUID.randomUUID(), EventTypes.PRIVATE_TRIP_REJECTED, rental.getId(),
                Map.of("rentalId", rental.getId().toString(), "code", "RESOURCE_OVERLAP",
                        "reason", "El bus ya tiene un viaje en ese horario")));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(RentalStatus.PAID, rentalRepository.findById(rental.getId()).orElseThrow().getStatus()));
        RentalEntity saved = rentalRepository.findById(rental.getId()).orElseThrow();
        assertNull(saved.getBusId());
        assertNull(saved.getDriverId());
        assertEquals("El bus ya tiene un viaje en ese horario", saved.getAssignmentRejectionReason());
    }

    @Test
    void tripCompletedOfBothLegs_completesRoundTripRental() throws Exception {
        LocalDate departure = today().plusDays(5);
        RentalEntity rental = saveRental(UUID.randomUUID(), RentalStatus.ASSIGNED, departure, departure.plusDays(1));
        UUID regularTripId = UUID.randomUUID();
        UUID outboundTripId = UUID.randomUUID();

        send(tripTopic, regularTripId, envelope(UUID.randomUUID(), EventTypes.TRIP_COMPLETED, regularTripId,
                tripCompleted(regularTripId, "REGULAR", null)));
        send(tripTopic, outboundTripId, envelope(UUID.randomUUID(), EventTypes.TRIP_COMPLETED, outboundTripId,
                tripCompleted(outboundTripId, "PRIVATE", rental.getId())));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(1, rentalRepository.findById(rental.getId()).orElseThrow().getCompletedTrips()));
        assertEquals(RentalStatus.ASSIGNED, rentalRepository.findById(rental.getId()).orElseThrow().getStatus());

        UUID returnTripId = UUID.randomUUID();
        send(tripTopic, returnTripId, envelope(UUID.randomUUID(), EventTypes.TRIP_COMPLETED, returnTripId,
                tripCompleted(returnTripId, "PRIVATE", rental.getId())));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertEquals(RentalStatus.COMPLETED, rentalRepository.findById(rental.getId()).orElseThrow().getStatus()));
        assertEquals(2, rentalRepository.findById(rental.getId()).orElseThrow().getCompletedTrips());
        assertEquals(2, count("SELECT COUNT(*) FROM processed_events WHERE event_type = ?", EventTypes.TRIP_COMPLETED));
    }

    @Test
    void outboxEvent_isPublishedToRentalEventsWithRentalAsKey() throws Exception {
        UUID branchId = UUID.randomUUID();
        saveSettings(branchId);
        MvcResult result = mockMvc.perform(post("/rentals").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"branchId":"%s","originText":"Parque Central","destinationText":"Panajachel",
                                 "departureDate":"%s","passengers":30,"distanceKm":120}
                                """.formatted(branchId, today().plusDays(3))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rentalId = idAt(result, "$.id");

        ConsumerRecord<String, String> record = awaitRecord(rentalTopic, rentalId.toString());

        JsonNode envelope = jsonMapper.readTree(record.value());
        assertEquals(EventTypes.RENTAL_REQUESTED, envelope.get("eventType").asString());
        assertEquals(rentalId.toString(), envelope.get("aggregateId").asString());
        assertEquals("Parque Central", envelope.get("payload").get("originText").asString());
        assertEquals("REQUESTED", envelope.get("payload").get("status").asString());
        await().atMost(TIMEOUT).untilAsserted(() -> assertNotNull(jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_events WHERE aggregate_id = ?", Instant.class, rentalId)));
    }

    @Test
    void malformedPaymentEvent_endsInDeadLetterTopic() throws Exception {
        UUID key = UUID.randomUUID();

        send(paymentTopic, key, "{no es json");

        ConsumerRecord<String, String> record = awaitRecord(paymentTopic + ".DLT", key.toString());
        assertEquals("{no es json", record.value());
    }

    private RentalEntity pendingPayment() {
        RentalEntity rental = saveRental(UUID.randomUUID(), RentalStatus.QUOTED, today().plusDays(5), null);
        rental.setPaymentDate(today());
        rental.setCustomerId(CUSTOMER_ID);
        rental.setPaymentRequestedAt(Instant.now());
        return rentalRepository.save(rental);
    }

    private static Map<String, Object> paymentResult(String referenceType, UUID referenceId, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("referenceType", referenceType);
        payload.put("referenceId", referenceId.toString());
        payload.put("reason", reason);
        return payload;
    }

    private static Map<String, Object> tripCompleted(UUID tripId, String type, UUID rentalId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("type", type);
        payload.put("rentalId", rentalId == null ? null : rentalId.toString());
        payload.put("kmTraveled", 120.5);
        return payload;
    }

    private void send(String topic, UUID key, String value) throws Exception {
        kafkaTemplate.send(topic, key.toString(), value).get(10, TimeUnit.SECONDS);
    }

    private String envelope(UUID eventId, String eventType, UUID aggregateId, Map<String, Object> payload) {
        return jsonMapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "eventType", eventType,
                "occurredAt", Instant.now().toString(),
                "aggregateId", aggregateId.toString(),
                "version", 1,
                "correlationId", UUID.randomUUID().toString(),
                "payload", payload));
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("it-" + UUID.randomUUID(), "it")) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(60)).pollInSameThread().untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(received::add);
                assertTrue(received.stream().anyMatch(record -> key.equals(record.key())),
                        "no llegó el mensaje con key " + key + " a " + topic);
            });
        }
        return received.stream().filter(record -> key.equals(record.key())).findFirst().orElseThrow();
    }
}

package com.urbantransport.rental.service.impl;

import com.urbantransport.rental.config.RentalKafkaProperties;
import com.urbantransport.rental.domain.OutboxEventEntity;
import com.urbantransport.rental.event.EventTypes;
import com.urbantransport.rental.repository.OutboxEventRepository;
import com.urbantransport.rental.web.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxServiceImplTest {

    private static final UUID AGGREGATE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TOPIC = "rental-events";
    private static final String CORRELATION_ID = "corr-123";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Captor
    private ArgumentCaptor<OutboxEventEntity> outboxCaptor;

    private OutboxServiceImpl outboxService;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxServiceImpl(outboxEventRepository, jsonMapper,
                new RentalKafkaProperties(TOPIC, "payment-events", "trip-events"));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void record_savesEnvelopeWithEventDataAndRentalTopic() {
        MDC.put(CorrelationIdFilter.MDC_KEY, CORRELATION_ID);

        outboxService.record(EventTypes.AGGREGATE_RENTAL, AGGREGATE_ID, EventTypes.RENTAL_REQUESTED,
                Map.of("originText", "Parque Central"));

        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEventEntity saved = outboxCaptor.getValue();
        assertNotNull(saved.getId());
        assertEquals(EventTypes.AGGREGATE_RENTAL, saved.getAggregateType());
        assertEquals(AGGREGATE_ID, saved.getAggregateId());
        assertEquals(EventTypes.RENTAL_REQUESTED, saved.getEventType());
        assertEquals(TOPIC, saved.getTopic());
        assertNull(saved.getPublishedAt());
        assertEquals(0, saved.getAttempts());

        JsonNode envelope = jsonMapper.readTree(saved.getPayload());
        assertEquals(saved.getId().toString(), envelope.get("eventId").asString());
        assertEquals(EventTypes.RENTAL_REQUESTED, envelope.get("eventType").asString());
        assertEquals(AGGREGATE_ID.toString(), envelope.get("aggregateId").asString());
        assertEquals(1, envelope.get("version").asInt());
        assertEquals(CORRELATION_ID, envelope.get("correlationId").asString());
        assertFalse(envelope.get("occurredAt").isNull());
        assertEquals("Parque Central", envelope.get("payload").get("originText").asString());
    }

    @Test
    void record_withoutCorrelationInContext_generatesOne() {
        outboxService.record(EventTypes.AGGREGATE_RENTAL, AGGREGATE_ID, EventTypes.RENTAL_PAID, Map.of());

        verify(outboxEventRepository).save(outboxCaptor.capture());
        JsonNode envelope = jsonMapper.readTree(outboxCaptor.getValue().getPayload());
        String correlationId = envelope.get("correlationId").asString();
        assertDoesNotThrow(() -> UUID.fromString(correlationId));
    }
}

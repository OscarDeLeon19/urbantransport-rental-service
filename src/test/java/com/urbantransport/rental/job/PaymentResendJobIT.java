package com.urbantransport.rental.job;

import com.urbantransport.rental.AbstractIntegrationTest;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentResendJobIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentResendJob paymentResendJob;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void resend_republishesOnlyStaleQuotedPayments() {
        Instant beforeRun = Instant.now();
        RentalEntity stale = withPayment(RentalStatus.QUOTED, Instant.now().minus(Duration.ofMinutes(11)));
        RentalEntity legacy = withPayment(RentalStatus.QUOTED, null);
        RentalEntity recent = withPayment(RentalStatus.QUOTED, Instant.now().minus(Duration.ofMinutes(1)));
        RentalEntity paid = withPayment(RentalStatus.PAID, Instant.now().minus(Duration.ofMinutes(30)));
        RentalEntity withoutPayment = saveRental(UUID.randomUUID(), RentalStatus.QUOTED, today().plusDays(5), null);

        paymentResendJob.resendStalePayments();

        assertEquals(List.of("PaymentRequested"), outboxEventTypes(stale.getId()));
        assertEquals(List.of("PaymentRequested"), outboxEventTypes(legacy.getId()));
        assertTrue(outboxEventTypes(recent.getId()).isEmpty());
        assertTrue(outboxEventTypes(paid.getId()).isEmpty());
        assertTrue(outboxEventTypes(withoutPayment.getId()).isEmpty());
        assertTrue(rentalRepository.findById(stale.getId()).orElseThrow().getPaymentRequestedAt().isAfter(beforeRun));
        assertTrue(rentalRepository.findById(legacy.getId()).orElseThrow().getPaymentRequestedAt().isAfter(beforeRun));

        JsonNode payload = jsonMapper.readTree(outboxPayload(stale.getId(), "PaymentRequested")).get("payload");
        assertEquals("RENTAL", payload.get("referenceType").asString());
        assertEquals(stale.getId().toString(), payload.get("referenceId").asString());
        assertEquals(CUSTOMER_ID.toString(), payload.get("customerId").asString());
        assertEquals(0, CONFIRMED_PRICE.compareTo(payload.get("amount").decimalValue()));
    }

    @Test
    void resend_justAfterResending_doesNotRepeat() {
        RentalEntity stale = withPayment(RentalStatus.QUOTED, Instant.now().minus(Duration.ofMinutes(11)));

        paymentResendJob.resendStalePayments();
        paymentResendJob.resendStalePayments();

        assertEquals(List.of("PaymentRequested"), outboxEventTypes(stale.getId()));
    }

    private RentalEntity withPayment(RentalStatus status, Instant requestedAt) {
        RentalEntity rental = saveRental(UUID.randomUUID(), status, today().plusDays(5), null);
        rental.setPaymentDate(today());
        rental.setCustomerId(CUSTOMER_ID);
        rental.setPaymentRequestedAt(requestedAt);
        return rentalRepository.save(rental);
    }
}

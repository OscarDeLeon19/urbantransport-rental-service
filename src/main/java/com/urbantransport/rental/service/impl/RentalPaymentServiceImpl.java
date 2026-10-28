package com.urbantransport.rental.service.impl;

import com.urbantransport.rental.domain.ProcessedEventEntity;
import com.urbantransport.rental.domain.RentalEntity;
import com.urbantransport.rental.domain.RentalStatus;
import com.urbantransport.rental.event.EventTypes;
import com.urbantransport.rental.event.PaymentRequestedPayload;
import com.urbantransport.rental.event.RentalEventPayload;
import com.urbantransport.rental.repository.ProcessedEventRepository;
import com.urbantransport.rental.repository.RentalRepository;
import com.urbantransport.rental.service.OutboxService;
import com.urbantransport.rental.service.RentalPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RentalPaymentServiceImpl implements RentalPaymentService {

    private static final int MAX_FAILURE_REASON = 40;
    private static final String UNKNOWN_REASON = "UNKNOWN";

    private final RentalRepository rentalRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxService outboxService;

    @Override
    @Transactional
    public void applyPaymentCompleted(UUID eventId, String eventType, UUID rentalId) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }

        RentalEntity rental = lockRental(rentalId);
        if (rental.getStatus() == RentalStatus.QUOTED) {
            rental.setStatus(RentalStatus.PAID);
            rental.setPaymentFailureReason(null);
            outboxService.record(EventTypes.AGGREGATE_RENTAL, rental.getId(), EventTypes.RENTAL_PAID,
                    RentalEventPayload.fromEntity(rental));
        } else if (rental.getStatus() == RentalStatus.PAID) {
            log.debug("Alquiler {} ya pagado, pago duplicado ignorado", rentalId);
        } else {
            log.warn("Pago completado para el alquiler {} en estado {}; no se cambia su estado",
                    rentalId, rental.getStatus());
        }

        markProcessed(eventId, eventType);
    }

    @Override
    @Transactional
    public void applyPaymentFailed(UUID eventId, String eventType, UUID rentalId, String reason) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Evento {} ya procesado, se ignora", eventId);
            return;
        }

        RentalEntity rental = lockRental(rentalId);
        if (rental.getStatus() == RentalStatus.QUOTED) {
            rental.setPaymentFailureReason(failureReason(reason));
            rental.setPaymentDate(null);
            rental.setPaymentRequestedAt(null);
        } else {
            log.debug("Alquiler {} en estado {}, el fallo de pago se ignora", rentalId, rental.getStatus());
        }

        markProcessed(eventId, eventType);
    }

    @Override
    @Transactional
    public int resendStalePayments(Instant requestedBefore) {
        List<RentalEntity> stale = rentalRepository.findStalePaymentsForUpdate(RentalStatus.QUOTED, requestedBefore);
        for (RentalEntity rental : stale) {
            rental.setPaymentRequestedAt(Instant.now());
            outboxService.record(EventTypes.AGGREGATE_RENTAL, rental.getId(), EventTypes.PAYMENT_REQUESTED,
                    PaymentRequestedPayload.from(rental));
            log.warn("Cobro del alquiler {} sin respuesta de customer, se reenvía", rental.getId());
        }
        return stale.size();
    }

    private RentalEntity lockRental(UUID rentalId) {
        return rentalRepository.findByIdForUpdate(rentalId)
                .orElseThrow(() -> new IllegalStateException("Resultado de pago de un alquiler inexistente: " + rentalId));
    }

    private String failureReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return UNKNOWN_REASON;
        }
        return reason.length() <= MAX_FAILURE_REASON ? reason : reason.substring(0, MAX_FAILURE_REASON);
    }

    private void markProcessed(UUID eventId, String eventType) {
        processedEventRepository.save(ProcessedEventEntity.builder()
                .eventId(eventId)
                .eventType(eventType)
                .build());
    }
}

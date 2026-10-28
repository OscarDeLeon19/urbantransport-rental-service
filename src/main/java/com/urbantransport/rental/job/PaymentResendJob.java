package com.urbantransport.rental.job;

import com.urbantransport.rental.config.RentalProperties;
import com.urbantransport.rental.service.RentalPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResendJob {

    private final RentalPaymentService rentalPaymentService;
    private final RentalProperties rentalProperties;

    @Scheduled(fixedDelayString = "${app.jobs.payment-resend.fixed-delay-ms:60000}")
    public void resendStalePayments() {
        Instant requestedBefore = Instant.now().minus(rentalProperties.paymentResendAfter());
        int resent = rentalPaymentService.resendStalePayments(requestedBefore);
        if (resent > 0) {
            log.info("Se reenviaron {} cobros de alquiler sin respuesta", resent);
        }
    }
}

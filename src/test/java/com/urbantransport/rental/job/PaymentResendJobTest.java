package com.urbantransport.rental.job;

import com.urbantransport.rental.config.RentalProperties;
import com.urbantransport.rental.service.RentalPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentResendJobTest {

    private static final Duration RESEND_AFTER = Duration.ofMinutes(10);

    @Mock
    private RentalPaymentService rentalPaymentService;

    @Captor
    private ArgumentCaptor<Instant> requestedBeforeCaptor;

    private PaymentResendJob job;

    @BeforeEach
    void setUp() {
        job = new PaymentResendJob(rentalPaymentService, new RentalProperties(RESEND_AFTER));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void resend_usesConfiguredDelayAsCutoff(int resent) {
        when(rentalPaymentService.resendStalePayments(any(Instant.class))).thenReturn(resent);
        Instant before = Instant.now().minus(RESEND_AFTER);

        job.resendStalePayments();

        Instant after = Instant.now().minus(RESEND_AFTER);
        verify(rentalPaymentService).resendStalePayments(requestedBeforeCaptor.capture());
        Instant cutoff = requestedBeforeCaptor.getValue();
        assertFalse(cutoff.isBefore(before));
        assertFalse(cutoff.isAfter(after));
    }

    @Test
    void resend_cutoffIsInThePast() {
        when(rentalPaymentService.resendStalePayments(any(Instant.class))).thenReturn(0);

        job.resendStalePayments();

        verify(rentalPaymentService).resendStalePayments(requestedBeforeCaptor.capture());
        assertTrue(requestedBeforeCaptor.getValue().isBefore(Instant.now().minus(Duration.ofMinutes(9))));
    }
}

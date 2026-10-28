package com.urbantransport.rental.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerSummaryResponse(
        UUID id,
        String nit,
        String fullName,
        boolean generic,
        boolean hasAccount) {
}

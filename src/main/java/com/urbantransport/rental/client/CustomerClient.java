package com.urbantransport.rental.client;

public interface CustomerClient {

    CustomerSummaryResponse getByNit(String nit, String accessToken);
}

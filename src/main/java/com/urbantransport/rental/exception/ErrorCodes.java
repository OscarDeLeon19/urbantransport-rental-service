package com.urbantransport.rental.exception;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String BRANCH_REQUIRED = "BRANCH_REQUIRED";
    public static final String BRANCH_FORBIDDEN = "BRANCH_FORBIDDEN";
    public static final String CUSTOMER_NIT_REQUIRED = "CUSTOMER_NIT_REQUIRED";
    public static final String DEPARTURE_IN_PAST = "DEPARTURE_IN_PAST";
    public static final String RETURN_BEFORE_DEPARTURE = "RETURN_BEFORE_DEPARTURE";
    public static final String RENTAL_SETTINGS_NOT_FOUND = "RENTAL_SETTINGS_NOT_FOUND";
    public static final String INVALID_NIT = "INVALID_NIT";
    public static final String PAYMENT_DATE_IN_FUTURE = "PAYMENT_DATE_IN_FUTURE";
    public static final String CUSTOMER_NOT_FOUND = "CUSTOMER_NOT_FOUND";
    public static final String CUSTOMER_UNAVAILABLE = "CUSTOMER_UNAVAILABLE";
    public static final String CUSTOMER_ERROR = "CUSTOMER_ERROR";
    public static final String RENTAL_NOT_FOUND = "RENTAL_NOT_FOUND";
    public static final String RENTAL_NOT_IN_BRANCH = "RENTAL_NOT_IN_BRANCH";
    public static final String RENTAL_NOT_REQUESTED = "RENTAL_NOT_REQUESTED";
    public static final String CUSTOMER_ID_REQUIRED = "CUSTOMER_ID_REQUIRED";
    public static final String RENTAL_NOT_OWNED = "RENTAL_NOT_OWNED";
    public static final String RENTAL_NOT_QUOTED = "RENTAL_NOT_QUOTED";
    public static final String RENTAL_NOT_PAID = "RENTAL_NOT_PAID";
    public static final String SCHEDULE_DATE_MISMATCH = "SCHEDULE_DATE_MISMATCH";
    public static final String ARRIVAL_BEFORE_DEPARTURE = "ARRIVAL_BEFORE_DEPARTURE";
    public static final String RETURN_SCHEDULE_REQUIRED = "RETURN_SCHEDULE_REQUIRED";
    public static final String RETURN_SCHEDULE_NOT_ALLOWED = "RETURN_SCHEDULE_NOT_ALLOWED";
    public static final String RETURN_BEFORE_ARRIVAL = "RETURN_BEFORE_ARRIVAL";
    public static final String RENTAL_NOT_CANCELLABLE = "RENTAL_NOT_CANCELLABLE";
    public static final String PAYMENT_IN_PROGRESS = "PAYMENT_IN_PROGRESS";
    public static final String INVALID_DATE_RANGE = "INVALID_DATE_RANGE";
    public static final String RETURN_DATE_REQUIRED = "RETURN_DATE_REQUIRED";
    public static final String RETURN_DATE_NOT_ALLOWED = "RETURN_DATE_NOT_ALLOWED";
}

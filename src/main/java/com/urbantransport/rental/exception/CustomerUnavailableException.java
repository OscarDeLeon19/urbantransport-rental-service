package com.urbantransport.rental.exception;

public class CustomerUnavailableException extends RuntimeException {

    public CustomerUnavailableException(String message) {
        super(message);
    }

    public CustomerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

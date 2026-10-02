package com.example.shortener;

/** The submitted URL or alias breaks a validation rule. Maps to HTTP 400. */
public class InvalidLinkException extends RuntimeException {

    public InvalidLinkException(String message) {
        super(message);
    }
}

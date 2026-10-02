package com.example.shortener;

/** The requested custom alias is already in use. Maps to HTTP 409. */
public class AliasTakenException extends RuntimeException {

    public AliasTakenException(String alias) {
        super("alias '" + alias + "' is already taken");
    }
}

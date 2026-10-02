package com.example.shortener;

/** No link exists for the requested code. Maps to HTTP 404. */
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException(String code) {
        super("no link found for code '" + code + "'");
    }
}

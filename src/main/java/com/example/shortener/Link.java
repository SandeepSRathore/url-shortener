package com.example.shortener;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** A stored short link. {@code clicks} is mutable and thread-safe; everything else is fixed at creation. */
public record Link(String code, String url, Instant createdAt, AtomicLong clicks) {

    public static Link create(String code, String url, Instant createdAt) {
        return new Link(code, url, createdAt, new AtomicLong());
    }
}

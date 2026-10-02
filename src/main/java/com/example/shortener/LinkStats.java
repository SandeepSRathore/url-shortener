package com.example.shortener;

import java.time.Instant;

/** Immutable point-in-time view of a link, returned by the stats endpoint. */
public record LinkStats(String code, String url, long clicks, Instant createdAt) {

    static LinkStats of(Link link) {
        return new LinkStats(link.code(), link.url(), link.clicks().get(), link.createdAt());
    }
}

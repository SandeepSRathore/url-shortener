package com.example.shortener;

import java.time.Instant;

/** Response body of POST /api/links. */
public record LinkResponse(String code, String shortUrl, String url, Instant createdAt) {
}

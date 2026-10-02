package com.example.shortener;

/** Body of POST /api/links. {@code alias} may be absent or null to request a generated code. */
public record CreateLinkRequest(String url, String alias) {
}

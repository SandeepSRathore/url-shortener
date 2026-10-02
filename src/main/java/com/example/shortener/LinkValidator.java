package com.example.shortener;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure validation of URLs (spec 4.1) and aliases (spec 4.2). No Spring dependencies. */
public final class LinkValidator {

    static final int MAX_URL_LENGTH = 2048;

    private static final Pattern ALIAS_PATTERN = Pattern.compile("[A-Za-z0-9_-]{3,30}");
    private static final Set<String> RESERVED_ALIASES = Set.of("api", "error");

    private LinkValidator() {
    }

    public static void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidLinkException("url is required");
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw new InvalidLinkException("url must be at most " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidLinkException("url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new InvalidLinkException("url must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new InvalidLinkException("url must include a host");
        }
    }

    public static void validateAlias(String alias) {
        if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
            throw new InvalidLinkException("alias must be 3-30 characters from [A-Za-z0-9_-]");
        }
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidLinkException("alias '" + alias + "' is reserved");
        }
    }
}

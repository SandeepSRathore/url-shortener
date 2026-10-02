package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LinkValidatorTest {

    private static final String URL_PREFIX = "https://example.com/"; // 20 characters

    // ---- URL rules (spec 4.1) ----

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1#frag",
            "HTTPS://EXAMPLE.COM/upper",
            "http://localhost:8080/x",
            "https://sub.domain.example.org/a/b/c"
    })
    void acceptsValidHttpUrls(String url) {
        assertThatCode(() -> LinkValidator.validateUrl(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "R2: rejects [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "example.com",
            "ftp://example.com/file",
            "mailto:someone@example.com",
            "javascript:alert(1)",
            "http://",
            "https:///path-without-host",
            " https://example.com",
            "https://exa mple.com",
            "not a url at all"
    })
    @DisplayName("R2: missing, blank, non-http(s) or malformed URL is rejected")
    void rejectsInvalidUrls(String url) {
        assertThatThrownBy(() -> LinkValidator.validateUrl(url)).isInstanceOf(InvalidLinkException.class);
    }

    @Test
    @DisplayName("R2: URL of exactly 2048 characters is accepted")
    void acceptsUrlAtMaxLength() {
        String url = URL_PREFIX + "a".repeat(2048 - URL_PREFIX.length());

        assertThatCode(() -> LinkValidator.validateUrl(url)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R2: URL longer than 2048 characters is rejected")
    void rejectsUrlOverMaxLength() {
        String url = URL_PREFIX + "a".repeat(2049 - URL_PREFIX.length());

        assertThatThrownBy(() -> LinkValidator.validateUrl(url))
                .isInstanceOf(InvalidLinkException.class)
                .hasMessageContaining("2048");
    }

    // ---- Alias rules (spec 4.2) ----

    @ParameterizedTest
    @ValueSource(strings = {"abc", "my-link", "My_Link_2", "a-b_c", "API2", "errors", "abcdefghijklmnopqrstuvwxyz0123"}) // last one is exactly 30 chars
    void acceptsValidAliases(String alias) {
        assertThatCode(() -> LinkValidator.validateAlias(alias)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "R4: rejects [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {
            "ab",
            "has space",
            "dot.ted",
            "slash/ed",
            "emoji😀x",
            "new\nline",
            "ümlaut"
    })
    @DisplayName("R4: alias with bad length or bad characters is rejected")
    void rejectsMalformedAliases(String alias) {
        assertThatThrownBy(() -> LinkValidator.validateAlias(alias)).isInstanceOf(InvalidLinkException.class);
    }

    @Test
    @DisplayName("R4: alias longer than 30 characters is rejected")
    void rejectsAliasOverMaxLength() {
        assertThatThrownBy(() -> LinkValidator.validateAlias("x".repeat(31)))
                .isInstanceOf(InvalidLinkException.class);
    }

    @ParameterizedTest(name = "R4: reserved [{0}]")
    @ValueSource(strings = {"api", "API", "Api", "error", "ERROR", "Error"})
    @DisplayName("R4: reserved aliases are rejected in any case")
    void rejectsReservedAliases(String alias) {
        assertThatThrownBy(() -> LinkValidator.validateAlias(alias))
                .isInstanceOf(InvalidLinkException.class)
                .hasMessageContaining("reserved");
    }
}

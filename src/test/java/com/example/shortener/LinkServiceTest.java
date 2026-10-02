package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");
    private static final String URL = "https://example.com/some/long/path";

    private final InMemoryLinkRepository repository = new InMemoryLinkRepository();
    private Deque<String> codes;

    /** Service whose generator returns the given codes in order, and throws if asked for more. */
    private LinkService serviceGenerating(String... generatedCodes) {
        codes = new ArrayDeque<>(List.of(generatedCodes));
        return new LinkService(repository, codes::pop, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("R1: create without alias stores link under the generated code")
    void createWithoutAliasUsesGeneratedCode() {
        LinkService service = serviceGenerating("Ab3dE9x");

        Link link = service.create(URL, null);

        assertThat(link.code()).isEqualTo("Ab3dE9x");
        assertThat(link.url()).isEqualTo(URL);
        assertThat(link.createdAt()).isEqualTo(NOW);
        assertThat(link.clicks().get()).isZero();
        assertThat(repository.findByCode("Ab3dE9x")).containsSame(link);
    }

    @Test
    @DisplayName("R1: same URL shortened twice gives two different codes (no dedup)")
    void sameUrlTwiceGivesTwoLinks() {
        LinkService service = serviceGenerating("first01", "second2");

        Link first = service.create(URL, null);
        Link second = service.create(URL, null);

        assertThat(first.code()).isNotEqualTo(second.code());
    }

    @Test
    @DisplayName("R2: invalid URL is rejected before anything is stored")
    void invalidUrlRejected() {
        LinkService service = serviceGenerating("Ab3dE9x");

        assertThatThrownBy(() -> service.create("ftp://example.com", null))
                .isInstanceOf(InvalidLinkException.class);
        assertThat(repository.findByCode("Ab3dE9x")).isEmpty();
    }

    @Test
    @DisplayName("R3: create with valid alias uses the alias as the code")
    void createWithAliasUsesAlias() {
        LinkService service = serviceGenerating(); // generator must not be called

        Link link = service.create(URL, "my-link");

        assertThat(link.code()).isEqualTo("my-link");
        assertThat(repository.findByCode("my-link")).containsSame(link);
    }

    @Test
    @DisplayName("R4: invalid alias is rejected")
    void invalidAliasRejected() {
        LinkService service = serviceGenerating();

        assertThatThrownBy(() -> service.create(URL, "ab"))
                .isInstanceOf(InvalidLinkException.class);
    }

    @Test
    @DisplayName("R4: empty-string alias is invalid, not treated as absent")
    void emptyAliasRejected() {
        LinkService service = serviceGenerating("Ab3dE9x");

        assertThatThrownBy(() -> service.create(URL, ""))
                .isInstanceOf(InvalidLinkException.class);
        assertThat(codes).hasSize(1); // generator was not used
    }

    @Test
    @DisplayName("R5: alias already taken is rejected and the original link is unchanged")
    void takenAliasRejected() {
        LinkService service = serviceGenerating();
        Link original = service.create("https://first.example.com", "my-link");

        assertThatThrownBy(() -> service.create("https://second.example.com", "my-link"))
                .isInstanceOf(AliasTakenException.class)
                .hasMessageContaining("my-link");
        assertThat(repository.findByCode("my-link")).containsSame(original);
    }

    @Test
    @DisplayName("R11: generated code collision is retried with a new code")
    void generatedCollisionRetried() {
        repository.saveIfAbsent(Link.create("taken01", "https://a.example.com", NOW));
        repository.saveIfAbsent(Link.create("taken02", "https://b.example.com", NOW));
        LinkService service = serviceGenerating("taken01", "taken02", "fresh01");

        Link link = service.create(URL, null);

        assertThat(link.code()).isEqualTo("fresh01");
    }

    @Test
    @DisplayName("R11: five collisions in a row fail with CodeGenerationException")
    void fiveCollisionsFail() {
        repository.saveIfAbsent(Link.create("taken01", "https://a.example.com", NOW));
        LinkService service = serviceGenerating(
                "taken01", "taken01", "taken01", "taken01", "taken01", "unused1");

        assertThatThrownBy(() -> service.create(URL, null))
                .isInstanceOf(CodeGenerationException.class)
                .hasMessageContaining("5");
        assertThat(codes).containsExactly("unused1"); // exactly 5 attempts were made
    }
}

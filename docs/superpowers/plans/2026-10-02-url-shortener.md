# URL Shortener v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot REST API that shortens URLs, redirects short codes, supports custom aliases, and counts clicks, with every spec rule (R1–R11) proven by a named test.

**Architecture:** A thin `LinkController` delegates to `LinkService`, which owns every business rule. Validation lives in a pure `LinkValidator`. Storage is an in-memory `ConcurrentHashMap` behind a `LinkRepository` interface, so uniqueness (`putIfAbsent`) and click counting (`AtomicLong`) are atomic. `ApiExceptionHandler` maps domain exceptions to RFC 7807 `ProblemDetail` responses.

**Tech Stack:** Java 25, Maven 3.9, Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`), JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-02-url-shortener-design.md`

## Global Constraints

- Java 25; Spring Boot parent `4.1.1`; only dependencies `spring-boot-starter-webmvc` and `spring-boot-starter-webmvc-test` (test scope).
- All production code in package `com.example.shortener` (one flat package; the app is small).
- All tests in `src/test/java/com/example/shortener/`.
- Every test that proves a spec rule has `@DisplayName("Rn: ...")`, starting with the rule ID and a colon.
- URL: non-blank, ≤ 2048 chars, absolute URI, scheme `http`/`https` (case-insensitive), non-empty host. Stored exactly as submitted.
- Alias: 3–30 chars of `[A-Za-z0-9_-]`, not a reserved word (`api`, `error`, compared case-insensitively). Case-sensitive for uniqueness. `""` is invalid; only missing/`null` means "generate".
- Generated code: 7 random chars from `[0-9A-Za-z]`, at most 5 attempts, then `500`.
- Errors are `ProblemDetail` JSON (`application/problem+json`) with `type`, `title`, `status`, `detail`.
- `createdAt` is ISO-8601 UTC (e.g. `2026-10-02T10:00:00Z`).
- No URL dedup, no expiry, no persistence, no auth.

## Review Focus

1. **Malformed or missing JSON body on `POST /api/links`** → expected `400` ProblemDetail, not `500`. Pinned in Task 6.
2. **Alias that is reserved in a different case (`API`, `Error`)** → expected `400`. Pinned in Task 2.
3. **URLs that look plausible but aren't usable** (`http://`, `https:///path`, `javascript:alert(1)`, `ftp://x.com`, leading space) → expected `400`. Pinned in Task 2.
4. **Exact length boundaries** (URL of 2048 vs 2049 chars; alias of 3/30 vs 2/31 chars) → the limit itself is accepted, one past it is rejected. Pinned in Task 2.
5. **`"alias": null` sent explicitly in JSON, and `GET /api` with no code** → `null` behaves as "generate a code" (`201`), and `/api` is a normal `404` ProblemDetail. Pinned in Tasks 6 and 7.

Known limitation (accepted for v1, not a bug): `java.net.URI` returns no host for hostnames with underscores or non-ASCII characters, so such URLs are rejected with `400`.

---

## File Structure

```
pom.xml
README.md
src/main/java/com/example/shortener/
  ShortenerApplication.java     Boot entry point + Clock bean
  Link.java                     record: code, url, createdAt, clicks (AtomicLong)
  LinkStats.java                record: immutable stats snapshot
  LinkRepository.java           storage interface
  InMemoryLinkRepository.java   ConcurrentHashMap implementation
  LinkValidator.java            pure URL + alias rules
  CodeGenerator.java            functional interface
  RandomCodeGenerator.java      SecureRandom base62, 7 chars
  LinkService.java              create / resolve / stats
  InvalidLinkException.java     → 400
  AliasTakenException.java      → 409
  LinkNotFoundException.java    → 404
  CodeGenerationException.java  → 500
  CreateLinkRequest.java        request DTO
  LinkResponse.java             create response DTO
  LinkController.java           HTTP mapping
  ApiExceptionHandler.java      exceptions → ProblemDetail
src/test/java/com/example/shortener/
  InMemoryLinkRepositoryTest.java
  LinkValidatorTest.java
  RandomCodeGeneratorTest.java
  LinkServiceTest.java
  LinkApiTest.java
```

---

### Task 1: Project scaffold, `Link` model, and in-memory repository (R9, R10)

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/example/shortener/ShortenerApplication.java`
- Create: `src/main/java/com/example/shortener/Link.java`
- Create: `src/main/java/com/example/shortener/LinkRepository.java`
- Create: `src/main/java/com/example/shortener/InMemoryLinkRepository.java`
- Test: `src/test/java/com/example/shortener/InMemoryLinkRepositoryTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `record Link(String code, String url, Instant createdAt, AtomicLong clicks)` with `static Link create(String code, String url, Instant createdAt)` (clicks start at 0)
  - `interface LinkRepository { boolean saveIfAbsent(Link link); Optional<Link> findByCode(String code); boolean incrementClicks(String code); }`
  - `@Repository class InMemoryLinkRepository implements LinkRepository` (public no-arg constructor)
  - `ShortenerApplication` declares `@Bean Clock clock()` returning `Clock.systemUTC()`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>shortener</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>shortener</name>
    <description>URL shortener — spec-driven development learning project</description>

    <properties>
        <java.version>25</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create `ShortenerApplication.java`**

```java
package com.example.shortener;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortenerApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 3: Write the failing repository tests**

`src/test/java/com/example/shortener/InMemoryLinkRepositoryTest.java`:

```java
package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryLinkRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private final InMemoryLinkRepository repository = new InMemoryLinkRepository();

    @Test
    void savedLinkCanBeFoundByCode() {
        Link link = Link.create("abc1234", "https://example.com", NOW);

        assertThat(repository.saveIfAbsent(link)).isTrue();
        assertThat(repository.findByCode("abc1234")).containsSame(link);
    }

    @Test
    void findByUnknownCodeIsEmpty() {
        assertThat(repository.findByCode("missing")).isEmpty();
    }

    @Test
    void saveIfAbsentRejectsExistingCodeAndKeepsOriginal() {
        Link original = Link.create("taken", "https://first.example.com", NOW);
        repository.saveIfAbsent(original);

        boolean saved = repository.saveIfAbsent(Link.create("taken", "https://second.example.com", NOW));

        assertThat(saved).isFalse();
        assertThat(repository.findByCode("taken")).containsSame(original);
    }

    @Test
    void codesAreCaseSensitive() {
        assertThat(repository.saveIfAbsent(Link.create("my-link", "https://a.example.com", NOW))).isTrue();
        assertThat(repository.saveIfAbsent(Link.create("My-Link", "https://b.example.com", NOW))).isTrue();
    }

    @Test
    void incrementClicksOnUnknownCodeReturnsFalse() {
        assertThat(repository.incrementClicks("missing")).isFalse();
    }

    @Test
    void newLinkStartsWithZeroClicks() {
        assertThat(Link.create("abc1234", "https://example.com", NOW).clicks().get()).isZero();
    }

    @Test
    @DisplayName("R9: concurrent saves of the same code - exactly one succeeds")
    void concurrentSavesOfSameCodeExactlyOneWins() throws Exception {
        AtomicInteger counter = new AtomicInteger();

        List<Boolean> results = runConcurrently(50, () -> repository.saveIfAbsent(
                Link.create("same-alias", "https://example.com/" + counter.incrementAndGet(), NOW)));

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
    }

    @Test
    @DisplayName("R10: N concurrent click increments - clicks equals N")
    void concurrentIncrementsAreAllCounted() throws Exception {
        repository.saveIfAbsent(Link.create("popular", "https://example.com", NOW));

        List<Boolean> results = runConcurrently(100, () -> repository.incrementClicks("popular"));

        assertThat(results).containsOnly(true);
        assertThat(repository.findByCode("popular").orElseThrow().clicks().get()).isEqualTo(100);
    }

    /** Starts all tasks at the same instant (via a latch) to maximise contention. */
    private static List<Boolean> runConcurrently(int threads, Callable<Boolean> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=InMemoryLinkRepositoryTest`
Expected: COMPILATION ERROR — `cannot find symbol: class InMemoryLinkRepository` / `class Link`.

- [ ] **Step 5: Create `Link.java`**

```java
package com.example.shortener;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** A stored short link. {@code clicks} is mutable and thread-safe; everything else is fixed at creation. */
public record Link(String code, String url, Instant createdAt, AtomicLong clicks) {

    public static Link create(String code, String url, Instant createdAt) {
        return new Link(code, url, createdAt, new AtomicLong());
    }
}
```

- [ ] **Step 6: Create `LinkRepository.java`**

```java
package com.example.shortener;

import java.util.Optional;

public interface LinkRepository {

    /** Atomically stores the link if its code is unused. Returns false if the code already exists. */
    boolean saveIfAbsent(Link link);

    Optional<Link> findByCode(String code);

    /** Atomically adds one click. Returns false if the code is unknown. */
    boolean incrementClicks(String code);
}
```

- [ ] **Step 7: Create `InMemoryLinkRepository.java`**

```java
package com.example.shortener;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

@Repository
public class InMemoryLinkRepository implements LinkRepository {

    private final ConcurrentMap<String, Link> links = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(Link link) {
        return links.putIfAbsent(link.code(), link) == null;
    }

    @Override
    public Optional<Link> findByCode(String code) {
        return Optional.ofNullable(links.get(code));
    }

    @Override
    public boolean incrementClicks(String code) {
        Link link = links.get(code);
        if (link == null) {
            return false;
        }
        link.clicks().incrementAndGet();
        return true;
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=InMemoryLinkRepositoryTest`
Expected: BUILD SUCCESS, 8 tests pass.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/java/com/example/shortener/ShortenerApplication.java \
  src/main/java/com/example/shortener/Link.java \
  src/main/java/com/example/shortener/LinkRepository.java \
  src/main/java/com/example/shortener/InMemoryLinkRepository.java \
  src/test/java/com/example/shortener/InMemoryLinkRepositoryTest.java
git commit -m "feat: scaffold project with atomic in-memory link repository (R9, R10)"
```

---

### Task 2: `LinkValidator` — URL and alias rules (R2, R4)

**Files:**
- Create: `src/main/java/com/example/shortener/InvalidLinkException.java`
- Create: `src/main/java/com/example/shortener/LinkValidator.java`
- Test: `src/test/java/com/example/shortener/LinkValidatorTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `class InvalidLinkException extends RuntimeException` with constructor `InvalidLinkException(String message)`
  - `final class LinkValidator` with `static void validateUrl(String url)` and `static void validateAlias(String alias)`. Both throw `InvalidLinkException` on failure and return normally on success.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/example/shortener/LinkValidatorTest.java`:

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=LinkValidatorTest`
Expected: COMPILATION ERROR — `cannot find symbol: class LinkValidator` / `class InvalidLinkException`.

- [ ] **Step 3: Create `InvalidLinkException.java`**

```java
package com.example.shortener;

/** The submitted URL or alias breaks a validation rule. Maps to HTTP 400. */
public class InvalidLinkException extends RuntimeException {

    public InvalidLinkException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Create `LinkValidator.java`**

```java
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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=LinkValidatorTest`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/shortener/InvalidLinkException.java \
  src/main/java/com/example/shortener/LinkValidator.java \
  src/test/java/com/example/shortener/LinkValidatorTest.java
git commit -m "feat: add URL and alias validation rules (R2, R4)"
```

---

### Task 3: `CodeGenerator` and `RandomCodeGenerator` (R1 code format)

**Files:**
- Create: `src/main/java/com/example/shortener/CodeGenerator.java`
- Create: `src/main/java/com/example/shortener/RandomCodeGenerator.java`
- Test: `src/test/java/com/example/shortener/RandomCodeGeneratorTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `@FunctionalInterface interface CodeGenerator { String generate(); }`. Being functional lets tests pass a lambda or a method reference.
  - `@Component class RandomCodeGenerator implements CodeGenerator` (public no-arg constructor)

- [ ] **Step 1: Write the failing test**

`src/test/java/com/example/shortener/RandomCodeGeneratorTest.java`:

```java
package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RandomCodeGeneratorTest {

    private final RandomCodeGenerator generator = new RandomCodeGenerator();

    @Test
    @DisplayName("R1: generated codes are 7 base62 characters")
    void generatesSevenCharBase62Codes() {
        for (int i = 0; i < 1000; i++) {
            assertThat(generator.generate()).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    void generatesDifferentCodesAcrossCalls() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(generator.generate());
        }
        // 1000 draws from 62^7 values: a repeat has probability ~1e-7.
        assertThat(codes).hasSize(1000);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q test -Dtest=RandomCodeGeneratorTest`
Expected: COMPILATION ERROR — `cannot find symbol: class RandomCodeGenerator`.

- [ ] **Step 3: Create `CodeGenerator.java`**

```java
package com.example.shortener;

/** Produces candidate short codes. Callers must handle collisions; generators don't check uniqueness. */
@FunctionalInterface
public interface CodeGenerator {

    String generate();
}
```

- [ ] **Step 4: Create `RandomCodeGenerator.java`**

```java
package com.example.shortener;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

@Component
public class RandomCodeGenerator implements CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final int LENGTH = 7;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -q test -Dtest=RandomCodeGeneratorTest`
Expected: BUILD SUCCESS, 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/shortener/CodeGenerator.java \
  src/main/java/com/example/shortener/RandomCodeGenerator.java \
  src/test/java/com/example/shortener/RandomCodeGeneratorTest.java
git commit -m "feat: add random base62 code generator (R1)"
```

---

### Task 4: `LinkService.create` — generated codes, aliases, collisions (R1, R2, R3, R4, R5, R11)

**Files:**
- Create: `src/main/java/com/example/shortener/AliasTakenException.java`
- Create: `src/main/java/com/example/shortener/CodeGenerationException.java`
- Create: `src/main/java/com/example/shortener/LinkService.java`
- Test: `src/test/java/com/example/shortener/LinkServiceTest.java`

**Interfaces:**
- Consumes: `Link.create(String, String, Instant)`, `LinkRepository.saveIfAbsent(Link)`, `InMemoryLinkRepository()`, `LinkValidator.validateUrl(String)`, `LinkValidator.validateAlias(String)`, `InvalidLinkException`, `CodeGenerator.generate()`
- Produces:
  - `@Service class LinkService` with constructor `LinkService(LinkRepository repository, CodeGenerator codeGenerator, Clock clock)` and `Link create(String url, String alias)`, where `alias == null` means "generate a code"
  - `class AliasTakenException extends RuntimeException`, constructor `AliasTakenException(String alias)`
  - `class CodeGenerationException extends RuntimeException`, constructor `CodeGenerationException(int attempts)`
  - `static final int MAX_GENERATION_ATTEMPTS = 5` on `LinkService`

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/example/shortener/LinkServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=LinkServiceTest`
Expected: COMPILATION ERROR — `cannot find symbol: class LinkService` / `AliasTakenException` / `CodeGenerationException`.

- [ ] **Step 3: Create `AliasTakenException.java`**

```java
package com.example.shortener;

/** The requested custom alias is already in use. Maps to HTTP 409. */
public class AliasTakenException extends RuntimeException {

    public AliasTakenException(String alias) {
        super("alias '" + alias + "' is already taken");
    }
}
```

- [ ] **Step 4: Create `CodeGenerationException.java`**

```java
package com.example.shortener;

/** Every attempt to generate an unused code collided. Maps to HTTP 500. */
public class CodeGenerationException extends RuntimeException {

    public CodeGenerationException(int attempts) {
        super("could not generate a unique code after " + attempts + " attempts");
    }
}
```

- [ ] **Step 5: Create `LinkService.java`**

```java
package com.example.shortener;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

@Service
public class LinkService {

    static final int MAX_GENERATION_ATTEMPTS = 5;

    private final LinkRepository repository;
    private final CodeGenerator codeGenerator;
    private final Clock clock;

    public LinkService(LinkRepository repository, CodeGenerator codeGenerator, Clock clock) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    /** Creates a link. A null alias means "generate a code"; any other value must be a valid alias. */
    public Link create(String url, String alias) {
        LinkValidator.validateUrl(url);
        Instant now = clock.instant();

        if (alias != null) {
            LinkValidator.validateAlias(alias);
            Link link = Link.create(alias, url, now);
            if (!repository.saveIfAbsent(link)) {
                throw new AliasTakenException(alias);
            }
            return link;
        }

        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            Link link = Link.create(codeGenerator.generate(), url, now);
            if (repository.saveIfAbsent(link)) {
                return link;
            }
        }
        throw new CodeGenerationException(MAX_GENERATION_ATTEMPTS);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=LinkServiceTest`
Expected: BUILD SUCCESS, 9 tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/shortener/AliasTakenException.java \
  src/main/java/com/example/shortener/CodeGenerationException.java \
  src/main/java/com/example/shortener/LinkService.java \
  src/test/java/com/example/shortener/LinkServiceTest.java
git commit -m "feat: add LinkService.create with alias and collision-retry rules (R1-R5, R11)"
```

---

### Task 5: `LinkService.resolve` and `LinkService.stats` (R6, R7, R8)

**Files:**
- Create: `src/main/java/com/example/shortener/LinkNotFoundException.java`
- Create: `src/main/java/com/example/shortener/LinkStats.java`
- Modify: `src/main/java/com/example/shortener/LinkService.java` (add two public methods and one private helper)
- Modify: `src/test/java/com/example/shortener/LinkServiceTest.java` (append tests)

**Interfaces:**
- Consumes: `LinkService` from Task 4; `LinkRepository.findByCode(String)`, `LinkRepository.incrementClicks(String)`
- Produces:
  - `String LinkService.resolve(String code)` returns the target URL and records one click. Throws `LinkNotFoundException` for an unknown code.
  - `LinkStats LinkService.stats(String code)` returns a snapshot and doesn't count a click. Throws `LinkNotFoundException` for an unknown code.
  - `record LinkStats(String code, String url, long clicks, Instant createdAt)`
  - `class LinkNotFoundException extends RuntimeException`, constructor `LinkNotFoundException(String code)`

- [ ] **Step 1: Write the failing tests**

Append these test methods inside the `LinkServiceTest` class, after the last existing test:

```java
    @Test
    @DisplayName("R6: resolve returns the original URL and counts one click")
    void resolveReturnsUrlAndCountsClick() {
        LinkService service = serviceGenerating();
        service.create(URL, "my-link");

        String target = service.resolve("my-link");

        assertThat(target).isEqualTo(URL);
        assertThat(service.stats("my-link").clicks()).isEqualTo(1);
    }

    @Test
    @DisplayName("R6: each resolve adds exactly one click")
    void eachResolveAddsOneClick() {
        LinkService service = serviceGenerating();
        service.create(URL, "my-link");

        service.resolve("my-link");
        service.resolve("my-link");
        service.resolve("my-link");

        assertThat(service.stats("my-link").clicks()).isEqualTo(3);
    }

    @Test
    @DisplayName("R7: resolve of unknown code throws LinkNotFoundException")
    void resolveUnknownCodeThrows() {
        LinkService service = serviceGenerating();

        assertThatThrownBy(() -> service.resolve("missing"))
                .isInstanceOf(LinkNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("R7: stats of unknown code throws LinkNotFoundException")
    void statsUnknownCodeThrows() {
        LinkService service = serviceGenerating();

        assertThatThrownBy(() -> service.stats("missing"))
                .isInstanceOf(LinkNotFoundException.class);
    }

    @Test
    @DisplayName("R8: stats returns code, url, clicks and createdAt")
    void statsReturnsAllFields() {
        LinkService service = serviceGenerating();
        service.create(URL, "my-link");

        LinkStats stats = service.stats("my-link");

        assertThat(stats).isEqualTo(new LinkStats("my-link", URL, 0, NOW));
    }

    @Test
    @DisplayName("R8: reading stats does not add a click")
    void statsDoesNotCountAsClick() {
        LinkService service = serviceGenerating();
        service.create(URL, "my-link");

        service.stats("my-link");
        service.stats("my-link");

        assertThat(service.stats("my-link").clicks()).isZero();
    }

    @Test
    void statsIsASnapshotNotALiveView() {
        LinkService service = serviceGenerating();
        service.create(URL, "my-link");
        LinkStats before = service.stats("my-link");

        service.resolve("my-link");

        assertThat(before.clicks()).isZero();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=LinkServiceTest`
Expected: COMPILATION ERROR — `cannot find symbol: method resolve(String)` / `class LinkStats` / `class LinkNotFoundException`.

- [ ] **Step 3: Create `LinkNotFoundException.java`**

```java
package com.example.shortener;

/** No link exists for the requested code. Maps to HTTP 404. */
public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException(String code) {
        super("no link found for code '" + code + "'");
    }
}
```

- [ ] **Step 4: Create `LinkStats.java`**

```java
package com.example.shortener;

import java.time.Instant;

/** Immutable point-in-time view of a link, returned by the stats endpoint. */
public record LinkStats(String code, String url, long clicks, Instant createdAt) {

    static LinkStats of(Link link) {
        return new LinkStats(link.code(), link.url(), link.clicks().get(), link.createdAt());
    }
}
```

- [ ] **Step 5: Add `resolve`, `stats` and `find` to `LinkService`**

Insert these methods after `create(...)`, inside the class, in `src/main/java/com/example/shortener/LinkService.java`:

```java
    /** Returns the target URL for a code and records one click. */
    public String resolve(String code) {
        Link link = find(code);
        repository.incrementClicks(code);
        return link.url();
    }

    /** Returns a snapshot of a link's stats. Does not record a click. */
    public LinkStats stats(String code) {
        return LinkStats.of(find(code));
    }

    private Link find(String code) {
        return repository.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=LinkServiceTest`
Expected: BUILD SUCCESS, 16 tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/shortener/LinkNotFoundException.java \
  src/main/java/com/example/shortener/LinkStats.java \
  src/main/java/com/example/shortener/LinkService.java \
  src/test/java/com/example/shortener/LinkServiceTest.java
git commit -m "feat: add LinkService resolve and stats (R6-R8)"
```

---

### Task 6: `POST /api/links` endpoint and `ApiExceptionHandler` (R1–R5 over HTTP)

**Files:**
- Create: `src/main/java/com/example/shortener/CreateLinkRequest.java`
- Create: `src/main/java/com/example/shortener/LinkResponse.java`
- Create: `src/main/java/com/example/shortener/LinkController.java`
- Create: `src/main/java/com/example/shortener/ApiExceptionHandler.java`
- Test: `src/test/java/com/example/shortener/LinkApiTest.java`

**Interfaces:**
- Consumes: `LinkService.create(String, String)`, `Link`, and the exceptions `InvalidLinkException`, `AliasTakenException`, `LinkNotFoundException`, `CodeGenerationException`
- Produces:
  - `record CreateLinkRequest(String url, String alias)`
  - `record LinkResponse(String code, String shortUrl, String url, Instant createdAt)`
  - `@RestController class LinkController` with `POST /api/links`. Task 7 adds the GET endpoints to this class.
  - `@RestControllerAdvice class ApiExceptionHandler extends ResponseEntityExceptionHandler`, which maps exceptions to responses:

    | Exception | Status | Title |
    |-----------|--------|-------|
    | `InvalidLinkException` | 400 | `"Invalid link"` |
    | `AliasTakenException` | 409 | `"Alias already taken"` |
    | `LinkNotFoundException` | 404 | `"Link not found"` |
    | `CodeGenerationException` | 500 | `"Code generation failed"` |

    Because the class extends `ResponseEntityExceptionHandler`, malformed JSON also gets a 400 ProblemDetail.

Notes for the implementer:
- In Boot 4, `@AutoConfigureMockMvc` is in `org.springframework.boot.webmvc.test.autoconfigure`.
- MockMvc requests come from `http://localhost`, so `shortUrl` is `http://localhost/{code}` in tests.
- The Spring context, and so the repository, is shared across tests in the class. Every test therefore uses its own unique alias.

- [ ] **Step 1: Write the failing API tests**

`src/test/java/com/example/shortener/LinkApiTest.java`:

```java
package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class LinkApiTest {

    private static final String URL = "https://example.com/some/long/path";

    @Autowired
    private MockMvc mvc;

    // ---- POST /api/links ----

    @Test
    @DisplayName("R1: POST with valid URL returns 201 with a generated code")
    void createWithGeneratedCode() throws Exception {
        String body = createLink("{\"url\":\"" + URL + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.createdAt").isString())
                .andReturn().getResponse().getContentAsString();

        String code = JsonPath.read(body, "$.code");
        assertThat(JsonPath.<String>read(body, "$.shortUrl")).isEqualTo("http://localhost/" + code);
    }

    @Test
    @DisplayName("R1: POST sets Location header to the stats URL")
    void createSetsLocationHeader() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"loc-test\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/links/loc-test"));
    }

    @Test
    @DisplayName("R1: explicit null alias behaves like a missing alias")
    void explicitNullAliasGeneratesCode() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":null}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("[0-9A-Za-z]{7}")));
    }

    @Test
    @DisplayName("R2: POST with invalid URL returns 400 ProblemDetail")
    void createWithInvalidUrl() throws Exception {
        createLink("{\"url\":\"ftp://example.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid link"))
                .andExpect(jsonPath("$.detail").value("url must use http or https"));
    }

    @Test
    @DisplayName("R2: POST without url returns 400")
    void createWithMissingUrl() throws Exception {
        createLink("{\"alias\":\"no-url-here\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid link"));
    }

    @Test
    @DisplayName("R2: malformed JSON body returns 400 ProblemDetail, not 500")
    void createWithMalformedJson() throws Exception {
        createLink("{not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("R2: missing request body returns 400 ProblemDetail")
    void createWithNoBody() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("R3: POST with valid alias returns 201 and code equals alias")
    void createWithAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"my-alias\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("my-alias"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost/my-alias"));
    }

    @Test
    @DisplayName("R4: POST with invalid alias returns 400")
    void createWithInvalidAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"ab\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid link"));
    }

    @Test
    @DisplayName("R4: POST with empty-string alias returns 400")
    void createWithEmptyAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R4: POST with reserved alias returns 400")
    void createWithReservedAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"Error\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("R5: POST with taken alias returns 409 ProblemDetail")
    void createWithTakenAlias() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"dup-alias\"}")
                .andExpect(status().isCreated());

        createLink("{\"url\":\"https://other.example.com\",\"alias\":\"dup-alias\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Alias already taken"));
    }

    private ResultActions createLink(String json) throws Exception {
        return mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=LinkApiTest`
Expected: FAIL with status-code mismatches. With no handler mapped yet, `POST /api/links` returns 404 or 405 instead of the expected 201, 400 or 409.

- [ ] **Step 3: Create `CreateLinkRequest.java`**

```java
package com.example.shortener;

/** Body of POST /api/links. {@code alias} may be absent or null to request a generated code. */
public record CreateLinkRequest(String url, String alias) {
}
```

- [ ] **Step 4: Create `LinkResponse.java`**

```java
package com.example.shortener;

import java.time.Instant;

/** Response body of POST /api/links. */
public record LinkResponse(String code, String shortUrl, String url, Instant createdAt) {
}
```

- [ ] **Step 5: Create `LinkController.java`**

```java
package com.example.shortener;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping("/api/links")
    public ResponseEntity<LinkResponse> create(@RequestBody CreateLinkRequest request) {
        Link link = linkService.create(request.url(), request.alias());

        String shortUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/{code}").buildAndExpand(link.code()).toUriString();
        URI location = UriComponentsBuilder.fromPath("/api/links/{code}")
                .buildAndExpand(link.code()).toUri();

        return ResponseEntity.created(location)
                .body(new LinkResponse(link.code(), shortUrl, link.url(), link.createdAt()));
    }
}
```

- [ ] **Step 6: Create `ApiExceptionHandler.java`**

```java
package com.example.shortener;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain exceptions to RFC 7807 ProblemDetail responses. Extending
 * ResponseEntityExceptionHandler also turns Spring MVC errors (e.g. malformed JSON) into ProblemDetail.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidLinkException.class)
    ProblemDetail handleInvalidLink(InvalidLinkException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid link", e);
    }

    @ExceptionHandler(AliasTakenException.class)
    ProblemDetail handleAliasTaken(AliasTakenException e) {
        return problem(HttpStatus.CONFLICT, "Alias already taken", e);
    }

    @ExceptionHandler(LinkNotFoundException.class)
    ProblemDetail handleLinkNotFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Link not found", e);
    }

    @ExceptionHandler(CodeGenerationException.class)
    ProblemDetail handleCodeGeneration(CodeGenerationException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Code generation failed", e);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(title);
        return problem;
    }
}
```

- [ ] **Step 7: Run the API tests to verify they pass**

Run: `mvn -q test -Dtest=LinkApiTest`
Expected: BUILD SUCCESS, 12 tests pass.

- [ ] **Step 8: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, with every test from Tasks 1–6 passing.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/shortener/CreateLinkRequest.java \
  src/main/java/com/example/shortener/LinkResponse.java \
  src/main/java/com/example/shortener/LinkController.java \
  src/main/java/com/example/shortener/ApiExceptionHandler.java \
  src/test/java/com/example/shortener/LinkApiTest.java
git commit -m "feat: add POST /api/links with ProblemDetail error handling (R1-R5)"
```

---

### Task 7: `GET /{code}` redirect and `GET /api/links/{code}` stats endpoints (R6–R8 over HTTP)

**Files:**
- Modify: `src/main/java/com/example/shortener/LinkController.java` (add two endpoints and their imports)
- Modify: `src/test/java/com/example/shortener/LinkApiTest.java` (add imports and tests)

**Interfaces:**
- Consumes: `LinkService.resolve(String) → String`, `LinkService.stats(String) → LinkStats`, `ApiExceptionHandler` (404 mapping) from Task 6
- Produces: `GET /{code}` → `302` with a `Location` header; `GET /api/links/{code}` → `200` with a `LinkStats` JSON body

- [ ] **Step 1: Write the failing API tests**

In `LinkApiTest.java`, add this static import next to the existing `post` import:

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
```

Then add these tests inside the class, just above the `private ResultActions createLink` helper:

```java
    // ---- GET /{code} ----

    @Test
    @DisplayName("R6: GET /{code} redirects 302 to the original URL and counts a click")
    void redirectCountsClick() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"go-there\"}").andExpect(status().isCreated());

        mvc.perform(get("/go-there"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", URL));

        mvc.perform(get("/api/links/go-there"))
                .andExpect(jsonPath("$.clicks").value(1));
    }

    @Test
    @DisplayName("R7: GET /{code} with unknown code returns 404 ProblemDetail")
    void redirectUnknownCode() throws Exception {
        mvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Link not found"));
    }

    @Test
    @DisplayName("R7: GET /api (no code) is an ordinary 404, not a server error")
    void bareApiPathIsNotFound() throws Exception {
        mvc.perform(get("/api"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- GET /api/links/{code} ----

    @Test
    @DisplayName("R7: GET stats with unknown code returns 404 ProblemDetail")
    void statsUnknownCode() throws Exception {
        mvc.perform(get("/api/links/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("R8: GET stats returns code, url, clicks and ISO-8601 createdAt")
    void statsReturnsAllFields() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"stats-me\"}").andExpect(status().isCreated());

        mvc.perform(get("/api/links/stats-me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("stats-me"))
                .andExpect(jsonPath("$.url").value(URL))
                .andExpect(jsonPath("$.clicks").value(0))
                .andExpect(jsonPath("$.createdAt").value(
                        matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")));
    }

    @Test
    @DisplayName("R8: reading stats does not add a click")
    void statsDoesNotCountAsClick() throws Exception {
        createLink("{\"url\":\"" + URL + "\",\"alias\":\"just-look\"}").andExpect(status().isCreated());

        mvc.perform(get("/api/links/just-look"));
        mvc.perform(get("/api/links/just-look"));

        mvc.perform(get("/api/links/just-look"))
                .andExpect(jsonPath("$.clicks").value(0));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q test -Dtest=LinkApiTest`
Expected: FAIL. With no handlers for these GET paths yet, `GET /go-there` and `GET /api/links/stats-me` return 404 instead of 302 or 200, and `redirectCountsClick` and `statsReturnsAllFields` fail.

- [ ] **Step 3: Add the endpoints to `LinkController`**

Add these imports to `LinkController.java`:

```java
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
```

Add these methods inside the class, after `create(...)`:

```java
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String url = linkService.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    @GetMapping("/api/links/{code}")
    public LinkStats stats(@PathVariable String code) {
        return linkService.stats(code);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q test -Dtest=LinkApiTest`
Expected: BUILD SUCCESS, 18 tests pass.

- [ ] **Step 5: Run the full suite**

Run: `mvn -q test`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/shortener/LinkController.java \
  src/test/java/com/example/shortener/LinkApiTest.java
git commit -m "feat: add redirect and stats endpoints (R6-R8)"
```

---

### Task 8: README curl walkthrough and Definition-of-Done check

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: the running application from Tasks 1–7
- Produces: documentation only

- [ ] **Step 1: Verify traceability — every rule R1–R11 has a named test**

Run:
```bash
for r in R1 R2 R3 R4 R5 R6 R7 R8 R9 R10 R11; do
  grep -rqE "@DisplayName\(\"$r:" src/test || echo "MISSING $r"
done; echo "traceability check done"
```
Expected: only `traceability check done` is printed, with no `MISSING` lines. If any rule is missing, add a test for it in the task that owns that rule before continuing.

- [ ] **Step 2: Start the app**

Run (in background): `mvn -q spring-boot:run`
Expected: the log shows `Tomcat started on port 8080` and `Started ShortenerApplication`.

- [ ] **Step 3: Run the curl walkthrough and confirm each expected status**

```bash
# 1. Generated code → 201
curl -s -i -X POST localhost:8080/api/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/some/long/path"}'

# 2. Custom alias → 201
curl -s -i -X POST localhost:8080/api/links -H 'Content-Type: application/json' \
  -d '{"url":"https://spring.io","alias":"spring"}'

# 3. Same alias again → 409
curl -s -i -X POST localhost:8080/api/links -H 'Content-Type: application/json' \
  -d '{"url":"https://other.example.com","alias":"spring"}'

# 4. Redirect → 302 Location: https://spring.io
curl -s -i localhost:8080/spring

# 5. Stats → 200, clicks: 1
curl -s localhost:8080/api/links/spring

# 6. Unknown code → 404
curl -s -i localhost:8080/nope-nope
```
Expected statuses, in order: `201`, `201`, `409`, `302`, `200` (with `"clicks":1`), `404`.

- [ ] **Step 4: Stop the app**

Stop the background `mvn spring-boot:run` process.

- [ ] **Step 5: Create `README.md`**

````markdown
# URL Shortener

A small Spring Boot REST API for practising **spec-driven development**.

- Spec: [`docs/superpowers/specs/2026-10-02-url-shortener-design.md`](docs/superpowers/specs/2026-10-02-url-shortener-design.md)
- Plan: [`docs/superpowers/plans/2026-10-02-url-shortener.md`](docs/superpowers/plans/2026-10-02-url-shortener.md)

Every rule in the spec has an ID (R1–R11), and every test proving a rule has an `@DisplayName` starting with that ID.

## Requirements

Java 25, Maven 3.9+.

## Run

```bash
mvn spring-boot:run      # starts on http://localhost:8080
mvn test                 # runs all tests
```

## API

| Method | Path | Result |
|--------|------|--------|
| `POST` | `/api/links` | `201` create (`{"url": "...", "alias": "optional"}`) · `400` invalid · `409` alias taken |
| `GET` | `/{code}` | `302` redirect (counts a click) · `404` unknown |
| `GET` | `/api/links/{code}` | `200` stats `{code, url, clicks, createdAt}` · `404` unknown |

Errors are RFC 7807 `application/problem+json`.

## Walkthrough

```bash
# Create with a generated code → 201
curl -s -X POST localhost:8080/api/links -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/some/long/path"}'

# Create with a custom alias → 201
curl -s -X POST localhost:8080/api/links -H 'Content-Type: application/json' \
  -d '{"url":"https://spring.io","alias":"spring"}'

# Same alias again → 409 Conflict
curl -s -i -X POST localhost:8080/api/links -H 'Content-Type: application/json' \
  -d '{"url":"https://other.example.com","alias":"spring"}'

# Follow the short link → 302 to https://spring.io
curl -s -i localhost:8080/spring

# Stats → {"code":"spring","url":"https://spring.io","clicks":1,"createdAt":"..."}
curl -s localhost:8080/api/links/spring
```

## Rules at a glance

- URL: `http`/`https` only, must have a host, ≤ 2048 characters.
- Alias: 3–30 characters of `[A-Za-z0-9_-]`, case-sensitive; `api` and `error` are reserved.
- Generated codes: 7 random base62 characters.
- Data is in memory and is lost on restart.
````

- [ ] **Step 6: Commit**

```bash
git add README.md
git commit -m "docs: add README with API summary and curl walkthrough"
```

---

## Spec Coverage Map

| Spec section | Rule(s) | Task(s) |
|---|---|---|
| 3.1 POST /api/links | R1–R5 | 4, 6 |
| 3.2 GET /{code} | R6, R7 | 5, 7 |
| 3.3 GET /api/links/{code} | R7, R8 | 5, 7 |
| 4.1 URL validation | R2 | 2 |
| 4.2 Alias validation | R4 | 2 |
| 4.3 Code generation | R1, R11 | 3, 4 |
| 4.4 Uniqueness and concurrency | R5, R9, R10 | 1, 4 |
| 4.5 Duplicates | — | 4 |
| 6 Architecture | — | 1–7 |
| 8 Definition of Done | all | 8 |

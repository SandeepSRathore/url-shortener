# URL Shortener — Design Spec (v1)

- **Date:** 2026-10-02
- **Status:** Draft — awaiting review
- **Purpose:** Learning project for spec-driven development (SDD). The domain is deliberately small so attention stays on the spec → plan → test → code loop.

## 1. Goals and Non-Goals

### Goals
- A REST API that shortens URLs, redirects short codes, supports custom aliases, and counts clicks.
- Every rule in this spec has a stable ID (R1…R11) and at least one automated test that names it.

### Non-Goals (v1)
- No UI (API only; driven via curl/tests).
- No authentication, users, or rate limiting.
- No persistence across restarts (in-memory store).
- No link expiry, no URL deduplication, no deletion/editing of links.
- Single instance only; no distributed concerns.

## 2. Tech Stack

- Java 25, Maven 3.9
- Spring Boot 4.1.1 (latest stable as of 2026-10-02)
- Dependencies: `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test` only (Boot 4 names for the web and test starters)
- Base package: `com.example.shortener`

## 3. API Contract

All error responses use Spring's RFC 7807 `ProblemDetail` JSON (`type`, `title`, `status`, `detail`).

### 3.1 `POST /api/links` — create a short link

Request body (JSON):

| Field   | Type   | Required | Notes                         |
|---------|--------|----------|-------------------------------|
| `url`   | string | yes      | Target URL                    |
| `alias` | string | no       | Custom code; generated if absent |

Success: `201 Created`, header `Location: /api/links/{code}`, body:

```json
{
  "code": "my-link",
  "shortUrl": "http://localhost:8080/my-link",
  "url": "https://example.com/some/long/path",
  "createdAt": "2026-10-02T10:00:00Z"
}
```

- `shortUrl` is built from the incoming request's scheme, host and port plus `/{code}`.
- `createdAt` is ISO-8601 UTC.

Errors:
- `400` — `url` missing, blank, not `http`/`https`, not a parseable absolute URL with a host, or longer than 2048 characters.
- `400` — `alias` present but invalid (see 4.2).
- `409` — `alias` already in use.

### 3.2 `GET /{code}` — redirect

- `302 Found` with `Location: <original url>`; the link's click count increases by exactly 1.
- `404` — no link with that code.

### 3.3 `GET /api/links/{code}` — stats

- `200 OK`, body: `{ "code", "url", "clicks", "createdAt" }`.
- Reading stats does **not** change the click count.
- `404` — no link with that code.

## 4. Business Rules

### 4.1 URL validation
- Must be non-blank, at most 2048 characters.
- Must parse as an absolute URI with scheme `http` or `https` (case-insensitive) and a non-empty host.
- The URL is stored exactly as submitted (no normalization).

### 4.2 Alias validation
- Length 3–30 characters inclusive.
- Characters limited to `[A-Za-z0-9_-]`.
- Must not be a reserved word. Reserved (compared case-insensitively): `api`, `error`. (`/error` is Spring Boot's built-in error path, so a link with that alias could never redirect.)
- Aliases are case-sensitive for uniqueness and lookup: `My-Link` and `my-link` are distinct.
- An empty-string alias (`""`) is treated as invalid (`400`), not as "absent". Only a missing or `null` alias means "generate one".

### 4.3 Code generation
- Generated codes are 7 characters from base62 (`[0-9A-Za-z]`), chosen randomly.
- If a generated code collides with an existing code, generate a new one; at most 5 attempts in total.
- If all 5 attempts collide, respond `500` (ProblemDetail). Practically unreachable (62⁷ ≈ 3.5 × 10¹² codes) but defined.
- Generated codes are never checked against the reserved-word list. A 7-character code can never equal `api` or `error`.

### 4.4 Uniqueness and concurrency
- Code uniqueness is enforced atomically by the repository (`putIfAbsent`). There is no separate check-then-insert.
- Under concurrent creation with the same alias, exactly one request succeeds (`201`); all others get `409`.
- Click counting is atomic: N concurrent redirects to one code increase its count by exactly N.

### 4.5 Duplicates
- Shortening the same URL twice produces two independent links with different codes (no dedup in v1).

## 5. Rule Catalogue (traceability)

Every test's `@DisplayName` starts with the rule ID it proves.

| ID  | Rule |
|-----|------|
| R1  | `POST` with a valid http/https URL and no alias → `201` with a generated 7-char base62 code |
| R2  | Missing, blank, non-http(s), malformed, or >2048-char URL → `400` |
| R3  | Valid custom alias → `201` and `code == alias` |
| R4  | Alias with bad length, bad characters, empty string, or reserved word → `400` |
| R5  | Alias already taken → `409` |
| R6  | `GET /{code}` → `302` to the original URL, clicks + 1 |
| R7  | Unknown code on redirect or stats → `404` |
| R8  | `GET /api/links/{code}` returns code, url, clicks, createdAt; reading stats does not add a click |
| R9  | Concurrent creates with the same alias → exactly one `201` |
| R10 | N concurrent redirects → clicks == N |
| R11 | Generated-code collision → retry, max 5 attempts, then `500` |

## 6. Architecture

```
LinkController ──► LinkService ──► LinkRepository (interface)
  (HTTP only)       (all rules)        └─ InMemoryLinkRepository (ConcurrentHashMap)
                        │
                        ├─► LinkValidator   (URL + alias rules)
                        ├─► CodeGenerator   (interface) └─ RandomCodeGenerator
                        └─► Clock           (injected)
ApiExceptionHandler — maps domain exceptions → ProblemDetail
```

### Components

| Unit | Responsibility | Depends on |
|------|----------------|------------|
| `LinkController` | HTTP mapping, request/response DTOs, building `shortUrl`. No business rules. | `LinkService` |
| `LinkService` | `create(url, alias)`, `resolve(code)` (counts a click), `stats(code)`. Owns retry logic. | `LinkRepository`, `LinkValidator`, `CodeGenerator`, `Clock` |
| `LinkValidator` | Pure validation of URL (4.1) and alias (4.2). No Spring dependencies. | — |
| `CodeGenerator` / `RandomCodeGenerator` | Produces candidate codes (4.3). Interface so tests can force collisions. | `SecureRandom` |
| `LinkRepository` / `InMemoryLinkRepository` | `saveIfAbsent(Link) → boolean`, `findByCode(code) → Optional<Link>`, `incrementClicks(code) → boolean` (false if code is unknown). | `ConcurrentHashMap` |
| `ApiExceptionHandler` | `InvalidLinkException` → 400, `LinkNotFoundException` → 404, `AliasTakenException` → 409, `CodeGenerationException` → 500. | — |

### Data model

`Link` — `code: String`, `url: String`, `createdAt: Instant`, `clicks: AtomicLong`. Stats responses expose a snapshot (`clicks.get()`).

### Data flow

- **Create:** controller → `service.create` → validate URL (and alias, if present) → alias given: `saveIfAbsent`, false → `AliasTakenException`. No alias: loop up to 5 times generating a code and calling `saveIfAbsent`; still failing → `CodeGenerationException`.
- **Redirect:** controller → `service.resolve(code)` → `findByCode` (absent → `LinkNotFoundException`) → `incrementClicks` → return url → controller responds `302`.
- **Stats:** controller → `service.stats(code)` → `findByCode` (absent → `LinkNotFoundException`) → snapshot DTO.

## 7. Testing Strategy

- **Workflow:** TDD. Each implementation task is "write a failing test for rule Rx, make it pass, commit".
- **Unit tests (no Spring):**
  - `LinkValidator`: parameterized edge cases for R2, R4.
  - `LinkService`: fake `CodeGenerator`, fixed `Clock`, covers R1, R3, R5, R11.
  - `InMemoryLinkRepository`: real thread contention via `ExecutorService` + `CountDownLatch` for R9, R10.
- **API tests:** `@SpringBootTest` + MockMvc for R1–R8: status codes, JSON shape, `Location` headers, ProblemDetail bodies.

## 8. Definition of Done

- Every rule R1–R11 has at least one passing test whose `@DisplayName` starts with its ID.
- `mvn test` passes cleanly.
- README contains a curl walkthrough (create generated, create with alias, redirect, stats, a 409 case) that works against `mvn spring-boot:run`.

## 9. Future Specs (out of scope here)

- v2: link expiry (`410 Gone`), URL deduplication.
- Persistence: swap `InMemoryLinkRepository` for a JPA implementation behind the same interface.
- UI: Angular front end as a separate spec.

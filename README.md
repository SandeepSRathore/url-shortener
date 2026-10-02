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

Port 8080 busy? `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081`

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

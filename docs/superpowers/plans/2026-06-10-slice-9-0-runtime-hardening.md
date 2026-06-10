# Slice 9.0 Runtime Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Operator-grade runtime hardening: validated typed configuration, request rate limiting, explicit security headers, an authenticated metrics endpoint, and a deny-by-default route fallback.

**Architecture:** A single `@ConfigurationProperties("ehr")` + `@Validated` class (`EhrProperties`) owns the security-critical settings — dev JWT secret (non-blank, ≥32 bytes, replacing the ad-hoc check), export storage dir, and rate limit — so misconfiguration fails at startup, not at first request. A servlet-level fixed-window rate limiter (per client IP, configurable requests/minute, default 1000, injectable clock for tests) returns `429` + `Retry-After` on `/api` and `/fhir` paths; no new dependencies. `SecurityConfiguration` gains explicit headers (CSP `default-src 'none'` — this is an API, not a site — and `Referrer-Policy: no-referrer`, on top of Spring's nosniff/frame-deny defaults), exposes actuator `metrics` behind authentication while `health` stays public, and replaces the permissive `anyRequest().permitAll()` with explicit permits (`/error`, discovery, stubs, health, metadata) and `anyRequest().denyAll()`. Redacted-logging posture is verified (no payload logging exists; Boot's `server.error.include-message` default already strips exception messages from error bodies) and documented in 9.1's threat model.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/runtime/EhrProperties.kt`, `RateLimitFilter.kt`.
- Modify `SecurityConfiguration.kt` (properties, headers, actuator + deny-all rules), `ExportJobProcessor.kt` (use properties), `application.properties` (expose metrics).
- Create `src/test/kotlin/dev/ehr/runtime/RateLimitFilterTest.kt`, `HardeningIntegrationTest.kt`.

## Acceptance Criteria

- Startup fails on a blank or short (<32 byte) `ehr.security.dev-jwt-secret`; export dir and rate limit are validated and typed.
- Rate limiter: requests beyond the per-minute limit on `/api`/`/fhir` paths get `429` with `Retry-After`; other paths and other client keys are unaffected; the window resets (unit-tested with an injected clock).
- Responses carry `Content-Security-Policy: default-src 'none'`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`.
- `/actuator/health` is public; `/actuator/metrics` requires authentication and works for an authenticated member; unknown routes are denied, not silently permitted.
- All prior behavior unchanged otherwise; full suite green.

## Intentional Deferrals

- No distributed/replicated rate limiting (single-node in-memory; horizontal scale needs shared state), no per-principal or per-route limits, no HSTS config (TLS termination is deployment-specific), no Prometheus registry wiring, no startup config printing.

## Tasks

- [ ] Failing tests: rate-limit unit matrix; hardening integration (headers, metrics auth, health public, deny-all fallback).
- [ ] Implement properties, filter, security changes; grep main code for payload logging (must remain none).
- [ ] Focused + full suites; commit plan as `docs: add Slice 9.0 runtime hardening plan`; implementation as `feat: add runtime hardening with rate limiting and validated config`.

## Self-Review Checklist

- Misconfiguration is a startup failure, not a runtime surprise.
- The rate limiter cannot block health checks or discovery.
- No route is reachable that is not explicitly intended to be.

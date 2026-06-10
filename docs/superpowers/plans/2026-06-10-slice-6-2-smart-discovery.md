# Slice 6.2 SMART Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve an honest `.well-known/smart-configuration` discovery document plus the explicit token/authorize integration-point stubs, completing the Slice 6 deliverables and exit criteria.

**Architecture:** A public controller renders the SMART configuration with request-derived absolute endpoint URLs. Because no authorization server exists yet (dev JWTs are the Slice 1 decision), `authorization_endpoint` and `token_endpoint` point at explicit stubs (`/oauth/authorize`, `/oauth/token`) that return `501` with a machine-readable error — the design spec's "token introspection stub or integration point", not a fake flow. The document advertises only what is true: `client_credentials` as the future grant, the `user`/`system` scope families the policy evaluator honors (v1 and v2 forms), and capabilities `client-confidential-symmetric`, `permission-user`, `permission-v1`, `permission-v2`. `SecurityConfiguration` permits `/.well-known/**` and `/oauth/**` anonymously. No policy change.

**Standards Notes:** SMART App Launch 2.x discovery served from `/.well-known/smart-configuration`. Patient-context capabilities, launch contexts, and OIDC (`sso-openid-connect`) are deliberately absent from `capabilities` because they are unsupported — the exit criterion is accuracy, not breadth.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/oauth/SmartConfigurationController.kt` (discovery + stubs).
- Modify `SecurityConfiguration.kt`: permit `/.well-known/**` and `/oauth/**`.
- Create `src/test/kotlin/dev/ehr/oauth/SmartConfigurationIntegrationTest.kt`.

## Acceptance Criteria

- `GET /.well-known/smart-configuration` is public and returns: absolute `authorization_endpoint`/`token_endpoint`, `grant_types_supported: ["client_credentials"]`, `scopes_supported` covering the honored `user|system` v1/v2 families plus `openid`/`fhirUser`, and `capabilities: ["client-confidential-symmetric","permission-user","permission-v1","permission-v2"]`.
- `POST /oauth/token` and `GET /oauth/authorize` are public and return `501` with `{"error":"unsupported_grant_type"|"unsupported"}`-style JSON naming them integration points.
- Protected routes remain protected (`/api/v1/**`, `/fhir/r4/**` still 401 unauthenticated).
- Full suite green.

## Intentional Deferrals

- No real authorization server, token issuance, introspection, JWKS, or OIDC discovery; no patient-launch capabilities; no per-client scope policies.

## Tasks

- [ ] Failing tests (public discovery shape, stub behavior, protected routes unchanged).
- [ ] Implement controller + security config change.
- [ ] Focused + full suites; commit plan as `docs: add Slice 6.2 SMART discovery plan`; implementation as `feat: add SMART discovery document and authorization stubs`.

## Self-Review Checklist

- The discovery document contains nothing the server does not actually support.
- Stubs fail loudly and honestly; nothing pretends to issue tokens.
- Public surface is limited to discovery + stubs; clinical routes remain authenticated.

# Slice 2.2 FHIR Patient Read And Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first FHIR R4 boundary: `GET /fhir/r4/Patient/{id}` and `GET /fhir/r4/Patient?identifier=system|value`, mapped from the internal patient model, returning `application/fhir+json` with `OperationOutcome` errors, gated and audited through the same policy/audit path as the internal API.

**Architecture:** Slice 2.2 completes the Slice 2 exit criteria from the design spec. HAPI FHIR is used strictly as a standards library (parsing/serialization of `org.hl7.fhir.r4.model` types); the internal model stays the source of truth and persistence is untouched. The FHIR controller reuses `PatientService`, so policy decisions, tenant scoping, and audit rows are identical for both API families — the FHIR layer only changes the wire format. A shared `FhirContext` bean is created once (it is expensive); parsers are created per request (they are cheap and not thread-safe).

**Standards Notes:** FHIR R4.0.1 read/search semantics: unknown or cross-tenant resource IDs return `404` with an `OperationOutcome`; searches return a `searchset` Bundle with `total` and `entry.search.mode = match`; errors use `OperationOutcome` issues (`forbidden`, `not-found`, `invalid`). The `identifier` search parameter is a token accepted only in full `system|value` form in this slice. US Core profile conformance assertions and `CapabilityStatement` remain Slice 7 work.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, HAPI FHIR `hapi-fhir-structures-r4` 8.2.0, Spring Security OAuth2 Resource Server, Spring MVC, PostgreSQL 16, Testcontainers, MockMvc, JUnit 5.

---

## File Structure

- Modify `build.gradle.kts`: add `ca.uhn.hapi.fhir:hapi-fhir-structures-r4:8.2.0`.
- Create `src/main/kotlin/dev/ehr/fhir/FhirConfiguration.kt`: shared R4 `FhirContext` bean.
- Create `src/main/kotlin/dev/ehr/fhir/PatientFhirMapper.kt`: internal patient + identifiers to FHIR R4 `Patient`.
- Create `src/main/kotlin/dev/ehr/fhir/PatientFhirController.kt`: FHIR read/search endpoints, Bundle assembly, `OperationOutcome` error mapping.
- Create `src/test/kotlin/dev/ehr/fhir/PatientFhirMapperTest.kt`: pure mapping unit tests.
- Create `src/test/kotlin/dev/ehr/fhir/PatientFhirApiIntegrationTest.kt`: endpoint, tenancy, audit, and error-shape coverage.

## Acceptance Criteria

- `GET /fhir/r4/Patient/{id}` returns `200` with `application/fhir+json` and a FHIR `Patient` carrying:
  - `id` = internal patient UUID;
  - `meta.versionId` = internal version, `meta.lastUpdated` = `updated_at`;
  - `active` = true only for `ACTIVE` status;
  - `name[0].family`/`given[0]`;
  - `birthDate` and `gender` when present;
  - `identifier[]` with `system`, `value`, `use`, `period`, and `assigner.display` when present.
- `GET /fhir/r4/Patient/{id}` returns `404` + `OperationOutcome` (`not-found`) for missing, cross-organization, or non-UUID IDs.
- `GET /fhir/r4/Patient?identifier=system|value` returns a `searchset` Bundle with `total`, `entry.fullUrl`, `entry.search.mode = match`; empty Bundle (`total` 0) for no match or cross-tenant identifiers.
- Missing or malformed `identifier` parameter returns `400` + `OperationOutcome` (`invalid`).
- Denied access (wrong role/scope) returns `403` + `OperationOutcome` (`forbidden`) and audits `AUTHORIZATION_DENIED`.
- FHIR reads/searches produce the same audit rows as the internal API (policy version, reason code, patient/resource IDs) because they flow through `PatientService`.
- Unauthenticated `/fhir/r4/**` requests return `401` with no audit row.
- `OperationOutcome` diagnostics contain no demographics or identifier values.
- Existing internal API behavior and all prior tests remain green.

## Intentional Deferrals

- No FHIR write/update/delete interactions.
- No `CapabilityStatement` / `metadata` endpoint (Slice 7).
- No US Core profile declarations or validator integration (Slice 7).
- No search parameters beyond `identifier`, no `value`-only or `|value` token forms, no `_count`/paging.
- No entered-in-error filtering semantics; status maps to `active` only.
- No identifier `type` CodeableConcept mapping from terminology (needs concept loading; future slice).
- No FHIR `AuditEvent`/`Provenance` resources (Slice 4+).
- No SMART scopes beyond the existing scope strings, no launch context.
- No XML (`application/fhir+xml`) support.
- No other FHIR resources.

## Task 1: Write Failing Mapper Tests

- [ ] Add `PatientFhirMapperTest` covering full demographic mapping, status-to-active mapping for all three statuses, gender mapping for all four values plus null, and identifier mapping including use, period, and assigner display.
- [ ] Run targeted tests and confirm failures are due to the missing mapper.

## Task 2: Write Failing FHIR API Tests

- [ ] Add `PatientFhirApiIntegrationTest` covering: unauthenticated `401` without audit; clinician read `200` with FHIR JSON body and `READ` audit; cross-organization read `404` `OperationOutcome` with `READ`+`FAILURE` audit; non-UUID ID `404` `OperationOutcome`; org-admin read `403` `OperationOutcome` with denied audit; identifier search hit Bundle with `SEARCH` audit; cross-tenant identifier search returning `total` 0; missing/malformed identifier `400` `OperationOutcome`.
- [ ] Run targeted tests and confirm failures are due to missing endpoints.

## Task 3: Implement FHIR Boundary

- [ ] Add the HAPI FHIR dependency.
- [ ] Add `FhirConfiguration` with a singleton R4 `FhirContext`.
- [ ] Implement `PatientFhirMapper`.
- [ ] Implement `PatientFhirController` reusing `PatientService`, translating `ResponseStatusException` into `OperationOutcome` responses and assembling search Bundles.

## Task 4: Verify And Commit

- [ ] Run focused FHIR, patient, and security tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Search for deferred CapabilityStatement, US Core, validator, FHIR write, paging, SMART, or other-resource work.
- [ ] Search FHIR code for demographic or identifier values in error diagnostics or logs.
- [ ] Commit plan artifact as `docs: add Slice 2.2 FHIR Patient read/search plan`.
- [ ] Commit implementation as `feat: add FHIR Patient read and search boundary`.

## Self-Review Checklist

- FHIR endpoints flow through the same policy and audit spine as internal endpoints; there is no FHIR-only bypass.
- Cross-tenant data is unreachable through FHIR read or search.
- The internal model remains the source of truth; HAPI types appear only at the boundary.
- Error responses are FHIR `OperationOutcome`s without clinical payload leakage.
- Searches are audited with the matched patient ID, never the queried identifier value.
- Deferred conformance work (CapabilityStatement, US Core, Inferno) is documented, not half-implemented.

# Slice 3.2 FHIR Encounter Read And Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the FHIR R4 Encounter boundary: `GET /fhir/r4/Encounter/{id}` and the first patient-compartment search `GET /fhir/r4/Encounter?patient=...`, mapped from the internal encounter model with coded class, `OperationOutcome` errors, and the same policy/audit path as the internal API.

**Architecture:** Slice 3.2 mirrors the FHIR Patient boundary over `EncounterService`, so FHIR reads/searches produce identical policy decisions and audit rows to `/api/v1`. The mapper resolves the encounter's `class_concept_id` through the terminology repository and emits the primary coding as the FHIR `Encounter.class` Coding — terminology references leave the system as real codes, never bare display strings. Shared FHIR HTTP plumbing (content type, `OperationOutcome` rendering, error translation) is extracted from the Patient FHIR controller into a small reusable component instead of being copied.

**Standards Notes:** FHIR R4 `Encounter.status` maps one-to-one onto the internal status subset. `Encounter.class` is a required v3-ActCode Coding in R4. The `patient` search parameter accepts both a bare logical id and a `Patient/{id}` reference, per common FHIR server behavior. A search naming a patient that is not visible in the caller's tenant returns `404` `OperationOutcome` — identical to internal-API semantics, and revealing nothing about other tenants. Bundle results are newest-first to match the internal timeline.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, HAPI FHIR structures-r4 8.2.0, Spring MVC, PostgreSQL 16, Testcontainers, MockMvc, JUnit 5.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/fhir/FhirResponseSupport.kt`: shared FHIR JSON response + `OperationOutcome` factory extracted from the Patient controller.
- Modify `src/main/kotlin/dev/ehr/fhir/PatientFhirController.kt`: use the shared support.
- Create `src/main/kotlin/dev/ehr/fhir/EncounterFhirMapper.kt`: internal encounter + class concept to FHIR `Encounter`.
- Create `src/main/kotlin/dev/ehr/fhir/EncounterFhirController.kt`: FHIR read/search endpoints.
- Create `src/test/kotlin/dev/ehr/fhir/EncounterFhirMapperTest.kt`: mapping unit tests.
- Create `src/test/kotlin/dev/ehr/fhir/EncounterFhirApiIntegrationTest.kt`: endpoint, tenancy, audit, and error-shape coverage.

## Acceptance Criteria

- `GET /fhir/r4/Encounter/{id}` returns `200` `application/fhir+json` with:
  - `id`, `meta.versionId`, `meta.lastUpdated`;
  - `status` mapped one-to-one (`planned`, `in-progress`, `finished`, `cancelled`, `entered-in-error`);
  - `class` carrying the primary coding (system/code/display) of the internal class concept;
  - `subject.reference` = `Patient/{patientId}`;
  - `period.start`/`period.end`.
- `GET /fhir/r4/Encounter/{id}` returns `404` + `OperationOutcome` (`not-found`) for missing, cross-tenant, or non-UUID IDs.
- `GET /fhir/r4/Encounter?patient={id}` (bare UUID or `Patient/{id}` form) returns a newest-first `searchset` Bundle with `total`, `entry.fullUrl`, `entry.search.mode = match`.
- Search for a patient not visible in the tenant returns `404` + `OperationOutcome`; missing/malformed `patient` parameter returns `400` + `OperationOutcome` (`invalid`).
- Denied access returns `403` + `OperationOutcome` (`forbidden`) with an `AUTHORIZATION_DENIED` audit row.
- FHIR reads/searches produce the same audit rows as the internal API (operation `READ`/`SEARCH`, patient/encounter IDs, `policy-spine-v3`).
- Unauthenticated requests return `401` with no audit row.
- `OperationOutcome` diagnostics contain no clinical payloads or concept displays.
- Patient FHIR endpoints behave exactly as before after the shared-support refactor.

## Intentional Deferrals

- No FHIR Encounter write.
- No additional search parameters (`date`, `status`, `_count`, paging) or `value`-less token forms beyond the two `patient` forms.
- No `CapabilityStatement`, US Core profiles, or validator integration (Slice 7).
- No participant/location/serviceType/reason mapping (fields do not exist internally yet).
- No batched concept resolution; per-encounter lookup is acceptable at this scale and noted for later optimization.
- No FHIR `AuditEvent`/`Provenance` (Slice 4+), RLS, SMART, or frontend.

## Task 1: Write Failing Mapper Tests

- [ ] Add `EncounterFhirMapperTest` covering all five status mappings, class coding mapping, subject reference, period start/end (including open encounters without an end), and meta version/lastUpdated.
- [ ] Run targeted tests and confirm failures are due to the missing mapper.

## Task 2: Write Failing FHIR API Tests

- [ ] Add `EncounterFhirApiIntegrationTest` covering: unauthenticated `401` without audit; clinician read `200` with full FHIR shape and `READ` audit; cross-tenant read `404` with `READ`+`FAILURE` audit; non-UUID `404` without audit; admin `403` `OperationOutcome` with denied audit; compartment search in both `patient` forms with `SEARCH` audit and newest-first entries; cross-tenant patient search `404` with `SEARCH`+`FAILURE` audit; missing `patient` parameter `400`.
- [ ] Run targeted tests and confirm failures are due to missing endpoints.

## Task 3: Implement FHIR Encounter Boundary

- [ ] Extract shared FHIR response support and migrate the Patient FHIR controller onto it.
- [ ] Implement `EncounterFhirMapper` (encounter + resolved class concept → FHIR Encounter).
- [ ] Implement `EncounterFhirController` reusing `EncounterService` and resolving class concepts via the terminology repository.

## Task 4: Verify And Commit

- [ ] Run focused FHIR, encounter, patient, and security tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Search for deferred write/paging/CapabilityStatement/US Core/validator work and payload leakage in diagnostics.
- [ ] Commit plan artifact as `docs: add Slice 3.2 FHIR Encounter read/search plan`.
- [ ] Commit implementation as `feat: add FHIR Encounter read and search boundary`.

## Self-Review Checklist

- FHIR Encounter endpoints flow through `EncounterService`; there is no FHIR-only authorization or audit bypass.
- Cross-tenant encounters and patients are unreachable through read or compartment search.
- Encounter class round-trips as a real coding from the terminology tables.
- The shared FHIR support removed duplication without changing Patient FHIR behavior.
- Searches audit the compartment patient ID, never query parameters.
- The internal model remains the source of truth; HAPI types stay at the boundary.

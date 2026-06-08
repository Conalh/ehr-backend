# Slice 1.6 Terminology Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first terminology foundation so future patient and clinical records cannot drift into display-string-only semantics.

**Architecture:** Slice 1.6 creates durable terminology schema, Kotlin value/model types, and basic repositories for canonical code systems, codings, and codeable concepts. It intentionally stops before patient registry, FHIR resources, terminology operations, HAPI validation, and external terminology imports.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring JDBC/JdbcTemplate, PostgreSQL 16, Flyway, Testcontainers, JUnit 5.

---

## File Structure

- Create `src/main/resources/db/migration/V3__terminology_foundation.sql`: terminology schema and constraints.
- Create `src/main/kotlin/dev/ehr/terminology/TerminologyIds.kt`: typed terminology IDs.
- Create `src/main/kotlin/dev/ehr/terminology/TerminologyModels.kt`: canonical code system, coding, codeable concept, and value set model types.
- Create `src/main/kotlin/dev/ehr/terminology/CanonicalCodeSystems.kt`: canonical system URI constants.
- Create `src/main/kotlin/dev/ehr/terminology/CodeSystemRepository.kt`: create/find canonical code systems.
- Create `src/main/kotlin/dev/ehr/terminology/CodingRepository.kt`: create/find codings.
- Create `src/main/kotlin/dev/ehr/terminology/CodeableConceptRepository.kt`: create codeable concepts with ordered codings and a primary coding.
- Create `src/test/kotlin/dev/ehr/terminology/TerminologySchemaMigrationTest.kt`: migration and constraint coverage.
- Create `src/test/kotlin/dev/ehr/terminology/TerminologyRepositoryIntegrationTest.kt`: repository behavior coverage.

## Acceptance Criteria

- Terminology tables exist:
  - `code_systems`
  - `code_system_versions`
  - `codings`
  - `codeable_concepts`
  - `codeable_concept_codings`
  - `value_sets`
  - `value_set_versions`
  - `value_set_members`
  - `terminology_import_runs`
- Typed IDs/value classes exist for:
  - `CodeSystemId`
  - `CodeSystemVersionId`
  - `CodingId`
  - `CodeableConceptId`
  - `ValueSetId`
  - `ValueSetVersionId`
- Model types exist for:
  - `CanonicalCodeSystem`
  - `Coding`
  - `CodeableConcept`
  - `BindingContext`
- Canonical system constants exist for SNOMED CT, LOINC, RxNorm, UCUM, ICD-10-CM, CVX, and HL7 v3 ActCode.
- Schema constraints enforce:
  - canonical URIs are nonblank and unique;
  - coding systems and codes are nonblank;
  - optional display values are nonblank when present;
  - binding context is preserved when present;
  - codeable concept coding order is stable;
  - value set members reference value set versions.
- Repositories support:
  - creating and finding canonical code systems;
  - creating codings with system, code, optional display, optional version, and user-selected flag;
  - creating codeable concepts with one or more ordered codings and a primary coding.
- Empty codeable concept coding lists are rejected.
- Display text is never used as a computational key; two different codes may share the same display text without collapsing into one concept.
- Existing security/auth/audit tests remain green.

## Task 1: Write Failing Terminology Tests

- [ ] Add migration test proving terminology tables exist.
- [ ] Add constraint tests for blank canonical URI, blank coding system, blank coding code, and blank optional display.
- [ ] Add repository test for creating and finding a canonical code system.
- [ ] Add repository test for creating a coding with system/code/display/version/userSelected.
- [ ] Add repository test for creating a codeable concept with multiple ordered codings and a primary coding.
- [ ] Add repository test proving two different codes may share display text without collapsing.
- [ ] Add repository test proving binding context is stored and returned.
- [ ] Add repository test proving empty coding lists are rejected.
- [ ] Run targeted tests and confirm failures are due to missing terminology schema/models/repositories.

## Task 2: Implement Terminology Schema

- [ ] Add `V3__terminology_foundation.sql`.
- [ ] Create core terminology tables with UUID primary keys and timestamp fields.
- [ ] Add unique/nonblank/check constraints.
- [ ] Add indexes for canonical code system URI, coding system/code/version, concept coding order, and value set membership.
- [ ] Keep `terminology_import_runs` as metadata only.

## Task 3: Implement Terminology Models And Constants

- [ ] Add typed UUID wrappers.
- [ ] Add model data classes.
- [ ] Add `BindingContext` as a small value type that preserves contexts such as `Observation.code`.
- [ ] Add canonical code system constants with exact canonical URIs from the terminology architecture doc.

## Task 4: Implement Terminology Repositories

- [ ] Add `CodeSystemRepository.create` and lookup methods.
- [ ] Add `CodingRepository.create` and lookup methods.
- [ ] Add `CodeableConceptRepository.create` that requires non-empty codings and validates the primary coding is present in the ordered list.
- [ ] Preserve coding ordinal order when reading concepts.
- [ ] Use explicit SQL and existing `JdbcTemplate` style.

## Task 5: Verify And Commit

- [ ] Run focused terminology tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Audit that no patient tables, clinical tables, FHIR endpoints/resources, terminology operations, HAPI validator integration, external terminology payloads/import jobs, SMART/Keycloak/refresh-token behavior, consent/break-glass, RLS, patient-compartment authorization, or frontend was introduced.
- [ ] Commit the Slice 1.6 plan.
- [ ] Commit the Slice 1.6 implementation.

## Self-Review Checklist

- Clinical/admin semantics are prepared for coded storage instead of display-string-only records.
- No full terminology distributions are vendored.
- No real PHI is introduced.
- The schema is small enough for Slice 1.6 but has the load-bearing tables needed for later value set and search work.
- Repository tests prove code identity comes from system/code/version, not display text.

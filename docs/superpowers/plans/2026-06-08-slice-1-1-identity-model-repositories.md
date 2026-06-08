# Slice 1.1 Identity Model Repositories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add identity/security domain model types and basic JdbcTemplate repositories on top of the Slice 1.0 schema.

**Architecture:** This slice keeps authentication and authorization behavior out of scope. It creates typed IDs, statuses, roles, security context shells, repository APIs, and integration fixtures so later JWT, policy, and audit code can use explicit domain contracts instead of raw strings and UUIDs.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring JDBC/JdbcTemplate, PostgreSQL 16, Flyway, Testcontainers, JUnit 5.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/identity/IdentityIds.kt`: typed UUID wrappers.
- Create `src/main/kotlin/dev/ehr/identity/IdentityEnums.kt`: status and role enums with DB values.
- Create `src/main/kotlin/dev/ehr/identity/IdentityModels.kt`: organization, user, practitioner, membership, OAuth client models.
- Create `src/main/kotlin/dev/ehr/identity/OrganizationRepository.kt`: organization create/read by ID and slug.
- Create `src/main/kotlin/dev/ehr/identity/UserRepository.kt`: user create/read by ID and external subject.
- Create `src/main/kotlin/dev/ehr/identity/PractitionerRepository.kt`: practitioner create/read by ID and user ID.
- Create `src/main/kotlin/dev/ehr/identity/MembershipRepository.kt`: membership create/read and role assignment/read.
- Create `src/main/kotlin/dev/ehr/security/SecurityContextModels.kt`: authenticated subject, organization context, security principal.
- Create `src/main/kotlin/dev/ehr/security/SecurityScope.kt`: parser shell for raw scope tokens.
- Create `src/test/kotlin/dev/ehr/testsupport/PostgresIntegrationTest.kt`: shared Testcontainers base.
- Create `src/test/kotlin/dev/ehr/testsupport/IdentityTestFixtures.kt`: creates two orgs, users, memberships, and roles.
- Create `src/test/kotlin/dev/ehr/identity/IdentityRepositoryIntegrationTest.kt`: repository tests.
- Create `src/test/kotlin/dev/ehr/security/SecurityContextModelsTest.kt`: security shell tests.

## Acceptance Criteria

- Organizations can be created and read by ID and slug.
- Users can be created and read by ID and external subject.
- Practitioners can be created and read by ID and user ID.
- Memberships connect organization and user correctly.
- Membership roles can be assigned and read.
- Duplicate organization slugs and duplicate user subjects fail through database constraints.
- Invalid statuses and invalid roles fail through database constraints.
- Cross-organization fixture data remains distinct through organization-aware repository methods.
- Security scope parsing preserves raw tokens and recognizes empty input.
- No JWT validation, request filters, policy decisions, audit service, patient data, FHIR endpoints, RLS, or SMART launch behavior is introduced.

## Task 1: Write Failing Tests

- [ ] Add `SecurityContextModelsTest` for scope parsing and subject/context value objects.
- [ ] Add `IdentityRepositoryIntegrationTest` with repository behavior and database constraint expectations.
- [ ] Run targeted tests and confirm they fail because model/repository classes do not exist.

## Task 2: Implement Identity And Security Models

- [ ] Add typed ID wrappers.
- [ ] Add status and role enums with `dbValue` and `fromDb`.
- [ ] Add identity model data classes.
- [ ] Add security context shells and scope parser shell.
- [ ] Run model tests and confirm they pass.

## Task 3: Implement Repositories And Fixtures

- [ ] Add organization, user, practitioner, and membership repositories using JdbcTemplate.
- [ ] Add test fixtures using the repositories.
- [ ] Run repository integration tests and confirm they pass.

## Task 4: Full Verification And Commit

- [ ] Run `.\gradlew.bat test`.
- [ ] Commit the Slice 1.1 plan, models, repositories, fixtures, and tests.

## Self-Review Checklist

- All repository methods use typed IDs at their boundary.
- Organization-aware membership reads require `OrganizationId`.
- There is no auth filter, JWT parser, policy engine, audit service, patient data, FHIR endpoint, RLS, or SMART behavior.
- Tests prove both happy paths and database-level invalid values.

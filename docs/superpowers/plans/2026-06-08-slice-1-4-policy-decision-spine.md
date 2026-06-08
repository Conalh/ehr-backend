# Slice 1.4 Policy Decision Spine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a small deny-by-default policy decision spine over the Slice 1.3 authenticated principal.

**Architecture:** Slice 1.4 introduces typed policy decision objects and a narrow evaluator service, but it does not build the final authorization product. The evaluator consumes database-backed `SecurityPrincipal` role/scope/org context and emits structured decisions future endpoints can audit and enforce.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring MVC, Spring Security OAuth2 Resource Server, MockMvc, JUnit 5, Testcontainers.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/security/PolicyModels.kt`: policy enums, request, and decision data classes.
- Create `src/main/kotlin/dev/ehr/security/PolicyEvaluator.kt`: deny-by-default evaluator with one explicit non-clinical organization-read rule.
- Create `src/main/kotlin/dev/ehr/security/PolicyDecisionController.kt`: protected `/api/v1/security/policy-check` endpoint that returns a policy decision.
- Create `src/test/kotlin/dev/ehr/security/PolicyModelsTest.kt`: policy model field coverage.
- Create `src/test/kotlin/dev/ehr/security/PolicyEvaluatorTest.kt`: role, scope, org, and unsupported-operation decisions.
- Modify `src/test/kotlin/dev/ehr/security/DevJwtAuthenticationIntegrationTest.kt`: endpoint coverage for authenticated policy-check behavior and no audit rows.

## Acceptance Criteria

- `PolicyDecision` includes allowed, subject user ID, organization ID, membership ID, resource type, operation, role basis, scope basis, nullable relationship basis, nullable purpose of use, policy version, and reason code.
- `PolicyEvaluator` accepts `SecurityPrincipal` plus `PolicyEvaluationRequest`.
- Evaluation denies by default.
- The only allowed rule is `ORGANIZATION` + `READ` for the principal's own organization when:
  - the principal has `ORG_ADMIN` or `SYSTEM_ADMIN`;
  - the principal has a compatible raw scope, `user/*.read` or `system/*.read`.
- `PolicyEvaluator` denies missing compatible scope.
- `PolicyEvaluator` denies `STAFF`, `CLINICIAN`, and `PATIENT` for organization read.
- `PolicyEvaluator` denies mismatched organization context.
- `PolicyEvaluator` denies unsupported resource type or operation.
- `/api/v1/security/policy-check` rejects unauthenticated requests.
- `/api/v1/security/policy-check` returns an allowed decision for a valid admin member JWT with compatible scope.
- `/api/v1/security/policy-check` returns a denied decision for a valid member JWT without enough role or scope.
- Existing `whoami` auth behavior remains green.
- `/internal/health` remains public.
- `/api/v1/**` and `/fhir/r4/**` remain protected.
- No audit rows are written yet.
- No audit service, clinical/patient tables, FHIR resources, SMART launch or SMART scope semantics, Keycloak, refresh tokens, consent/break-glass, RLS, patient-compartment authorization, broad policy matrix, or frontend is introduced.

## Task 1: Write Failing Policy Tests

- [ ] Add `PolicyModelsTest` that constructs a full allow and deny decision and verifies all required fields.
- [ ] Add `PolicyEvaluatorTest` for allowed organization read, missing scope, disallowed roles, mismatched organization, unsupported resource, and unsupported operation.
- [ ] Extend `DevJwtAuthenticationIntegrationTest` with policy-check endpoint unauthenticated, allowed, denied, and no-audit assertions.
- [ ] Run targeted tests and confirm failures are because policy model/evaluator/controller types do not exist.

## Task 2: Implement Policy Model

- [ ] Add `PolicyResourceType` with `ORGANIZATION` plus at least one unsupported placeholder such as `SYSTEM`.
- [ ] Add `PolicyOperation` with `READ` plus at least one unsupported operation such as `WRITE`.
- [ ] Add `PolicyReasonCode` with explicit allow/deny reasons.
- [ ] Add `PolicyEvaluationRequest`.
- [ ] Add `PolicyDecision`.

## Task 3: Implement Evaluator

- [ ] Add `PolicyEvaluator` as a Spring service.
- [ ] Derive subject, organization, membership, roles, and scopes only from `SecurityPrincipal`.
- [ ] Require request organization to match principal organization.
- [ ] Allow only `ORGANIZATION` + `READ` with admin role and compatible scope.
- [ ] Return structured deny decisions for every other path.

## Task 4: Add Protected Endpoint

- [ ] Add `/api/v1/security/policy-check`.
- [ ] Cast `Authentication.principal` to `SecurityPrincipal`.
- [ ] Evaluate `ORGANIZATION` + `READ` for the current principal organization.
- [ ] Return the full decision JSON.

## Task 5: Verify And Commit

- [ ] Run targeted policy and auth tests.
- [ ] Run `.\gradlew.bat test --rerun-tasks`.
- [ ] Audit that no deferred audit service, clinical, FHIR, SMART, Keycloak, refresh token, consent/break-glass, RLS, patient-compartment, broad matrix, or frontend behavior was introduced.
- [ ] Commit the Slice 1.4 plan, tests, and implementation.

## Self-Review Checklist

- Decisions are structured, not booleans hidden in controller logic.
- Policy is deny-by-default.
- Role and scope evidence comes from the principal, not a request body.
- Scope matching remains raw string matching, not SMART semantics.
- No audit rows are written yet.

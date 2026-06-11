# Slice H3 Compartment Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Third compartment-authorization slice per the accepted design (`docs/architecture/compartment-authorization.md`): per-organization enforcement of the treatment-relationship requirement, with a break-glass override for emergency reads and auto-expiry of encounter-derived memberships. Shadow remains the default — flipping an org to `enforced` is an explicit operator action.

**Architecture:**

- **Flag:** `organizations.compartment_enforcement` (`off | shadow | enforced`, default `shadow`, V17). A narrow `EnforcementModeResolver` (fun interface in security, implemented against `organizations`) joins `RelationshipResolver` in the evaluator's constructor; it is consulted only for relationship-requiring rules with a known patient.
- **Decision logic** (otherwise-allowed clinical-record decision with known patient): `off` → skip resolution, basis null. `shadow` → H2 behavior. `enforced` → no relationship and no break-glass ⇒ deny with new reason code `NO_TREATMENT_RELATIONSHIP`; principals without a user id fail closed the same way.
- **Break-glass:** a `X-Break-Glass-Reason` request header (mandatory non-blank free text) read via a `BreakGlassAccessor` fun interface (HTTP impl uses `RequestContextHolder` — no controller/service signature churn). Honored only for **READ** operations in `enforced` mode when no relationship exists (the write path is "open an encounter", which grants the relationship instantly, per decision 1C). Effect: allowed, `relationshipBasis = break-glass`, `purposeOfUse = ETREAT` (HL7 v3), reason recorded in audit `metadata` — never in logs. Break-glass never bypasses tenancy, roles, or scopes.
- **Audit:** the existing `audit_events.purpose_of_use` column finally gets written; `PolicyDecision` carries `purposeOfUse`/`breakGlassReason`; `AuditEventService` serializes the reason into metadata JSON via the Spring `ObjectMapper`.
- **Fetch-first denial:** the H2 post-fetch re-evaluations ("H3 enforces here") now check `allowed`. Non-transactional sites (gets, pre-tx evaluations in note-write/report-attach) deny inline; inside-transaction sites (condition update, observation/note amend, order transition) throw a `CompartmentDeniedException` carrying the decision, caught outside the transaction so the denial audit row survives the rollback. Existing pre-evaluation sites (creates, list-by-patient, chart) deny through the existing `!decision.allowed` branches unchanged.
- **Auto-expiry:** `CareTeamExpiryJob` (`@Scheduled`, hourly with initial delay; tests call `expireStale()` directly; `@EnableScheduling` via a small runtime configuration). One SQL sweep ends active `encounter-derived` memberships with **no sustaining encounter**: an encounter for (org, patient) `created_by` the member that is `planned`/`in-progress`, or `finished` with `period_end` within `ehr.compartment.encounter-derived-expiry-days` (validated `@Min(1)`, default 30). Cancelled/entered-in-error encounters do not sustain. Each ended membership gets a `SYSTEM` background audit event. Explicit memberships never auto-expire.
- `POLICY_VERSION` bumps to `policy-spine-v17`.

---

## File Structure

- Create `src/main/resources/db/migration/V17__compartment_enforcement.sql`.
- Create `src/main/kotlin/dev/ehr/security/EnforcementModeResolver.kt`, `BreakGlassAccessor.kt`; `src/main/kotlin/dev/ehr/identity/OrganizationEnforcementModeResolver.kt`; `src/main/kotlin/dev/ehr/careteam/CareTeamExpiryJob.kt`; `src/main/kotlin/dev/ehr/runtime/SchedulingConfiguration.kt`.
- Modify `PolicyModels.kt` (`NO_TREATMENT_RELATIONSHIP`, `CompartmentEnforcementMode`, decision `purposeOfUse` typed usage + `breakGlassReason`), `PolicyEvaluator.kt` (mode logic, break-glass, v17), `AuditModels.kt`/`AuditEventRepository.kt`/`AuditEventService.kt` (purpose_of_use + metadata reason), `EhrProperties.kt` (compartment block), the nine clinical services (post-fetch deny checks), `PolicyDecisionController.kt` if response shape needs the field.
- Create `src/test/kotlin/dev/ehr/security/CompartmentEnforcementIntegrationTest.kt`, `src/test/kotlin/dev/ehr/careteam/CareTeamExpiryJobIntegrationTest.kt`; extend `PolicyEvaluatorTest`.

## Acceptance Criteria

- V17: constrained `compartment_enforcement` column, default `shadow`, all existing rows backfilled `shadow`.
- Evaluator matrix (unit, stub resolvers): `enforced` + no relationship → denied `NO_TREATMENT_RELATIONSHIP`; `enforced` + relationship → allowed with basis; `enforced` + break-glass READ → allowed, `break-glass` basis, `ETREAT`; `enforced` + break-glass WRITE → denied; `off` → relationship resolver untouched, basis null; `shadow` → H2 behavior; org-wide rules never consult either resolver.
- Integration, enforced org: unrelated clinician list-by-patient → 403 with `AUTHORIZATION_DENIED`/`NO_TREATMENT_RELATIONSHIP` audit; get-by-id → 403 via the post-fetch path (denial audit row survives); related clinician read/write → 200; PATIENT demographics and ENCOUNTER open → 200 org-wide; break-glass read → 200 with `purpose_of_use = 'ETREAT'`, `relationship_basis = 'break-glass'`, reason in metadata; blank reason header → treated as absent → 403.
- Integration, `off` org: clinical reads succeed with null basis. Default `shadow`: H2 shadow test still green.
- Expiry: membership sustained by an open encounter survives the sweep; membership whose only encounter finished >30 days ago (backdated via SQL) is ended with a `SYSTEM` audit event; explicit memberships untouched; re-opening an encounter re-establishes membership (H1 idempotency).
- `policy-spine-v17` everywhere; full suite green.

## Intentional Deferrals

- No per-request UI/consent flows; no break-glass for writes; no FHIR CareTeam/RLS (H4); system-app principals still deferred (they fail closed in enforced orgs); no operator API for flipping the flag (SQL/ops for now — runbook note).

## Tasks

- [ ] Failing tests: evaluator enforcement matrix, enforcement integration scenarios, expiry job test.
- [ ] Implement V17, resolvers/accessor, evaluator logic, audit purpose_of_use + metadata, service deny checks, expiry job + config, v17 bump + literal replace.
- [ ] Focused + full suites; commit plan as `docs: add Slice H3 compartment enforcement plan`; implementation as `feat: enforce compartment authorization with break-glass and auto-expiry`.

## Self-Review Checklist

- Default posture unchanged: every org stays `shadow` after migration; no behavior change until an operator flips the flag.
- Denial audit rows survive transaction rollback on every fetch-first path.
- Break-glass: READ-only, reason mandatory, recorded in audit metadata (not logs), never bypasses tenancy/role/scope.
- Expiry sweep is tenant-correct, only touches `encounter-derived` rows, and is idempotent.

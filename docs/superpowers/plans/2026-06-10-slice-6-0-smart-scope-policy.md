# Slice 6.0 SMART Scope Model And Policy Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the policy evaluator's literal scope-string sets with a real SMART scope model: parse `patient|user|system / Resource|* . permissions` in both SMART v1 (`read`/`write`/`*`) and v2 (`cruds` subsets) forms, and map scopes to policy decisions by resource and operation semantics.

**Architecture:** A `SmartScope` value type parses raw scope strings into context (patient/user/system), FHIR resource name (or `*`), and read/write permissions (v2 `r`/`s` grant read; `c`/`u`/`d` grant write); non-clinical scopes (`openid`, `launch`, ...) parse to null and never authorize. `PolicyEvaluator` rules drop their per-rule scope-string sets in favor of a single FHIR resource name per policy resource type (`PATIENT`→`Patient`, ..., `ORDER`→`ServiceRequest`, `NOTE`→`DocumentReference`; `CHART` requires a wildcard resource); a scope is compatible when its context is `user` or `system`, its resource matches the rule's resource or `*`, and its permissions cover the operation. `patient`-context scopes are denied everywhere until patient-launch context exists (deferral). Role rules are unchanged; `scopeBasis` still reports the raw matching scope strings; `policy-spine-v12`.

**Standards Notes:** SMART App Launch 2.0 scope grammar with v1 syntax accepted for compatibility, per the AGENTS.md baseline ("SMART App Launch 2.0.0 ... designed compatibly with 2.2.0 where harmless"). Behavior is strictly widened, not changed: every previously-valid scope string keeps its meaning; v2 strings (`user/Patient.rs`, `user/*.cruds`) become meaningful instead of unmatchable.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/security/SmartScope.kt`.
- Modify `PolicyEvaluator.kt`: resource-name rule table + scope semantics, `policy-spine-v12`.
- Create `src/test/kotlin/dev/ehr/security/SmartScopeTest.kt`; extend `PolicyEvaluatorTest`; update policy-version literals.

## Acceptance Criteria

- `SmartScope.parse` handles: v1 `user/Patient.read`, `system/*.write`, `user/*.*`; v2 `user/Patient.rs`, `system/Observation.cruds`, `user/*.c`; rejects non-SMART scopes (`openid`, `fhirUser`, `launch/patient`), malformed strings, unknown permission letters, and empty segments.
- Permissions: v1 `read`→read, `write`→write, `*`→both; v2 `r` or `s`→read, any of `c`/`u`/`d`→write.
- Policy mapping: for every existing policy resource type, `user`/`system` scopes matching the rule's FHIR resource (or `*`) with covering permissions authorize; `patient`-context scopes never authorize (INSUFFICIENT_SCOPE); `CHART` requires wildcard-resource read.
- All previously-passing scope strings behave identically; v2 equivalents now also work (`user/Condition.rs` reads conditions; `user/Condition.cud` writes but does not read).
- `policy-spine-v12` with documented literal updates; full suite green.

## Intentional Deferrals

- No `patient`-context authorization (needs launch context), no launch/openid scope handling beyond ignoring them, no scope down-scoping or consent, no discovery document (6.2), no client registration (6.1).

## Tasks

- [ ] Failing `SmartScopeTest` (parse matrix) and `PolicyEvaluatorTest` additions (v2 scopes, patient-context denial, permission-direction checks).
- [ ] Implement `SmartScope` and the evaluator rework; bump version + literals.
- [ ] Focused + full suites; commit plan as `docs: add Slice 6.0 SMART scope policy plan`; implementation as `feat: add SMART scope model and scope-to-policy mapping`.

## Self-Review Checklist

- Scope semantics live in one parser, not scattered string sets.
- Patient-context scopes fail closed everywhere.
- No previously-granted access was silently revoked; no new access beyond documented v2 equivalence.

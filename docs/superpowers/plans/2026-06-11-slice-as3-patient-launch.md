# Slice AS3 Standalone Patient Launch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standalone patient launch per the accepted design (decision 5,
phase 2): a deliberately plain synthetic patient picker at authorize-time,
`patient` launch context in the token response, and **patient-context
(`patient/*`) scopes finally authorizing** — constrained to the launched
patient through the compartment plumbing H2 built.

**Decided-and-recorded:**

1. **Picker placement.** A filter in the AS chain intercepts authenticated
   `GET /oauth/authorize` requests whose scope includes `launch/patient` and
   redirects to `/launch/patient-picker` until a patient is selected for that
   authorize transaction. The picker lists the user's organization's
   synthetic patients; the POST validates org ownership, binds the selection
   to the transaction id/client/scopes/state, and bounces back to the
   original authorize URL. Plain server-rendered HTML, like the dev login.
2. **Context transport.** Token issuance is back-channel (no session), so the
   selection rides in the `OAuth2Authorization`: a delegating
   `OAuth2AuthorizationService` stamps the request-scoped selected patient
   as an authorization attribute at code-issuance time. The selection is
   consumed when the authorize request resumes, so later launches require a
   fresh explicit choice. The token customizer copies it into an
   `ehr_patient` access-token claim, and the token response gains the SMART
   `patient` parameter via a custom access-token response handler.
3. **The binding rule** (evaluator, `policy-spine-v20`): patient-context
   scopes stop failing closed — they authorize a rule's resource/direction
   only when the principal carries launch context. When **all** authorizing
   scopes are patient-context: a request for a different patient is denied
   with new reason code `OUTSIDE_PATIENT_CONTEXT`; a request with an unknown
   patient (pre-fetch get-by-id) is allowed because every fetch-first path
   re-evaluates post-fetch (H3) — the deferred check is the enforcement
   point. Mixed tokens (also holding `user/`/`system/` scopes) authorize
   org-wide through those scopes, as SMART intends.
4. **Closing the null-patient holes.** Services that never passed the
   compartment patient get it now: PatientService (get passes the path id;
   identifier search **filters results to the launched patient** for
   patient-context-only principals), EncounterService (open/timeline pass;
   get/transition gain the H2-style post-fetch re-evaluation),
   CareTeamService (add/list pass; end re-evaluates post-fetch). Chart and
   all clinical verticals already pass it.
5. **Discovery** gains `launch-standalone`, `context-standalone-patient`, and
   the `launch/patient` + `patient/*` scopes.

---

## File Structure

- Create `src/main/kotlin/dev/ehr/authz/PatientLaunchController.kt` (picker page + selection POST), `PatientLaunchFilter.kt`, `LaunchContextAuthorizationService.kt` (delegating stamp).
- Modify `AuthorizationServerConfiguration.kt` (filter, delegating service, `ehr_patient` claim, `patient` token-response parameter), `SecurityContextModels.kt` (`launchPatientId`), `JwtPrincipalAuthenticationConverter.kt`, `PolicyModels.kt` (`OUTSIDE_PATIENT_CONTEXT`), `PolicyEvaluator.kt` (binding + v20), `PatientService/EncounterService/CareTeamService`, `SmartConfigurationController.kt`.
- Create `src/test/kotlin/dev/ehr/authz/PatientLaunchIntegrationTest.kt`; extend `PolicyEvaluatorTest`.

## Acceptance Criteria

- Flow: authorize with `launch/patient patient/*.read` → picker redirect → selecting an own-org patient → code → token response carries `patient`; selecting a foreign patient → 400.
- The patient-scoped token: reads the launched patient's chart/conditions (200, audited); a different patient's list → 403 `OUTSIDE_PATIENT_CONTEXT` with denial audit; a different patient's condition by id → 403 at the post-fetch check; identifier search returns only the launched patient; the launched patient's demographics readable.
- Tokens without launch context: patient-context scopes still fail closed (existing tests unchanged); user-scope tokens unchanged.
- `policy-spine-v20` everywhere; discovery accurate; full suite green.

## Intentional Deferrals

- EHR launch (`launch` scope) until something exists to launch from; patient-facing users (PATIENT role flows); refresh-token launch-context persistence beyond what the authorization holds; AS4 `$export`.

## Tasks

- [ ] Failing tests: launch flow + binding matrix + search filtering + evaluator units.
- [ ] Implement picker/filter/context transport, claim + response parameter, principal field, evaluator binding + v20 + literals, service touch-ups, discovery.
- [ ] Focused + full suites; commit plan as `docs: add Slice AS3 patient launch plan`; implementation as `feat: add standalone patient launch with patient-context scopes`.

## Self-Review Checklist

- The launched patient boundary cannot be escaped: every read path either carries the patient at evaluation or re-evaluates post-fetch.
- The picker only ever offers (and accepts) the user's own organization's patients.
- No behavior change for non-launch tokens; patient-context scopes without launch context remain fail-closed.

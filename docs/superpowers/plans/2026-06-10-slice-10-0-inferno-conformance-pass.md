# Slice 10.0 Inferno-Oriented Conformance Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the design spec roadmap: document how to run ONC Inferno g10 locally against this server, produce an honest gap report mapping every g10 test group to supported / partial / intentionally-out-of-scope, fix the conformance gap the analysis surfaces in supported behavior (searchset Bundles missing `self` links), and record the explicit unsupported-criteria list.

**Architecture:** The deliverable is knowledge plus one fix. `docs/conformance/inferno-g10.md` carries: (1) local run instructions (Inferno g10 test kit via Docker against `http://host.docker.internal:8080`, dev-JWT bearer configuration, its limits given no real authorization server); (2) a test-group-by-test-group gap matrix grounded in what Slices 0–9 actually built; (3) the explicit out-of-scope list (launch flows, OIDC, patient-context scopes, US Core profile conformance, `$export` protocol shape) with the slice/decision that defers each. The code fix: every searchset Bundle gains a `link[self]` carrying the request URL — a FHIR conventions gap Inferno's search tests flag — added uniformly across all nine search endpoints and asserted in tests.

**Standards Notes:** The exit criterion is *knowing* which tests pass, fail, or are out of scope — not passing g10, which requires SMART launch + US Core conformance this product has deliberately deferred. The gap report says so plainly, per the AGENTS.md no-unearned-claims rule.

---

## File Structure

- Modify the nine FHIR search endpoints (`PatientFhirController` ... `ProvenanceFhirController`, `DiagnosticReportFhirController`): add `link[self]` to searchset Bundles.
- Modify two FHIR search tests to assert the self link.
- Create `docs/conformance/inferno-g10.md`.

## Acceptance Criteria

- Every `searchset` Bundle includes `link[0].relation == "self"` with the request URL (query string included).
- The conformance document contains runnable Inferno instructions, a complete g10 test-group matrix with grounded statuses, and the explicit unsupported list.
- Full suite green.

## Intentional Deferrals

- Actually executing Inferno in CI (needs a running stack + the kit; instructions provided), SMART launch flows, OIDC, US Core profile validation, FHIR `$export` protocol — all already-tracked deferrals, now consolidated in one place.

## Tasks

- [ ] Failing test assertions for the self link; implement across all nine search endpoints.
- [ ] Write the conformance document.
- [ ] Focused + full suites; commit plan as `docs: add Slice 10.0 Inferno conformance pass plan`; code as `feat: add self links to searchset bundles`; document as `docs: add Inferno g10 run instructions and gap report`.

## Self-Review Checklist

- The gap matrix claims nothing untested and hides nothing unsupported.
- The self link is uniform across every search endpoint.
- Out-of-scope items reference the decision that deferred them.

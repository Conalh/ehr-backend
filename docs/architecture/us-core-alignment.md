# US Core Profile Alignment Design

Date: 2026-06-11
Status: **Draft — recommendation marked; scope is the one goal-level call.**

## Problem

The refreshed Inferno gap report (`docs/conformance/inferno-g10.md`) has one
dominant remaining work program: the FHIR boundary serves base-R4-valid
resources, but claims no US Core profiles, omits must-support fields the
profiles require, and supports only the registry's compartment search
parameters. g10's Single Patient API group tests against **US Core**, not
base R4, so until profiles are declared and honored, those rows stay
"partial" no matter how clean the base validation is.

What carries over unchanged: the honesty rule (declare a profile only after
validating against it in tests), the registry-driven CapabilityStatement
(profiles become registry entries → `supportedProfile` declarations), and
the policy spine (profile work is mapper/search work; authorization does not
change).

## Decision 1 — How far to go (the goal-level call)

US Core 6.1 spans ~40 profiles. Full alignment is a product-scale program;
this is a portfolio repo. **Options:**

A. **Profile-align what we already serve (recommended core).** Declare and
   validate US Core profiles for the eleven served resources where a profile
   exists (Patient, Encounter, Condition, AllergyIntolerance, Observation
   vital-signs + laboratory, MedicationStatement→ *(note: US Core uses
   MedicationRequest; see Decision 3)*, DocumentReference, DiagnosticReport,
   CareTeam, Practitioner, Provenance). Fix the mappers' must-support gaps
   (Patient identifier/name slicing, Encounter type/participant, vital-signs
   category/coding magic values, etc.). High credibility per unit effort:
   every change is testable with the same HAPI validator already in the
   suite, now loaded with the US Core package.
B. **A + the g10 search parameter set.** `_id`, `date`, `code`, `status`,
   `category` where US Core requires them, plus `_revinclude=Provenance:target`.
   This is what makes the Single Patient API group actually runnable.
C. **B + the missing US Core resource types** (Immunization, Procedure,
   CarePlan, Goal, Device, Location, Organization read, PractitionerRole).
   Each is a Slice-3-style vertical; mechanical but voluminous.

**Recommendation: B.** A without search parameters still fails the Inferno
group it aims at; C is open-ended and adds breadth, not new engineering
ideas. Stop after B, re-run Inferno, and let observed results justify any C
work.

## Decision 2 — Validation mechanics

The conformance test's `ValidationSupportChain` gains the US Core IG package
(`hl7.fhir.us.core` via `NpmPackageValidationSupport`), and every mapper
example must validate against its **declared profile**, not just base R4.
`Resource.meta.profile` is stamped by the mappers; the registry carries the
profile URL per resource and the CapabilityStatement emits
`supportedProfile`. Declaration and validation land in the same slice per
resource — never declare ahead of proof.

## Decision 3 — The MedicationStatement problem

US Core dropped MedicationStatement; medication lists are
MedicationRequest-shaped. Re-modeling our medication vertical is out of
proportion. **Recorded position:** MedicationStatement stays served as base
R4 (no US Core claim), and the gap report says so explicitly. Revisit only
if an Inferno run shows the medications group is otherwise reachable.

## Decision 4 — Search parameter architecture

The registry stays the single source of truth: each `SupportedSearchParam`
gains a typed handler (token/date/reference) and the FHIR controllers
translate to repository predicates — no generic FHIR search engine, no
hand-waved SQL. `_revinclude=Provenance:target` is implemented as a
post-query join through the existing provenance repository (bounded:
only that one revinclude, refused otherwise with OperationOutcome).

## Slice breakdown (proposed)

- **UC1:** validator loads US Core; Patient profile (identifier/name/gender
  must-supports) declared + validated; registry/CapabilityStatement emit
  `supportedProfile`.
- **UC2:** Observation vital-signs + laboratory profiles (category slicing,
  component handling) and DiagnosticReport.
- **UC3:** Condition, AllergyIntolerance, Encounter, DocumentReference,
  CareTeam, Practitioner, Provenance profiles.
- **UC4:** the search parameter set (`_id`, `status`, `date`, `code`,
  `category` per US Core) across served resources.
- **UC5:** `_revinclude=Provenance:target`; refresh the gap report; Inferno
  re-run readiness.

## Open question for review

1. Scope: stop at B (recommended), or extend to C's resource verticals?
   (Everything else above is decide-and-record.)

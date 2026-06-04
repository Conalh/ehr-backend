# Terminology Model

Date: 2026-06-04
Status: Draft for review

## Why This Exists

Clinical interoperability is mostly semantics. The shape of a FHIR resource matters, but the meaning of many fields comes from terminology bindings: SNOMED CT, LOINC, RxNorm, UCUM, ICD-10-CM, CVX, HL7 code systems, and VSAC value sets.

The backend must not store observations, conditions, medications, allergies, procedures, or note categories as plain strings only. Display text is not the clinical meaning.

## Current Best-Practice Baseline

FHIR R4 provides the semantic container:

- `Coding`: one code from one terminology system.
- `CodeableConcept`: one clinical concept represented by zero or more codings plus optional text.
- `CodeSystem`: defines code meanings.
- `ValueSet`: defines allowed or expected sets of codes.
- `ConceptMap`: maps concepts between systems.
- Terminology service operations such as `$validate-code`, `$expand`, `$lookup`, and `$translate`.

US Core binds many elements to value sets and code systems. In the U.S. context, common systems include:

- SNOMED CT for conditions, clinical findings, body sites, routes, procedures, and other clinical concepts.
- LOINC for lab tests, vital signs, survey instruments, clinical notes, and observation names.
- RxNorm for clinical drugs.
- UCUM for units of measure.
- ICD-10-CM where diagnosis classification is required.
- CVX for vaccines.
- HL7 Terminology code systems for administrative/status/security concepts.
- VSAC for many U.S. value sets used by US Core, C-CDA, eCQMs, and other programs.

This is standardized, but not centralized in one clean package. FHIR defines the model; implementation guides bind fields to value sets; external vocabulary stewards maintain the codes and licenses.

## Licensing Reality

Do not vendor full terminology payloads casually.

- SNOMED CT US Edition is distributed by NLM through UMLS Terminology Services to licensed individuals. There is no charge for U.S. use, but a license/account is still required.
- VSAC access requires UMLS credentials.
- LOINC and RxNorm have public access paths, but each vocabulary still has its own terms and release process.

For early implementation:

- Use small curated synthetic fixtures.
- Store canonical system URLs and codes.
- Do not commit full downloaded terminology distributions into the repo.
- Keep import tooling separate from the core model.

## Canonical Code Systems

Use canonical URIs in storage and FHIR output.

| System | Canonical URI | Primary Use |
| --- | --- | --- |
| SNOMED CT | `http://snomed.info/sct` | Conditions, findings, procedures, body sites, allergy manifestations |
| LOINC | `http://loinc.org` | Labs, vitals, survey observations, clinical note/document types |
| RxNorm | `http://www.nlm.nih.gov/research/umls/rxnorm` | Medications |
| UCUM | `http://unitsofmeasure.org` | Units for quantities |
| ICD-10-CM | `http://hl7.org/fhir/sid/icd-10-cm` | Diagnosis classification/reporting |
| CVX | `http://hl7.org/fhir/sid/cvx` | Immunizations |
| HL7 v3 ActCode | `http://terminology.hl7.org/CodeSystem/v3-ActCode` | Administrative/security/purpose concepts |

## Core Data Types

### Coding

A coding represents a single code in a system.

Fields:

- `id`
- `system`
- `version`
- `code`
- `display`
- `user_selected`
- `created_at`

Rules:

- `system` and `code` are required for computable codings.
- `display` is for humans and must not be used as the computational key.
- `version` is optional but should be preserved when imported or selected from a versioned release.
- Multiple codings can represent the same concept.

### Codeable Concept

A codeable concept represents one clinical idea.

Fields:

- `id`
- `text`
- `primary_coding_id`
- `binding_context`
- `created_at`

Relationship:

- `codeable_concept_codings(concept_id, coding_id, ordinal)`

Rules:

- A concept may have multiple codings, such as local code plus LOINC.
- `text` preserves user-facing wording or source text.
- `primary_coding_id` identifies the preferred coding for default FHIR output and search indexing.
- `binding_context` records where the concept is used, such as `Observation.code` or `Condition.code`.

## Terminology Tables

Minimum schema spine:

- `code_systems`
- `code_system_versions`
- `concepts`
- `codings`
- `codeable_concepts`
- `codeable_concept_codings`
- `value_sets`
- `value_set_versions`
- `value_set_members`
- `concept_maps`
- `concept_map_entries`
- `terminology_import_runs`

Example fields:

`code_systems`

- `id`
- `canonical_uri`
- `name`
- `publisher`
- `license_note`

`value_sets`

- `id`
- `canonical_url`
- `oid`
- `name`
- `source`
- `profile_context`

`value_set_members`

- `value_set_version_id`
- `system`
- `code`
- `display`
- `code_system_version`

## Clinical Model Usage

Patient:

- Administrative fields still need codes for language, race, ethnicity, gender identity, and related US Core demographic elements where supported.

Encounter:

- `class`
- `type`
- `status`
- `service_type`
- `reason_code`

Condition:

- `code`
- `clinical_status`
- `verification_status`
- `category`
- optional ICD-10-CM classification code

Allergy:

- `code`
- `type`
- `category`
- `criticality`
- `reaction_manifestation`
- `reaction_severity`
- `verification_status`

Medication:

- `medication_code`
- `route`
- `dose_unit`
- `as_needed_reason`

Observation:

- `code`
- `category`
- `value_quantity_unit`
- `interpretation`
- `method`
- `body_site`
- `specimen_type`

DiagnosticReport:

- `code`
- `category`
- linked observation codes

ClinicalNote / DocumentReference:

- `type`
- `category`
- `security_label`
- `format`

## Search Indexing

Terminology feeds FHIR search.

Token search uses:

- `system`
- `code`
- `value`

Quantity search uses:

- numeric value
- comparator
- UCUM unit code
- normalized canonical quantity where practical

Reference search is not terminology, but it must combine with coded filters. Example: `Observation?patient=...&code=http://loinc.org|85354-9`.

## Validation Strategy

Do not build a full terminology server in the first milestone.

Early validation levels:

1. Structural validation: required coded fields have `system` and `code`.
2. Fixture validation: codes used in synthetic fixtures exist in our curated concept set.
3. Binding-context validation: a code used in `Observation.code` cannot be silently reused as `Medication.code`.
4. Later value-set validation: call HAPI validator and/or a terminology service for `$validate-code`.

Invalid or unknown codes:

- Reject when the field is bound to a required/closed set.
- Accept with warning only for configured extensible bindings and preserve source text.
- Never silently replace source code with display text.

## Synthetic Data

Use Synthea or a similar generator as an ingest source only after the core terminology structure exists.

Synthetic ingest must:

- preserve source system/code/display/version where present;
- create codeable concepts instead of raw strings;
- record a `synthetic_generation_run_id`;
- create provenance events for imported facts;
- avoid committing generated patient datasets that look like real PHI.

## Round-Trip Policy

Outbound FHIR:

- Render every supported coded element as a FHIR `CodeableConcept`, `Coding`, `code`, or `Quantity` as appropriate.
- Preserve multiple codings when known.
- Include `text` only as human-readable support, not as a substitute for coding.

Inbound FHIR:

- Reject unsupported coded elements if losing them would alter meaning.
- Preserve inbound snapshots where round-trip behavior is required.
- Map supported codings into internal codeable concepts.
- Retain local/unknown codings when policy allows, but mark validation status.

## Milestone Requirements

Slice 1:

- Terminology package and schema placeholders exist.

Slice 2:

- Patient demographic codings and identifier systems are represented.

Slice 2.5:

- Token search index supports coded search parameters.

Slice 3:

- Conditions, allergies, medications, observations, diagnostic reports, and notes use codeable concepts from the start.

Slice 3.5:

- Minimal Inferno smoke tests verify coded Patient/Condition/Observation behavior where supported.

## Open Questions

1. Which curated synthetic code set should be committed first?
2. Do we use Synthea immediately for fixtures, or first handcraft a tiny deterministic fixture set?
3. Do we use local terminology tables only in the first milestone, or wire HAPI validator to packaged terminology early?

Recommended defaults:

- Start with a tiny deterministic fixture set.
- Add Synthea import after the search index and provenance spine exist.
- Use local terminology tables first; add external validation after the data model is stable.


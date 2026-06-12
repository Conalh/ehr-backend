package dev.ehr.fhir

/**
 * Single source of truth for what the FHIR boundary actually serves.
 * The CapabilityStatement is generated from this registry; changing the
 * FHIR surface without updating it is a review failure.
 */
object FhirCapabilityRegistry {
    enum class SearchParamType {
        TOKEN,
        REFERENCE,
        DATE,
    }

    data class SupportedSearchParam(
        val name: String,
        val type: SearchParamType,
        val documentation: String,
    )

    data class SupportedResource(
        val type: String,
        val searchParams: List<SupportedSearchParam>,
        // Declared only once the conformance suite validates against them
        // (US Core alignment design, decision 2). Instances stamp exactly
        // the profile matching their shape, never the whole list.
        val profiles: List<String> = emptyList(),
        // Supports _revinclude=Provenance:target on the compartment search.
        val revIncludesProvenance: Boolean = false,
    )

    private val patientParam = SupportedSearchParam(
        name = "patient",
        type = SearchParamType.REFERENCE,
        documentation = "Patient compartment search by logical id or Patient/{id} reference.",
    )

    val supportedResources: List<SupportedResource> = listOf(
        SupportedResource(
            type = "Patient",
            searchParams = listOf(
                SupportedSearchParam(
                    name = "identifier",
                    type = SearchParamType.TOKEN,
                    documentation = "Exact identifier match in system|value form.",
                ),
                SupportedSearchParam(
                    name = "_id",
                    type = SearchParamType.TOKEN,
                    documentation = "Logical id; a non-match is an empty bundle.",
                ),
            ),
            profiles = listOf("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"),
        ),
        // Encounter stays base R4: us-core-encounter requires Encounter.type,
        // which this model does not capture (class only).
        SupportedResource("Encounter", listOf(patientParam)),
        SupportedResource(
            type = "Condition",
            searchParams = listOf(
                patientParam,
                SupportedSearchParam(
                    name = "category",
                    type = SearchParamType.TOKEN,
                    documentation = "Condition category (every condition is problem-list-item).",
                ),
                SupportedSearchParam(
                    name = "clinical-status",
                    type = SearchParamType.TOKEN,
                    documentation = "Condition clinical status code.",
                ),
            ),
            profiles = listOf(
                "http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition-problems-health-concerns",
            ),
            revIncludesProvenance = true,
        ),
        SupportedResource(
            type = "AllergyIntolerance",
            searchParams = listOf(patientParam),
            profiles = listOf("http://hl7.org/fhir/us/core/StructureDefinition/us-core-allergyintolerance"),
            revIncludesProvenance = true,
        ),
        SupportedResource(
            type = "Observation",
            searchParams = listOf(
                patientParam,
                SupportedSearchParam(
                    name = "category",
                    type = SearchParamType.TOKEN,
                    documentation = "Observation category code (vital-signs or laboratory).",
                ),
                SupportedSearchParam(
                    name = "code",
                    type = SearchParamType.TOKEN,
                    documentation = "Observation code as system|code or bare code.",
                ),
                SupportedSearchParam(
                    name = "date",
                    type = SearchParamType.DATE,
                    documentation = "Effective time with eq|ge|gt|le|lt prefixes; repeatable for ranges.",
                ),
            ),
            profiles = listOf(
                "http://hl7.org/fhir/us/core/StructureDefinition/us-core-vital-signs",
                "http://hl7.org/fhir/us/core/StructureDefinition/us-core-observation-lab",
            ),
            revIncludesProvenance = true,
        ),
        // MedicationStatement stays base R4: US Core dropped the resource.
        SupportedResource("MedicationStatement", listOf(patientParam)),
        // DocumentReference stays base R4: the us-core type binding composes
        // over full LOINC, which cannot ship offline for validation.
        SupportedResource("DocumentReference", listOf(patientParam)),
        SupportedResource(
            type = "DiagnosticReport",
            searchParams = listOf(
                patientParam,
                SupportedSearchParam(
                    name = "category",
                    type = SearchParamType.TOKEN,
                    documentation = "Report category (every report is LAB).",
                ),
                SupportedSearchParam(
                    name = "code",
                    type = SearchParamType.TOKEN,
                    documentation = "Report code as system|code or bare code.",
                ),
                SupportedSearchParam(
                    name = "date",
                    type = SearchParamType.DATE,
                    documentation = "Issued time with eq|ge|gt|le|lt prefixes; repeatable for ranges.",
                ),
            ),
            profiles = listOf("http://hl7.org/fhir/us/core/StructureDefinition/us-core-diagnosticreport-lab"),
            revIncludesProvenance = true,
        ),
        SupportedResource(
            type = "CareTeam",
            searchParams = listOf(
                patientParam,
                SupportedSearchParam(
                    name = "status",
                    type = SearchParamType.TOKEN,
                    documentation = "Care team status (the served team is always active).",
                ),
            ),
            // Stamped only on participant-bearing instances (the profile
            // requires participant 1..*; empty teams are valid here).
            profiles = listOf("http://hl7.org/fhir/us/core/StructureDefinition/us-core-careteam"),
        ),
        // Read-only: no search params means no search-type interaction.
        // Practitioner stays base R4: us-core-practitioner requires a
        // structured family name; practitioners carry display names only.
        SupportedResource("Practitioner", emptyList()),
        SupportedResource(
            type = "Provenance",
            searchParams = listOf(
                SupportedSearchParam(
                    name = "target",
                    type = SearchParamType.REFERENCE,
                    documentation = "Provenance for a target resource as {Type}/{id}.",
                ),
                patientParam,
            ),
            profiles = listOf("http://hl7.org/fhir/us/core/StructureDefinition/us-core-provenance"),
        ),
    )
}

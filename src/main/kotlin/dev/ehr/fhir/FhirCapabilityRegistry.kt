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
            ),
            profiles = listOf("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"),
        ),
        // Encounter stays base R4: us-core-encounter requires Encounter.type,
        // which this model does not capture (class only).
        SupportedResource("Encounter", listOf(patientParam)),
        SupportedResource(
            type = "Condition",
            searchParams = listOf(patientParam),
            profiles = listOf(
                "http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition-problems-health-concerns",
            ),
        ),
        SupportedResource(
            type = "AllergyIntolerance",
            searchParams = listOf(patientParam),
            profiles = listOf("http://hl7.org/fhir/us/core/StructureDefinition/us-core-allergyintolerance"),
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
            ),
            profiles = listOf(
                "http://hl7.org/fhir/us/core/StructureDefinition/us-core-vital-signs",
                "http://hl7.org/fhir/us/core/StructureDefinition/us-core-observation-lab",
            ),
        ),
        // MedicationStatement stays base R4: US Core dropped the resource.
        SupportedResource("MedicationStatement", listOf(patientParam)),
        // DocumentReference stays base R4: the us-core type binding composes
        // over full LOINC, which cannot ship offline for validation.
        SupportedResource("DocumentReference", listOf(patientParam)),
        SupportedResource(
            type = "DiagnosticReport",
            searchParams = listOf(patientParam),
            profiles = listOf("http://hl7.org/fhir/us/core/StructureDefinition/us-core-diagnosticreport-lab"),
        ),
        SupportedResource(
            type = "CareTeam",
            searchParams = listOf(patientParam),
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

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
        SupportedResource("Encounter", listOf(patientParam)),
        SupportedResource("Condition", listOf(patientParam)),
        SupportedResource("AllergyIntolerance", listOf(patientParam)),
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
        SupportedResource("MedicationStatement", listOf(patientParam)),
        SupportedResource("DocumentReference", listOf(patientParam)),
        SupportedResource(
            type = "DiagnosticReport",
            searchParams = listOf(patientParam),
            profiles = listOf("http://hl7.org/fhir/us/core/StructureDefinition/us-core-diagnosticreport-lab"),
        ),
        SupportedResource("CareTeam", listOf(patientParam)),
        // Read-only: no search params means no search-type interaction.
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
        ),
    )
}

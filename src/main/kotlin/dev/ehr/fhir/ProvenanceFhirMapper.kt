package dev.ehr.fhir

import dev.ehr.provenance.ProvenanceActivity
import dev.ehr.provenance.ProvenanceEvent
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.InstantType
import org.hl7.fhir.r4.model.Provenance
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.util.Date

@Component
class ProvenanceFhirMapper {
    fun toFhirProvenance(event: ProvenanceEvent): Provenance {
        val fhirProvenance = Provenance()

        fhirProvenance.id = event.id.toString()
        fhirProvenance.meta.addProfile("http://hl7.org/fhir/us/core/StructureDefinition/us-core-provenance")
        fhirProvenance.addTarget(
            Reference("${fhirTypeFor(event.targetResourceType)}/${event.targetResourceId}"),
        )
        fhirProvenance.recordedElement = InstantType(Date.from(event.recordedAt))
            .apply { setTimeZoneZulu(true) }
        fhirProvenance.activity = CodeableConcept()
            .addCoding(
                Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v3-DataOperation")
                    .setCode(dataOperationFor(event.activity)),
            )
            .setText(event.activity.dbValue)
        fhirProvenance.addAgent(
            Provenance.ProvenanceAgentComponent()
                .setWho(
                    Reference().setIdentifier(
                        Identifier()
                            .setSystem(AGENT_USER_SYSTEM)
                            .setValue(event.agentUserId?.value?.toString() ?: "system"),
                    ),
                ),
        )

        return fhirProvenance
    }

    private fun dataOperationFor(activity: ProvenanceActivity): String =
        when (activity) {
            ProvenanceActivity.CREATED -> "CREATE"
            ProvenanceActivity.UPDATED,
            ProvenanceActivity.CORRECTED,
            ProvenanceActivity.AMENDED,
            ProvenanceActivity.ADDENDED,
            -> "UPDATE"
            ProvenanceActivity.ENTERED_IN_ERROR -> "NULLIFY"
        }

    companion object {
        const val AGENT_USER_SYSTEM = "urn:ehr:user-id"

        private val internalToFhirType = mapOf(
            "PATIENT" to "Patient",
            "ENCOUNTER" to "Encounter",
            "CONDITION" to "Condition",
            "ALLERGY" to "AllergyIntolerance",
            "OBSERVATION" to "Observation",
            "MEDICATION" to "MedicationStatement",
            "NOTE" to "DocumentReference",
            "ORDER" to "ServiceRequest",
            "DIAGNOSTIC_REPORT" to "DiagnosticReport",
        )

        private val fhirToInternalType = internalToFhirType.entries.associate { it.value to it.key }

        fun fhirTypeFor(internalType: String): String =
            internalToFhirType[internalType] ?: internalType

        fun internalTypeFor(fhirType: String): String? =
            fhirToInternalType[fhirType]
    }
}

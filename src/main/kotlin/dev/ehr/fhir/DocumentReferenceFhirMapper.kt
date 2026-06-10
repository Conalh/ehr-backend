package dev.ehr.fhir

import dev.ehr.note.ClinicalNote
import dev.ehr.note.ClinicalNoteStatus
import dev.ehr.terminology.CodeableConcept
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import org.hl7.fhir.r4.model.CodeableConcept as FhirCodeableConcept

@Component
class DocumentReferenceFhirMapper {
    fun toFhirDocumentReference(
        note: ClinicalNote,
        typeConcept: CodeableConcept,
    ): DocumentReference {
        val fhirDocument = DocumentReference()

        fhirDocument.id = note.id.value.toString()
        fhirDocument.meta.versionId = note.version.toString()
        fhirDocument.meta.lastUpdated = Date.from(note.updatedAt)
        fhirDocument.status = toFhirStatus(note.status)
        fhirDocument.type = toFhirConcept(typeConcept)
        fhirDocument.subject = Reference("Patient/${note.patientId.value}")
        fhirDocument.context.addEncounter(Reference("Encounter/${note.encounterId.value}"))
        fhirDocument.date = Date.from(note.authoredAt)
        fhirDocument.description = note.title
        fhirDocument.addContent(
            DocumentReference.DocumentReferenceContentComponent()
                .setAttachment(
                    Attachment()
                        .setContentType("text/plain")
                        .setData(note.contentText.toByteArray(StandardCharsets.UTF_8)),
                ),
        )

        return fhirDocument
    }

    private fun toFhirStatus(status: ClinicalNoteStatus): Enumerations.DocumentReferenceStatus =
        when (status) {
            ClinicalNoteStatus.CURRENT -> Enumerations.DocumentReferenceStatus.CURRENT
            ClinicalNoteStatus.SUPERSEDED -> Enumerations.DocumentReferenceStatus.SUPERSEDED
            ClinicalNoteStatus.ENTERED_IN_ERROR -> Enumerations.DocumentReferenceStatus.ENTEREDINERROR
        }

    private fun toFhirConcept(concept: CodeableConcept): FhirCodeableConcept {
        val fhirConcept = FhirCodeableConcept()
        concept.codings.forEach { coding ->
            fhirConcept.addCoding(
                Coding()
                    .setSystem(coding.system)
                    .setCode(coding.code)
                    .setDisplay(coding.display),
            )
        }
        concept.text?.let(fhirConcept::setText)
        return fhirConcept
    }
}

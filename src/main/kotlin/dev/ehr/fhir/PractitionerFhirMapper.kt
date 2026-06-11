package dev.ehr.fhir

import dev.ehr.identity.Practitioner
import dev.ehr.identity.PractitionerStatus
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Identifier
import org.springframework.stereotype.Component

@Component
class PractitionerFhirMapper {
    fun toFhirPractitioner(practitioner: Practitioner): org.hl7.fhir.r4.model.Practitioner {
        val fhirPractitioner = org.hl7.fhir.r4.model.Practitioner()
        fhirPractitioner.id = practitioner.id.value.toString()
        fhirPractitioner.active = practitioner.status == PractitionerStatus.ACTIVE
        fhirPractitioner.addName(HumanName().setText(practitioner.displayName))
        practitioner.npi?.let { npi ->
            fhirPractitioner.addIdentifier(
                Identifier()
                    .setSystem(NPI_SYSTEM)
                    .setValue(npi),
            )
        }
        return fhirPractitioner
    }

    companion object {
        const val NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi"
    }
}

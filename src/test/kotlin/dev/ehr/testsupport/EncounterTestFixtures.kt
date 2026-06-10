package dev.ehr.testsupport

import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository

class EncounterTestFixtures(
    private val codingRepository: CodingRepository,
    private val codeableConceptRepository: CodeableConceptRepository,
) {
    fun createEncounterClassConcept(
        code: String = "AMB",
        display: String = "ambulatory",
    ): CodeableConcept {
        // Codings are globally unique per (system, code, version): reuse when present.
        val coding = codingRepository.findBySystemCodeVersion(
            system = CanonicalCodeSystems.HL7_V3_ACT_CODE,
            code = code,
        ) ?: codingRepository.create(
            system = CanonicalCodeSystems.HL7_V3_ACT_CODE,
            code = code,
            display = display,
        )
        return codeableConceptRepository.create(
            text = display,
            codingIds = listOf(coding.id),
            primaryCodingId = coding.id,
        )
    }
}

package dev.ehr.testsupport

import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository

class TerminologyTestFixtures(
    private val codingRepository: CodingRepository,
    private val codeableConceptRepository: CodeableConceptRepository,
) {
    // Codings are globally unique per (system, code, version): reuse when present.
    fun findOrCreateConcept(
        system: String,
        code: String,
        display: String,
    ): CodeableConcept {
        val coding = codingRepository.findBySystemCodeVersion(system = system, code = code)
            ?: codingRepository.create(system = system, code = code, display = display)
        return codeableConceptRepository.create(
            text = display,
            codingIds = listOf(coding.id),
            primaryCodingId = coding.id,
        )
    }
}

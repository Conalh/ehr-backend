package dev.ehr.terminology

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import kotlin.test.assertFailsWith

class TerminologyRepositoryIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var codeSystemRepository: CodeSystemRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Test
    fun `canonical code systems can be created and found by uri`() {
        val suffix = UUID.randomUUID()
        val created = codeSystemRepository.create(
            canonicalUri = "http://example.test/fhir/CodeSystem/local-$suffix",
            name = "Local Test System $suffix",
            publisher = "Synthetic Test Publisher",
            licenseNote = "Synthetic fixtures only",
        )

        assertNotNull(created.id)
        assertEquals(created, codeSystemRepository.findById(created.id))
        assertEquals(created, codeSystemRepository.findByCanonicalUri(created.canonicalUri))
    }

    @Test
    fun `codings preserve computable identity and human display fields`() {
        val suffix = UUID.randomUUID()
        val created = codingRepository.create(
            system = CanonicalCodeSystems.LOINC,
            code = "85354-9-$suffix",
            display = "Blood pressure panel",
            version = "2.78",
            userSelected = true,
        )

        assertEquals(CanonicalCodeSystems.LOINC, created.system)
        assertEquals("85354-9-$suffix", created.code)
        assertEquals("Blood pressure panel", created.display)
        assertEquals("2.78", created.version)
        assertEquals(true, created.userSelected)
        assertEquals(created, codingRepository.findById(created.id))
    }

    @Test
    fun `codeable concepts preserve ordered codings primary coding and binding context`() {
        val suffix = UUID.randomUUID()
        val localCoding = codingRepository.create(
            system = "http://example.test/fhir/CodeSystem/local-observation-$suffix",
            code = "bp-panel",
            display = "Blood pressure panel",
            userSelected = true,
        )
        val loincCoding = codingRepository.create(
            system = CanonicalCodeSystems.LOINC,
            code = "85354-9-$suffix",
            display = "Blood pressure panel",
            version = "2.78",
        )

        val created = codeableConceptRepository.create(
            text = "Blood pressure panel",
            bindingContext = BindingContext("Observation.code"),
            codingIds = listOf(localCoding.id, loincCoding.id),
            primaryCodingId = loincCoding.id,
        )

        assertEquals("Blood pressure panel", created.text)
        assertEquals(BindingContext("Observation.code"), created.bindingContext)
        assertEquals(loincCoding.id, created.primaryCoding.id)
        assertEquals(listOf(localCoding, loincCoding), created.codings)
        assertEquals(created, codeableConceptRepository.findById(created.id))
    }

    @Test
    fun `display text is not used as the computational key`() {
        val suffix = UUID.randomUUID()
        val first = codingRepository.create(
            system = CanonicalCodeSystems.SNOMED_CT,
            code = "111111-$suffix",
            display = "Shared Display",
        )
        val second = codingRepository.create(
            system = CanonicalCodeSystems.SNOMED_CT,
            code = "222222-$suffix",
            display = "Shared Display",
        )

        assertNotEquals(first.id, second.id)
        assertEquals("Shared Display", first.display)
        assertEquals("Shared Display", second.display)
    }

    @Test
    fun `codeable concept creation rejects empty coding lists`() {
        assertFailsWith<IllegalArgumentException> {
            codeableConceptRepository.create(
                text = "Text without coding is not allowed in Slice 1.6",
                bindingContext = BindingContext("Condition.code"),
                codingIds = emptyList(),
                primaryCodingId = null,
            )
        }
    }

    @Test
    fun `codeable concept creation requires the primary coding to be present`() {
        val suffix = UUID.randomUUID()
        val included = codingRepository.create(
            system = CanonicalCodeSystems.SNOMED_CT,
            code = "73211009-$suffix",
            display = "Diabetes mellitus",
        )
        val missingPrimary = codingRepository.create(
            system = CanonicalCodeSystems.ICD_10_CM,
            code = "E11.9-$suffix",
            display = "Type 2 diabetes mellitus without complications",
        )

        assertFailsWith<IllegalArgumentException> {
            codeableConceptRepository.create(
                text = "Diabetes mellitus",
                bindingContext = BindingContext("Condition.code"),
                codingIds = listOf(included.id),
                primaryCodingId = missingPrimary.id,
            )
        }
    }
}

package dev.ehr.integrity

import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

class PatientScopedReferenceSchemaMigrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `patient scoped clinical references must belong to the same patient at the database layer`() {
        val organizationId = insertOrganization()
        val patientA = insertPatient(organizationId)
        val patientB = insertPatient(organizationId)
        val conceptId = insertConcept()
        val encounterA = insertEncounter(organizationId, patientA, conceptId)
        val orderA = insertOrder(organizationId, patientA, conceptId)
        val observationA = insertObservation(organizationId, patientA, conceptId)
        val observationB = insertObservation(organizationId, patientB, conceptId)
        val reportA = insertDiagnosticReport(organizationId, patientA, orderA, conceptId)

        assertFailsWith<DataAccessException> {
            insertCondition(organizationId, patientB, conceptId, encounterA)
        }
        assertFailsWith<DataAccessException> {
            insertAllergy(organizationId, patientB, conceptId, encounterA)
        }
        assertFailsWith<DataAccessException> {
            insertObservation(organizationId, patientB, conceptId, encounterA)
        }
        assertFailsWith<DataAccessException> {
            insertMedicationStatement(organizationId, patientB, conceptId, encounterA)
        }
        assertFailsWith<DataAccessException> {
            insertOrder(organizationId, patientB, conceptId, encounterA)
        }
        assertFailsWith<DataAccessException> {
            insertClinicalNote(organizationId, patientB, encounterA, conceptId)
        }
        assertFailsWith<DataAccessException> {
            insertDiagnosticReport(organizationId, patientB, orderA, conceptId)
        }
        assertFailsWith<DataAccessException> {
            insertDiagnosticReport(organizationId, patientB, orderA, conceptId, encounterA)
        }
        assertFailsWith<DataAccessException> {
            insertDiagnosticReportResult(organizationId, reportA, observationB)
        }

        insertDiagnosticReportResult(organizationId, reportA, observationA)
    }

    private fun insertOrganization(): UUID =
        jdbcTemplate.queryForObject(
            "insert into organizations (slug, display_name) values (?, ?) returning id",
            UUID::class.java,
            "integrity-schema-org-${UUID.randomUUID()}",
            "Integrity Schema Org",
        )!!

    private fun insertPatient(organizationId: UUID): UUID =
        jdbcTemplate.queryForObject(
            """
            insert into patients (organization_id, given_name, family_name)
            values (?, 'Synthetic', 'Patient')
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
        )!!

    private fun insertConcept(): UUID {
        val codingId = jdbcTemplate.queryForObject(
            """
            insert into codings (system, code, display)
            values ('http://loinc.org', ?, 'Integrity concept')
            returning id
            """.trimIndent(),
            UUID::class.java,
            "INTEGRITY-${UUID.randomUUID()}",
        )!!
        return jdbcTemplate.queryForObject(
            """
            insert into codeable_concepts (text, primary_coding_id)
            values ('Integrity concept', ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            codingId,
        )!!
    }

    private fun insertEncounter(
        organizationId: UUID,
        patientId: UUID,
        classConceptId: UUID,
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            insert into encounters (organization_id, patient_id, status, class_concept_id, period_start)
            values (?, ?, 'in-progress', ?, ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            patientId,
            classConceptId,
            Timestamp.from(Instant.parse("2026-06-01T09:00:00Z")),
        )!!

    private fun insertCondition(
        organizationId: UUID,
        patientId: UUID,
        conceptId: UUID,
        encounterId: UUID,
    ) {
        jdbcTemplate.update(
            """
            insert into conditions (organization_id, patient_id, encounter_id, code_concept_id)
            values (?, ?, ?, ?)
            """.trimIndent(),
            organizationId,
            patientId,
            encounterId,
            conceptId,
        )
    }

    private fun insertAllergy(
        organizationId: UUID,
        patientId: UUID,
        conceptId: UUID,
        encounterId: UUID,
    ) {
        jdbcTemplate.update(
            """
            insert into allergies (organization_id, patient_id, encounter_id, code_concept_id)
            values (?, ?, ?, ?)
            """.trimIndent(),
            organizationId,
            patientId,
            encounterId,
            conceptId,
        )
    }

    private fun insertObservation(
        organizationId: UUID,
        patientId: UUID,
        conceptId: UUID,
        encounterId: UUID? = null,
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            insert into observations (
              organization_id, patient_id, encounter_id, category, code_concept_id,
              value_quantity, value_quantity_unit, effective_at
            )
            values (?, ?, ?, 'laboratory', ?, ?, 'mmol/L', ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            patientId,
            encounterId,
            conceptId,
            BigDecimal("4.5"),
            Timestamp.from(Instant.parse("2026-06-01T09:30:00Z")),
        )!!

    private fun insertMedicationStatement(
        organizationId: UUID,
        patientId: UUID,
        conceptId: UUID,
        encounterId: UUID,
    ) {
        jdbcTemplate.update(
            """
            insert into medication_statements (
              organization_id, patient_id, encounter_id, medication_concept_id
            )
            values (?, ?, ?, ?)
            """.trimIndent(),
            organizationId,
            patientId,
            encounterId,
            conceptId,
        )
    }

    private fun insertOrder(
        organizationId: UUID,
        patientId: UUID,
        conceptId: UUID,
        encounterId: UUID? = null,
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            insert into orders (organization_id, patient_id, encounter_id, code_concept_id)
            values (?, ?, ?, ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            patientId,
            encounterId,
            conceptId,
        )!!

    private fun insertClinicalNote(
        organizationId: UUID,
        patientId: UUID,
        encounterId: UUID,
        typeConceptId: UUID,
    ) {
        jdbcTemplate.update(
            """
            insert into clinical_notes (
              organization_id, patient_id, encounter_id, type_concept_id, title, content_text
            )
            values (?, ?, ?, ?, 'Synthetic note', 'Synthetic note body')
            """.trimIndent(),
            organizationId,
            patientId,
            encounterId,
            typeConceptId,
        )
    }

    private fun insertDiagnosticReport(
        organizationId: UUID,
        patientId: UUID,
        orderId: UUID,
        codeConceptId: UUID,
        encounterId: UUID? = null,
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            insert into diagnostic_reports (
              organization_id, patient_id, encounter_id, order_id, code_concept_id
            )
            values (?, ?, ?, ?, ?)
            returning id
            """.trimIndent(),
            UUID::class.java,
            organizationId,
            patientId,
            encounterId,
            orderId,
            codeConceptId,
        )!!

    private fun insertDiagnosticReportResult(
        organizationId: UUID,
        reportId: UUID,
        observationId: UUID,
    ) {
        jdbcTemplate.update(
            """
            insert into diagnostic_report_results (
              diagnostic_report_id, organization_id, observation_id, ordinal
            )
            values (?, ?, ?, 0)
            """.trimIndent(),
            reportId,
            organizationId,
            observationId,
        )
    }
}

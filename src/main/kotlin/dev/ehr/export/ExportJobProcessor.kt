package dev.ehr.export

import ca.uhn.fhir.context.FhirContext
import dev.ehr.allergy.AllergyRepository
import dev.ehr.condition.ConditionRepository
import dev.ehr.diagnostics.DiagnosticReportRepository
import dev.ehr.encounter.EncounterRepository
import dev.ehr.fhir.AllergyFhirMapper
import dev.ehr.fhir.ConditionFhirMapper
import dev.ehr.fhir.DiagnosticReportFhirMapper
import dev.ehr.fhir.DocumentReferenceFhirMapper
import dev.ehr.fhir.EncounterFhirMapper
import dev.ehr.fhir.MedicationStatementFhirMapper
import dev.ehr.fhir.ObservationFhirMapper
import dev.ehr.fhir.PatientFhirMapper
import dev.ehr.fhir.ProvenanceFhirMapper
import dev.ehr.identity.TenantScope
import dev.ehr.medication.MedicationStatementRepository
import dev.ehr.note.ClinicalNoteRepository
import dev.ehr.observation.ObservationRepository
import dev.ehr.observation.ObservationValue
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.patient.PatientWithIdentifiers
import dev.ehr.provenance.ProvenanceRepository
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptId
import dev.ehr.terminology.CodeableConceptRepository
import org.hl7.fhir.instance.model.api.IBaseResource
import dev.ehr.runtime.EhrProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Component
class ExportJobProcessor(
    private val exportJobRepository: ExportJobRepository,
    private val auditEventService: AuditEventService,
    private val jdbcTemplate: JdbcTemplate,
    private val patientRepository: PatientRepository,
    private val encounterRepository: EncounterRepository,
    private val conditionRepository: ConditionRepository,
    private val allergyRepository: AllergyRepository,
    private val observationRepository: ObservationRepository,
    private val medicationStatementRepository: MedicationStatementRepository,
    private val clinicalNoteRepository: ClinicalNoteRepository,
    private val diagnosticReportRepository: DiagnosticReportRepository,
    private val provenanceRepository: ProvenanceRepository,
    private val codeableConceptRepository: CodeableConceptRepository,
    private val patientFhirMapper: PatientFhirMapper,
    private val encounterFhirMapper: EncounterFhirMapper,
    private val conditionFhirMapper: ConditionFhirMapper,
    private val allergyFhirMapper: AllergyFhirMapper,
    private val observationFhirMapper: ObservationFhirMapper,
    private val medicationStatementFhirMapper: MedicationStatementFhirMapper,
    private val documentReferenceFhirMapper: DocumentReferenceFhirMapper,
    private val diagnosticReportFhirMapper: DiagnosticReportFhirMapper,
    private val provenanceFhirMapper: ProvenanceFhirMapper,
    private val fhirContext: FhirContext,
    private val properties: EhrProperties,
) {
    @Async("exportTaskExecutor")
    fun processAsync(job: ExportJob) {
        process(job)
    }

    fun process(job: ExportJob) {
        val scope = TenantScope(job.organizationId)
        try {
            exportJobRepository.markInProgress(job.id)

            val conceptCache = mutableMapOf<CodeableConceptId, CodeableConcept>()
            fun concept(id: CodeableConceptId): CodeableConcept =
                conceptCache.getOrPut(id) {
                    codeableConceptRepository.findById(id)
                        ?: throw IllegalStateException("referenced concept is missing")
                }

            val patientIds = jdbcTemplate.queryForList(
                "select id from patients where organization_id = ? order by created_at, id",
                UUID::class.java,
                job.organizationId.value,
            ).map(::PatientId)

            val byType = linkedMapOf<String, MutableList<IBaseResource>>()
            fun add(type: String, resource: IBaseResource) {
                byType.getOrPut(type) { mutableListOf() }.add(resource)
            }
            // Every served type gets a file, even when empty.
            listOf(
                "Patient", "Encounter", "Condition", "AllergyIntolerance", "Observation",
                "MedicationStatement", "DocumentReference", "DiagnosticReport", "Provenance",
            ).forEach { byType[it] = mutableListOf() }

            patientIds.forEach { patientId ->
                val patient = patientRepository.findById(scope, patientId) ?: return@forEach
                val identifiers = patientRepository.findIdentifiers(scope, patientId)
                add("Patient", patientFhirMapper.toFhirPatient(PatientWithIdentifiers(patient, identifiers)))

                encounterRepository.findByPatient(scope, patientId).forEach { encounter ->
                    add("Encounter", encounterFhirMapper.toFhirEncounter(encounter, concept(encounter.classConceptId)))
                }
                conditionRepository.findByPatient(scope, patientId).forEach { condition ->
                    add("Condition", conditionFhirMapper.toFhirCondition(condition, concept(condition.codeConceptId)))
                }
                allergyRepository.findByPatient(scope, patientId).forEach { allergy ->
                    add(
                        "AllergyIntolerance",
                        allergyFhirMapper.toFhirAllergyIntolerance(allergy, concept(allergy.codeConceptId)),
                    )
                }
                observationRepository.findByPatient(scope, patientId).forEach { observation ->
                    val valueConcept = (observation.value as? ObservationValue.Coded)
                        ?.let { concept(it.conceptId) }
                    add(
                        "Observation",
                        observationFhirMapper.toFhirObservation(
                            observation,
                            concept(observation.codeConceptId),
                            valueConcept,
                        ),
                    )
                }
                medicationStatementRepository.findByPatient(scope, patientId).forEach { statement ->
                    add(
                        "MedicationStatement",
                        medicationStatementFhirMapper.toFhirMedicationStatement(
                            statement,
                            concept(statement.medicationConceptId),
                        ),
                    )
                }
                clinicalNoteRepository.findByPatient(scope, patientId).forEach { note ->
                    add(
                        "DocumentReference",
                        documentReferenceFhirMapper.toFhirDocumentReference(note, concept(note.typeConceptId)),
                    )
                }
                diagnosticReportRepository.findByPatient(scope, patientId).forEach { report ->
                    add(
                        "DiagnosticReport",
                        diagnosticReportFhirMapper.toFhirDiagnosticReport(report, concept(report.codeConceptId)),
                    )
                }
                provenanceRepository.findByPatient(scope, patientId.value).forEach { event ->
                    add("Provenance", provenanceFhirMapper.toFhirProvenance(event))
                }
            }

            val jobDir = jobDirectory(job.id)
            Files.createDirectories(jobDir)
            val parser = fhirContext.newJsonParser()
            byType.forEach { (type, resources) ->
                val filePath = jobDir.resolve("$type.ndjson")
                Files.newBufferedWriter(filePath).use { writer ->
                    resources.forEach { resource ->
                        writer.write(parser.encodeResourceToString(resource))
                        writer.newLine()
                    }
                }
                val fileRecord = exportJobRepository.addFile(
                    organizationId = job.organizationId,
                    jobId = job.id,
                    resourceType = type,
                    resourceCount = resources.size,
                    storagePath = filePath.toString(),
                )
                auditEventService.recordBackgroundEvent(
                    organizationId = job.organizationId,
                    subjectUserId = job.requestedBy,
                    resourceType = "EXPORT_FILE",
                    operation = AuditOperation.SYSTEM,
                    outcome = AuditOutcome.SUCCESS,
                    resourceId = fileRecord.id,
                    // Async-produced event: keyed by requester + file, not the
                    // kickoff request's correlation.
                    correlationId = null,
                )
            }

            exportJobRepository.markCompleted(job.id)
        } catch (exception: Exception) {
            log.warn("export job {} failed: {}", job.id, exception.javaClass.simpleName)
            exportJobRepository.markFailed(
                jobId = job.id,
                errorMessage = "export processing failed (${exception.javaClass.simpleName})",
            )
            auditEventService.recordBackgroundEvent(
                organizationId = job.organizationId,
                subjectUserId = job.requestedBy,
                resourceType = "EXPORT_JOB",
                operation = AuditOperation.SYSTEM,
                outcome = AuditOutcome.FAILURE,
                resourceId = job.id,
                // Async-produced event: keyed by requester + job, not the
                // kickoff request's correlation.
                correlationId = null,
            )
        }
    }

    fun jobDirectory(jobId: UUID): Path = Paths.get(properties.export.storageDir, jobId.toString())

    private companion object {
        val log = LoggerFactory.getLogger(ExportJobProcessor::class.java)!!
    }
}

package dev.ehr.diagnostics

import dev.ehr.encounter.EncounterId
import dev.ehr.observation.ObservationId
import dev.ehr.order.OrderId
import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
import dev.ehr.terminology.CodeableConceptId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class DiagnosticReportController(
    private val diagnosticReportService: DiagnosticReportService,
) {
    @PostMapping("/orders/{orderId}/results")
    @ResponseStatus(HttpStatus.CREATED)
    fun attachResult(
        authentication: Authentication,
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: AttachResultRequest,
    ): DiagnosticReportResponse =
        diagnosticReportService.attachResult(
            principal = securityPrincipal(authentication),
            orderId = OrderId(orderId),
            codeConceptId = CodeableConceptId(request.codeConceptId!!),
            resultObservationIds = request.resultObservationIds.map(::ObservationId),
            conclusionText = request.conclusionText,
            encounterId = request.encounterId?.let(::EncounterId),
        ).toResponse()

    @GetMapping("/diagnostic-reports/{reportId}")
    fun get(
        authentication: Authentication,
        @PathVariable reportId: UUID,
    ): DiagnosticReportResponse =
        diagnosticReportService.get(
            principal = securityPrincipal(authentication),
            reportId = DiagnosticReportId(reportId),
        ).toResponse()

    @GetMapping("/patients/{patientId}/diagnostic-reports")
    fun listForPatient(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): DiagnosticReportListResponse =
        DiagnosticReportListResponse(
            diagnosticReports = diagnosticReportService.listForPatient(
                principal = securityPrincipal(authentication),
                patientId = PatientId(patientId),
            ).map { it.toResponse() },
        )

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class AttachResultRequest(
    @field:NotNull
    val codeConceptId: UUID?,
    @field:NotEmpty
    val resultObservationIds: List<UUID> = emptyList(),
    val conclusionText: String? = null,
    val encounterId: UUID? = null,
)

data class DiagnosticReportResponse(
    val id: String,
    val organizationId: String,
    val patientId: String,
    val encounterId: String?,
    val orderId: String,
    val status: String,
    val codeConceptId: String,
    val conclusionText: String?,
    val issuedAt: Instant,
    val resultObservationIds: List<String>,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DiagnosticReportListResponse(
    val diagnosticReports: List<DiagnosticReportResponse>,
)

fun DiagnosticReport.toResponse(): DiagnosticReportResponse =
    DiagnosticReportResponse(
        id = id.value.toString(),
        organizationId = organizationId.value.toString(),
        patientId = patientId.value.toString(),
        encounterId = encounterId?.value?.toString(),
        orderId = orderId.value.toString(),
        status = status.dbValue,
        codeConceptId = codeConceptId.value.toString(),
        conclusionText = conclusionText,
        issuedAt = issuedAt,
        resultObservationIds = resultObservationIds.map { it.value.toString() },
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

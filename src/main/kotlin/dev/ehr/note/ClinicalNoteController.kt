package dev.ehr.note

import dev.ehr.encounter.EncounterId
import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
import dev.ehr.terminology.CodeableConceptId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
class ClinicalNoteController(
    private val clinicalNoteService: ClinicalNoteService,
) {
    @PostMapping("/encounters/{encounterId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    fun write(
        authentication: Authentication,
        @PathVariable encounterId: UUID,
        @Valid @RequestBody request: WriteNoteRequest,
    ): ClinicalNoteResponse =
        clinicalNoteService.write(
            principal = securityPrincipal(authentication),
            encounterId = EncounterId(encounterId),
            typeConceptId = CodeableConceptId(request.typeConceptId!!),
            title = request.title,
            contentText = request.contentText,
        ).toResponse()

    @PatchMapping("/notes/{noteId}")
    fun amend(
        authentication: Authentication,
        @PathVariable noteId: UUID,
        @Valid @RequestBody request: AmendNoteRequest,
    ): ClinicalNoteResponse =
        clinicalNoteService.amend(
            principal = securityPrincipal(authentication),
            noteId = ClinicalNoteId(noteId),
            title = request.title,
            contentText = request.contentText,
            expectedVersion = request.expectedVersion!!,
        ).toResponse()

    @GetMapping("/notes/{noteId}")
    fun get(
        authentication: Authentication,
        @PathVariable noteId: UUID,
    ): ClinicalNoteResponse =
        clinicalNoteService.get(
            principal = securityPrincipal(authentication),
            noteId = ClinicalNoteId(noteId),
        ).toResponse()

    @GetMapping("/patients/{patientId}/notes")
    fun listForPatient(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): ClinicalNoteListResponse =
        ClinicalNoteListResponse(
            notes = clinicalNoteService.listForPatient(
                principal = securityPrincipal(authentication),
                patientId = PatientId(patientId),
            ).map { it.toResponse() },
        )

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class WriteNoteRequest(
    @field:NotNull
    val typeConceptId: UUID?,
    @field:NotBlank
    val title: String,
    @field:NotBlank
    val contentText: String,
)

data class AmendNoteRequest(
    @field:NotNull
    val expectedVersion: Int?,
    val title: String? = null,
    val contentText: String? = null,
)

data class ClinicalNoteResponse(
    val id: String,
    val organizationId: String,
    val patientId: String,
    val encounterId: String,
    val status: String,
    val typeConceptId: String,
    val title: String,
    val contentText: String,
    val authoredAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ClinicalNoteListResponse(
    val notes: List<ClinicalNoteResponse>,
)

fun ClinicalNote.toResponse(): ClinicalNoteResponse =
    ClinicalNoteResponse(
        id = id.value.toString(),
        organizationId = organizationId.value.toString(),
        patientId = patientId.value.toString(),
        encounterId = encounterId.value.toString(),
        status = status.dbValue,
        typeConceptId = typeConceptId.value.toString(),
        title = title,
        contentText = contentText,
        authoredAt = authoredAt,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

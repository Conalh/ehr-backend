package dev.ehr.terminology

import java.time.Instant

data class CanonicalCodeSystem(
    val id: CodeSystemId,
    val canonicalUri: String,
    val name: String,
    val publisher: String?,
    val licenseNote: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Coding(
    val id: CodingId,
    val codeSystemVersionId: CodeSystemVersionId?,
    val system: String,
    val version: String?,
    val code: String,
    val display: String?,
    val userSelected: Boolean,
    val createdAt: Instant,
)

data class CodeableConcept(
    val id: CodeableConceptId,
    val text: String?,
    val bindingContext: BindingContext?,
    val primaryCoding: Coding,
    val codings: List<Coding>,
    val createdAt: Instant,
)

@JvmInline
value class BindingContext(val value: String) {
    init {
        require(value.isNotBlank()) { "binding context must not be blank" }
    }
}

package dev.ehr.terminology

import java.util.UUID

@JvmInline
value class CodeSystemId(val value: UUID)

@JvmInline
value class CodeSystemVersionId(val value: UUID)

@JvmInline
value class CodingId(val value: UUID)

@JvmInline
value class CodeableConceptId(val value: UUID)

@JvmInline
value class ValueSetId(val value: UUID)

@JvmInline
value class ValueSetVersionId(val value: UUID)

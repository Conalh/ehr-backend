package dev.ehr.patient

import java.util.UUID

@JvmInline
value class PatientId(val value: UUID)

@JvmInline
value class PatientIdentifierId(val value: UUID)

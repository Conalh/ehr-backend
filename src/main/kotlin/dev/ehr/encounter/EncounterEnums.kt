package dev.ehr.encounter

enum class EncounterStatus(val dbValue: String) {
    PLANNED("planned"),
    IN_PROGRESS("in-progress"),
    FINISHED("finished"),
    CANCELLED("cancelled"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    fun canTransitionTo(target: EncounterStatus): Boolean =
        target in allowedTransitions.getValue(this)

    companion object {
        fun fromDb(dbValue: String): EncounterStatus =
            entries.first { it.dbValue == dbValue }

        private val allowedTransitions: Map<EncounterStatus, Set<EncounterStatus>> = mapOf(
            PLANNED to setOf(IN_PROGRESS, CANCELLED, ENTERED_IN_ERROR),
            IN_PROGRESS to setOf(FINISHED, ENTERED_IN_ERROR),
            FINISHED to setOf(ENTERED_IN_ERROR),
            CANCELLED to setOf(ENTERED_IN_ERROR),
            ENTERED_IN_ERROR to emptySet(),
        )
    }
}

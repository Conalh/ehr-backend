package dev.ehr.medication

enum class MedicationStatementStatus(val dbValue: String) {
    ACTIVE("active"),
    COMPLETED("completed"),
    STOPPED("stopped"),
    ON_HOLD("on-hold"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    companion object {
        fun fromDb(dbValue: String): MedicationStatementStatus =
            entries.first { it.dbValue == dbValue }
    }
}

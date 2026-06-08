package dev.ehr.patient

enum class PatientStatus(val dbValue: String) {
    ACTIVE("active"),
    INACTIVE("inactive"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    companion object {
        fun fromDb(dbValue: String): PatientStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class PatientAdministrativeGender(val dbValue: String) {
    MALE("male"),
    FEMALE("female"),
    OTHER("other"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromDb(dbValue: String): PatientAdministrativeGender =
            entries.first { it.dbValue == dbValue }
    }
}

enum class IdentifierUse(val dbValue: String) {
    USUAL("usual"),
    OFFICIAL("official"),
    TEMP("temp"),
    SECONDARY("secondary"),
    OLD("old"),
    ;

    companion object {
        fun fromDb(dbValue: String): IdentifierUse =
            entries.first { it.dbValue == dbValue }
    }
}

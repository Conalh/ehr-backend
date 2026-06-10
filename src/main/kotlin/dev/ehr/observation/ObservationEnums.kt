package dev.ehr.observation

enum class ObservationStatus(val dbValue: String) {
    PRELIMINARY("preliminary"),
    FINAL("final"),
    AMENDED("amended"),
    CANCELLED("cancelled"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    companion object {
        fun fromDb(dbValue: String): ObservationStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class ObservationCategory(val dbValue: String) {
    VITAL_SIGNS("vital-signs"),
    LABORATORY("laboratory"),
    ;

    companion object {
        fun fromDb(dbValue: String): ObservationCategory =
            entries.first { it.dbValue == dbValue }
    }
}

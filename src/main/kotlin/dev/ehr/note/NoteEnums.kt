package dev.ehr.note

enum class ClinicalNoteStatus(val dbValue: String) {
    CURRENT("current"),
    SUPERSEDED("superseded"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    companion object {
        fun fromDb(dbValue: String): ClinicalNoteStatus =
            entries.first { it.dbValue == dbValue }
    }
}

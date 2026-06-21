package dev.ehr.fhir

import dev.ehr.careteam.CareTeamMembership
import dev.ehr.identity.User
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import org.hl7.fhir.r4.model.CareTeam
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Period
import org.hl7.fhir.r4.model.Reference
import org.springframework.stereotype.Component
import java.util.Date

/**
 * One CareTeam per patient compartment: the active memberships are the
 * participants. Members are referenced by user identifier, matching the
 * Provenance agent convention.
 */
@Component
class CareTeamFhirMapper {
    fun toFhirCareTeam(
        patientId: PatientId,
        memberships: List<CareTeamMembership>,
        usersById: Map<UserId, User>,
    ): CareTeam {
        val team = CareTeam()
        team.id = patientId.value.toString()
        // us-core-careteam requires participant 1..*: a participant-less team
        // is valid here, so only participant-bearing instances claim it.
        if (memberships.isNotEmpty()) {
            team.meta.addProfile(US_CORE_CARETEAM_PROFILE)
        }
        team.status = CareTeam.CareTeamStatus.ACTIVE
        team.subject = Reference("Patient/${patientId.value}")

        memberships.forEach { membership ->
            team.addParticipant(
                CareTeam.CareTeamParticipantComponent()
                    .addRole(
                        CodeableConcept()
                            .addCoding(
                                Coding()
                                    .setSystem(CARE_TEAM_ROLE_SYSTEM)
                                    .setCode(membership.role.dbValue),
                            )
                            .setText(membership.role.dbValue),
                    )
                    .setMember(
                        Reference()
                            .setIdentifier(
                                Identifier()
                                    .setSystem(ProvenanceFhirMapper.AGENT_USER_SYSTEM)
                                    .setValue(membership.userId.value.toString()),
                            )
                            .setDisplay(usersById[membership.userId]?.displayName),
                    )
                    .setPeriod(Period().setStart(Date.from(membership.periodStart))),
            )
        }
        return team
    }

    companion object {
        const val CARE_TEAM_ROLE_SYSTEM = "urn:ehr:care-team-role"
        const val US_CORE_CARETEAM_PROFILE =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-careteam"
    }
}

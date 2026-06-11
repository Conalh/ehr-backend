package dev.ehr.authz

import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/**
 * Token issuance is back-channel (no session), so the picker's selection
 * rides in the OAuth2Authorization: stamped as an attribute at
 * code-issuance time, which runs on the authorize request thread where the
 * session is available.
 */
class LaunchContextAuthorizationService(
    private val delegate: OAuth2AuthorizationService,
) : OAuth2AuthorizationService {
    override fun save(authorization: OAuth2Authorization) {
        delegate.save(stampLaunchContext(authorization))
    }

    override fun remove(authorization: OAuth2Authorization) = delegate.remove(authorization)

    override fun findById(id: String): OAuth2Authorization? = delegate.findById(id)

    override fun findByToken(
        token: String,
        tokenType: OAuth2TokenType?,
    ): OAuth2Authorization? = delegate.findByToken(token, tokenType)

    private fun stampLaunchContext(authorization: OAuth2Authorization): OAuth2Authorization {
        if (authorization.authorizationGrantType != AuthorizationGrantType.AUTHORIZATION_CODE) {
            return authorization
        }
        if (PatientLaunchSession.LAUNCH_PATIENT_SCOPE !in authorization.authorizedScopes) {
            return authorization
        }
        if (authorization.getAttribute<UUID>(LAUNCH_PATIENT_ATTRIBUTE) != null) {
            return authorization
        }
        val session = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
            ?.request?.getSession(false)
            ?: return authorization
        val selectedPatient = session.getAttribute(PatientLaunchSession.SELECTED_PATIENT) as? UUID
            ?: return authorization
        return OAuth2Authorization.from(authorization)
            .attribute(LAUNCH_PATIENT_ATTRIBUTE, selectedPatient)
            .build()
    }

    companion object {
        const val LAUNCH_PATIENT_ATTRIBUTE = "ehr_launch_patient"
    }
}

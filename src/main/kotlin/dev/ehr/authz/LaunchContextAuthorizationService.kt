package dev.ehr.authz

import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Token issuance is back-channel (no session), so the picker's selection
 * rides in the OAuth2Authorization: stamped as an attribute at
 * code-issuance time, which runs on the authorize request thread where the
 * session is available.
 *
 * Additionally tracks refresh-token chains so that reuse of a rotated
 * refresh token revokes the entire family (the currently valid token),
 * matching the authorization-server design. Spring AS keeps the same
 * authorization ID across rotations (OAuth2Authorization.from(existing)),
 * so a refreshToken→authorizationId map is sufficient to locate the
 * current authorization when a used token is presented again.
 */
class LaunchContextAuthorizationService(
    private val delegate: OAuth2AuthorizationService,
) : OAuth2AuthorizationService {
    private val refreshTokenToAuthId = ConcurrentHashMap<String, String>()

    override fun save(authorization: OAuth2Authorization) {
        val stamped = stampLaunchContext(authorization)
        stamped.refreshToken?.let { refreshToken ->
            refreshTokenToAuthId[refreshToken.token.tokenValue] = stamped.id
        }
        delegate.save(stamped)
    }

    override fun remove(authorization: OAuth2Authorization) {
        delegate.remove(authorization)
    }

    override fun findById(id: String): OAuth2Authorization? = delegate.findById(id)

    override fun findByToken(
        token: String,
        tokenType: OAuth2TokenType?,
    ): OAuth2Authorization? {
        val found = delegate.findByToken(token, tokenType)
        if (found == null && tokenType == OAuth2TokenType.REFRESH_TOKEN && refreshTokenToAuthId.containsKey(token)) {
            // Refresh-token reuse: the token was once valid (we recorded it)
            // but is no longer in the store. Revoke the family by removing
            // the current authorization in this chain.
            val authId = refreshTokenToAuthId[token]
            if (authId != null) {
                delegate.findById(authId)?.let { current ->
                    delegate.remove(current)
                }
                refreshTokenToAuthId.entries.removeIf { it.value == authId }
            }
        }
        return found
    }

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
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
            ?.request
            ?: return authorization
        val session = request.getSession(false)
        val transaction =
            request.getAttribute(PatientLaunchSession.ACTIVE_TRANSACTION_REQUEST_ATTRIBUTE) as? PatientLaunchTransaction
                ?: session?.let { PatientLaunchSession.pendingTransaction(it) }
                ?: return authorization
        val selection =
            request.getAttribute(PatientLaunchSession.ACTIVE_SELECTION_REQUEST_ATTRIBUTE) as? PatientLaunchSelection
                ?: session?.let { PatientLaunchSession.selectedTransaction(it) }
                ?: return authorization
        if (!selection.matches(transaction)) {
            session?.let { PatientLaunchSession.clear(it) }
            return authorization
        }
        val stamped = OAuth2Authorization.from(authorization)
            .attribute(LAUNCH_PATIENT_ATTRIBUTE, selection.patientId)
            .build()
        session?.let { PatientLaunchSession.clear(it) }
        return stamped
    }

    companion object {
        const val LAUNCH_PATIENT_ATTRIBUTE = "ehr_launch_patient"
    }
}

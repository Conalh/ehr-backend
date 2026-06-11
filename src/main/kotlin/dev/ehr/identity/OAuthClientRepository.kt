package dev.ehr.identity

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class OAuthClientRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(
        organizationId: OrganizationId,
        clientIdentifier: String,
        displayName: String,
        clientType: OAuthClientType = OAuthClientType.PUBLIC,
        secretHash: String? = null,
        grantedScopes: String = "",
        redirectUris: String = "",
    ): OAuthClient =
        jdbcTemplate.queryForObject(
            """
            insert into oauth_clients (
              organization_id, client_identifier, display_name, client_type, secret_hash, granted_scopes, redirect_uris
            )
            values (?, ?, ?, ?, ?, ?, ?)
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            organizationId.value,
            clientIdentifier,
            displayName,
            clientType.dbValue,
            secretHash,
            grantedScopes,
            redirectUris,
        )!!

    /** Identity-level lookup for token issuance and system-token resolution. */
    fun findByClientIdentifier(clientIdentifier: String): OAuthClient? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from oauth_clients
            where client_identifier = ?
            """.trimIndent(),
            rowMapper,
            clientIdentifier,
        ).singleOrNull()

    fun findById(
        tenantScope: TenantScope,
        clientId: OAuthClientId,
    ): OAuthClient? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from oauth_clients
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            clientId.value,
        ).singleOrNull()

    fun findByOrganization(tenantScope: TenantScope): List<OAuthClient> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from oauth_clients
            where organization_id = ?
            order by created_at, id
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
        )

    fun revoke(
        tenantScope: TenantScope,
        clientId: OAuthClientId,
    ): OAuthClient? =
        jdbcTemplate.query(
            """
            update oauth_clients
            set status = 'revoked',
                updated_at = now()
            where organization_id = ?
              and id = ?
              and status <> 'revoked'
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            clientId.value,
        ).singleOrNull()

    private companion object {
        const val COLUMNS = """
              id,
              organization_id,
              client_identifier,
              display_name,
              status,
              client_type,
              secret_hash,
              granted_scopes,
              redirect_uris,
              created_at,
              updated_at
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            OAuthClient(
                id = OAuthClientId(rs.getObject("id", UUID::class.java)),
                organizationId = rs.getObject("organization_id", UUID::class.java)?.let(::OrganizationId),
                clientIdentifier = rs.getString("client_identifier"),
                displayName = rs.getString("display_name"),
                status = OAuthClientStatus.fromDb(rs.getString("status")),
                clientType = OAuthClientType.fromDb(rs.getString("client_type")),
                secretHash = rs.getString("secret_hash"),
                grantedScopes = rs.getString("granted_scopes"),
                redirectUris = rs.getString("redirect_uris"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }
}

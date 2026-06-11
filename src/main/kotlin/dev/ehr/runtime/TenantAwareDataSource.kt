package dev.ehr.runtime

import dev.ehr.security.TenantContextHolder
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DelegatingDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * Wraps the pooled DataSource so every borrowed connection carries the
 * ehr.organization_id GUC — the predicate tenant RLS filters on. The GUC is
 * set unconditionally on every borrow (empty when no tenant context is
 * bound), so a pooled connection can never carry a previous request's tenant:
 * each checkout overwrites whatever the last user left behind. Borrowed
 * connections are always in a clean state (the pool rolls back returned
 * work), so the set_config cannot hit an aborted transaction.
 */
class TenantAwareDataSource(delegate: DataSource) : DelegatingDataSource(delegate) {
    override fun getConnection(): Connection = withTenantGuc(super.getConnection())

    override fun getConnection(
        username: String,
        password: String,
    ): Connection = withTenantGuc(super.getConnection(username, password))

    private fun withTenantGuc(connection: Connection): Connection {
        val organizationId = TenantContextHolder.currentOrganizationId()?.value?.toString() ?: ""
        connection.prepareStatement("select set_config('ehr.organization_id', ?, false)").use { statement ->
            statement.setString(1, organizationId)
            statement.execute()
        }
        return connection
    }
}

@Configuration
class TenantAwareDataSourceConfiguration {
    @Bean
    fun tenantAwareDataSourcePostProcessor(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is DataSource && bean !is TenantAwareDataSource) {
                    TenantAwareDataSource(bean)
                } else {
                    bean
                }
        }
}

package dev.ehr.export

import dev.ehr.identity.OrganizationId
import dev.ehr.security.TenantContextHolder
import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
class AsyncConfiguration {

    /**
     * Bounded executor for the bulk-export @Async path. Replaces Spring's
     * default SimpleAsyncTaskExecutor (unbounded, one thread per task, no
     * context propagation). The TaskDecorator copies the caller's tenant
     * GUC and MDC onto the worker thread and restores the worker's prior
     * state on completion, so RLS and correlation-id logging hold on the
     * async path and pool threads never leak a previous task's tenant.
     */
    @Bean
    fun exportTaskExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 8
            queueCapacity = 32
            setThreadNamePrefix("export-")
            setTaskDecorator(TenantContextTaskDecorator())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            initialize()
        }
}

private class TenantContextTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val tenantId = TenantContextHolder.currentOrganizationId()
        val mdcContext = MDC.getCopyOfContextMap()
        return Runnable {
            val previousTenant = TenantContextHolder.currentOrganizationId()
            val previousMdc = MDC.getCopyOfContextMap()
            try {
                if (tenantId != null) {
                    TenantContextHolder.set(tenantId)
                } else {
                    TenantContextHolder.clear()
                }
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext)
                } else {
                    MDC.clear()
                }
                runnable.run()
            } finally {
                restoreTenant(previousTenant)
                restoreMdc(previousMdc)
            }
        }
    }

    private fun restoreTenant(previous: OrganizationId?) {
        if (previous != null) {
            TenantContextHolder.set(previous)
        } else {
            TenantContextHolder.clear()
        }
    }

    private fun restoreMdc(previous: Map<String, String>?) {
        if (previous != null) {
            MDC.setContextMap(previous)
        } else {
            MDC.clear()
        }
    }
}

package dev.ehr.export

import org.springframework.stereotype.Component

fun interface ExportJobDispatcher {
    fun dispatch(job: ExportJob)
}

@Component
class AsyncExportJobDispatcher(
    private val exportJobProcessor: ExportJobProcessor,
) : ExportJobDispatcher {
    override fun dispatch(job: ExportJob) {
        exportJobProcessor.processAsync(job)
    }
}

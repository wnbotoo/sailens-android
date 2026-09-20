package com.sailens.guidance.trace

import android.content.Context
import com.sailens.guidance.model.trace.TraceSessionDescriptor
import com.sailens.guidance.service.TraceReplayService
import java.io.File

class FileTraceReplayService(
    private val context: Context,
) : TraceReplayService {
    override fun listSessions(): List<TraceSessionDescriptor> {
        return traceDirectory()
            .listFiles { file -> file.isFile && file.name.startsWith("trace_") && file.extension == "jsonl" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                TraceSessionDescriptor(
                    sessionId = file.nameWithoutExtension.removePrefix("trace_"),
                    fileName = file.name,
                    lastModifiedAt = file.lastModified(),
                )
            }
            ?: emptyList()
    }

    override fun readSessionLines(sessionId: String): List<String> {
        val file = File(traceDirectory(), "trace_$sessionId.jsonl")
        if (!file.exists() || !file.isFile) return emptyList()
        return file.readLines()
    }

    private fun traceDirectory(): File = File(context.filesDir, "traces")
}


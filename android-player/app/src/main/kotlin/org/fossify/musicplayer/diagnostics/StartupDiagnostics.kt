/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import org.fossify.musicplayer.BuildConfig
import org.fossify.musicplayer.R
import org.fossify.musicplayer.helpers.AppLanguage
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded local diagnostics with manual report export. */
object StartupDiagnostics {
    private const val REPORT_LIMIT = 64 * 1024
    private const val TRACE_LIMIT = 1024 * 1024
    private lateinit var directory: File
    private var installed = false
    private val recording = AtomicBoolean(false)
    private var startedAt = 0L
    private val stages = java.util.ArrayDeque<String>()
    private val playback = linkedMapOf<String, String>()
    @Volatile private var stage = "application startup"

    @Synchronized fun install(context: Context) {
        if (installed) return
        installed = true
        directory = File(context.noBackupFilesDir, "startup-diagnostics").apply { mkdirs() }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (recording.compareAndSet(false, true)) {
                runCatching { recordFailure(thread.name, error) }
            }
            // Keep Android's normal crash handling and exit classification intact.
            if (previousHandler != null) previousHandler.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
        runCatching { collectPreviousExit(context) }
        startedAt = System.currentTimeMillis()
        mark("application startup")
    }

    fun hasReport(): Boolean = ::directory.isInitialized && File(directory, "report.txt").isFile

    @Synchronized fun updatePlayback(key: String, value: String) {
        playback[key.take(64)] = value.take(2048)
    }

    @Synchronized fun recordPlaybackFailure(error: Throwable) {
        if (!::directory.isInitialized) return
        runCatching { writeAtomic("playback-error.txt", header() + playbackText() + "\n" + boundedStackTrace(error)) }
    }

    @Synchronized private fun playbackText(): String =
        AppLanguage.string(R.string.player_diagnostics_playback) + "\n" + playback.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    fun report(): String = startupReport() + "\n\n" + playbackText() +
        if (::directory.isInitialized && File(directory, "playback-error.txt").isFile)
            "\n\n" + AppLanguage.string(R.string.player_diagnostics_last_error) + "\n" + File(directory, "playback-error.txt").readText().take(REPORT_LIMIT)
        else ""

    private fun startupReport(): String = if (hasReport())
        File(directory, "report.txt").reader().use {
            val text = CharArray(REPORT_LIMIT)
            var count = 0
            while (count < text.size) {
                val read = it.read(text, count, text.size - count)
                if (read <= 0) break
                count += read
            }
            if (count > 0) String(text, 0, count) else ""
        }
        else header() + AppLanguage.string(R.string.player_diagnostics_no_error)

    @Synchronized fun mark(value: String) {
        stage = value.take(512)
        stages.addLast("${System.currentTimeMillis() - startedAt} ms: $stage")
        if (stages.size > 24) stages.removeFirst()
        if (!::directory.isInitialized) return
        runCatching {
            writeAtomic("session.json", JSONObject().put("pid", Process.myPid())
                .put("startedAt", startedAt).put("stage", stage).put("stages", JSONArray(stages.toList())).toString())
        }
    }

    @Synchronized fun recordFailure(threadName: String, error: Throwable) {
        if (hasReport()) return
        writeAtomic("report.txt", header() + AppLanguage.string(R.string.player_diagnostics_failure_context,
            threadName.take(256), stage, stages.joinToString("\n")) + "\n\n" +
            boundedStackTrace(error))
    }

    @Synchronized fun clearReport() {
        File(directory, "report.txt").delete()
        File(directory, "system-trace.bin").delete()
        recording.set(false)
        mark("retry requested")
    }

    /** Run off the UI thread. The output is chosen by the user through Android's file picker. */
    fun export(output: OutputStream) {
        // Never hold the diagnostics lock while a document provider performs external I/O.
        val (text, trace) = synchronized(this) {
            val traceFile = File(directory, "system-trace.bin")
            val traceBytes = if (traceFile.isFile && traceFile.length() <= TRACE_LIMIT) traceFile.readBytes() else null
            report().toByteArray(Charsets.UTF_8) to traceBytes
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("report.txt"))
            zip.write(text)
            zip.closeEntry()
            if (trace != null) {
                zip.putNextEntry(ZipEntry("android-system-trace.bin"))
                zip.write(trace)
                zip.closeEntry()
            }
        }
    }

    private fun header(): String = AppLanguage.string(R.string.player_diagnostics_header,
        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID,
        Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
        Build.SUPPORTED_ABIS.joinToString(), java.util.Date().toString()) + "\n"

    private fun boundedStackTrace(error: Throwable): String = buildString {
        var current: Throwable? = error
        repeat(8) {
            val cause = current ?: return@buildString
            if (it > 0) append("Caused by: ")
            append(cause.javaClass.name).append(": ").append(cause.message?.take(2048)).append('\n')
            cause.stackTrace.take(48).forEach { frame -> append("    at ").append(frame.toString().take(512)).append('\n') }
            current = cause.cause.takeUnless { next -> next === cause }
            if (length > REPORT_LIMIT / 2) { setLength(REPORT_LIMIT / 2); return@buildString }
        }
    }

    private fun writeAtomic(name: String, value: String) {
        val file = AtomicFile(File(directory, name))
        val output = file.startWrite()
        try {
            // A UTF-16 code unit takes at most three UTF-8 bytes. Leave room even for non-ASCII reports.
            output.write(value.take(REPORT_LIMIT / 4).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun collectPreviousExit(context: Context) {
        if (hasReport() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val session = File(directory, "session.json")
        // Also recover crashes from the previous APK on the first diagnostic launch.
        val previous = if (session.isFile) JSONObject(session.readText()) else null
        val since = previous?.optLong("startedAt") ?: (System.currentTimeMillis() - 24 * 60 * 60 * 1000)
        val manager = context.getSystemService(ActivityManager::class.java)
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        val exit = exits.firstOrNull {
            it.timestamp >= since && (previous == null || it.pid == previous.optInt("pid")) &&
                it.reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
        } ?: return
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> R.string.player_diagnostics_java_crash
            ApplicationExitInfo.REASON_CRASH_NATIVE -> R.string.player_diagnostics_native_crash
            ApplicationExitInfo.REASON_ANR -> R.string.player_diagnostics_anr
            else -> R.string.player_diagnostics_init_failure
        }
        writeAtomic("report.txt", header() + AppLanguage.string(R.string.player_diagnostics_previous_exit,
            AppLanguage.string(reason), java.util.Date(exit.timestamp).toString(), exit.status,
            previous?.optString("stage") ?: AppLanguage.string(R.string.player_diagnostics_previous_apk),
            previous?.optJSONArray("stages")?.toString() ?: AppLanguage.string(R.string.player_diagnostics_unavailable),
            exit.description.orEmpty()) + "\n\n" + AppLanguage.string(R.string.player_diagnostics_recovery_hint))
        runCatching {
            exit.traceInputStream?.use { input ->
                File(directory, "system-trace.bin").outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var remaining = TRACE_LIMIT
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
        }
    }
}

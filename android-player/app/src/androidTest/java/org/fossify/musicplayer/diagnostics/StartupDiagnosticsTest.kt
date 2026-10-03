/* Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only */

package org.fossify.musicplayer.diagnostics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Exercises local recovery storage without causing a crash or starting playback. */
@RunWith(AndroidJUnit4::class)
class StartupDiagnosticsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val reportFile get() = File(context.noBackupFilesDir, "startup-diagnostics/report.txt")

    private fun withEmptyDiagnostics(test: () -> Unit) {
        StartupDiagnostics.install(context)
        // A real startup failure is more important than this test: never clear a user's report.
        assumeFalse("A real pending report must be preserved", StartupDiagnostics.hasReport())
        try {
            test()
        } finally {
            StartupDiagnostics.clearReport()
        }
    }

    @Test fun preservesFirstFailureAndExportsReadableUtf8Report() = withEmptyDiagnostics {
        StartupDiagnostics.mark("test: разрешение получено")
        StartupDiagnostics.recordFailure("diagnostics-test-thread",
            IllegalStateException("Внешняя ошибка", IllegalArgumentException("Вложенная причина")))

        assertTrue(StartupDiagnostics.hasReport())
        val report = StartupDiagnostics.report()
        assertTrue(report.contains("diagnostics-test-thread"))
        assertTrue(report.contains("test: разрешение получено"))
        assertTrue(report.contains("Внешняя ошибка"))
        assertTrue(report.contains("Вложенная причина"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("IllegalArgumentException"))
        assertEquals("Report must persist outside the singleton's memory", report, reportFile.readText())

        StartupDiagnostics.mark("test: later stage")
        StartupDiagnostics.recordFailure("second-thread", RuntimeException("Do not replace the original"))
        StartupDiagnostics.install(context)
        assertEquals("Later failures and repeated install must preserve the first report", report,
            StartupDiagnostics.report())

        val output = ByteArrayOutputStream()
        StartupDiagnostics.export(output)
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertEquals("report.txt", zip.nextEntry?.name)
            val exported = ByteArrayOutputStream()
            zip.copyTo(exported)
            assertEquals(report, exported.toByteArray().toString(Charsets.UTF_8))
            zip.closeEntry()
            assertNull("A synthetic Java failure has no system trace", zip.nextEntry)
        }
        assertTrue("Saving a report must not dismiss recovery", StartupDiagnostics.hasReport())
        StartupDiagnostics.clearReport()
        assertFalse(StartupDiagnostics.hasReport())
        assertFalse(reportFile.exists())
    }

    @Test fun boundsLargeMultibyteThrowableWithoutLosingFailureIdentity() = withEmptyDiagnostics {
        StartupDiagnostics.mark("test: oversized throwable")
        val error = IllegalStateException("OVERSIZED-TEST: " + "Ошибка 🎵 ".repeat(20_000))
        error.stackTrace = Array(1024) { index ->
            // Long three-byte UTF-8 class names catch character-only size limits.
            StackTraceElement("音".repeat(600) + "SyntheticClass$index", "syntheticMethod", "SyntheticFile.kt", index + 1)
        }
        StartupDiagnostics.recordFailure("bounded-test", error)

        assertTrue(StartupDiagnostics.hasReport())
        assertTrue("The complete file must stay within 64 KiB, including UTF-8 headers",
            reportFile.length() in 1..(64L * 1024L))
        val report = StartupDiagnostics.report()
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("OVERSIZED-TEST"))
        assertTrue(report.contains("test: oversized throwable"))
        assertEquals("Reader must return the entire bounded report", reportFile.readText(), report)
        assertFalse("UTF-8 should not be truncated inside a code point", report.contains('\uFFFD'))
    }
}

/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.fossify.musicplayer.App
import org.fossify.musicplayer.R
import org.fossify.musicplayer.diagnostics.StartupDiagnostics

/** Uses only platform widgets so a broken player, theme or media service cannot hide the report. */
class StartupReportActivity : LocalizedActivity() {
    private var exporting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply { text = getString(R.string.player_diagnostics_title); textSize = 23f })
        root.addView(TextView(this).apply {
            text = getString(R.string.player_diagnostics_description)
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
        })
        val reportView = TextView(this).apply {
            text = StartupDiagnostics.report()
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(reportView) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = getString(R.string.player_diagnostics_copy)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("exstereo", StartupDiagnostics.report()))
                Toast.makeText(this@StartupReportActivity, R.string.player_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.player_diagnostics_save)
            setOnClickListener {
                if (!exporting) startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, if (StartupDiagnostics.hasReport()) "exstereo-startup-report.zip" else "exstereo-playback-report.zip")
                }, 1)
            }
        })
        root.addView(Button(this).apply {
            text = getString(if (StartupDiagnostics.hasReport()) R.string.player_diagnostics_retry else R.string.player_diagnostics_return)
            setOnClickListener {
                if (!StartupDiagnostics.hasReport()) {
                    finish()
                    return@setOnClickListener
                }
                if (!exporting) {
                    isEnabled = false
                    StartupDiagnostics.clearReport()
                    (application as App).startPlaybackController()
                    startActivity(Intent(this@StartupReportActivity, PlayerSplashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            }
        })
        setContentView(root)
    }

    @Deprecated("Platform activity result used to keep recovery independent of app libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 1 || resultCode != RESULT_OK || exporting) return
        exporting = true
        Thread({
            val result = runCatching {
                val output = contentResolver.openOutputStream(uri) ?: error(getString(R.string.player_file_open_error))
                StartupDiagnostics.export(output)
            }
            runOnUiThread {
                exporting = false
                if (!isDestroyed) Toast.makeText(this,
                    if (result.isSuccess) getString(R.string.player_diagnostics_saved) else getString(R.string.player_diagnostics_save_error, result.exceptionOrNull()?.message.orEmpty()),
                    Toast.LENGTH_LONG).show()
            }
        }, "startup-report-export").start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

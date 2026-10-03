/*
 * Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.fossify.musicplayer.activities

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.util.Linkify
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.fossify.musicplayer.BuildConfig
import org.fossify.musicplayer.R

internal fun Activity.showExstereoAbout() {
    val githubUrl = getString(R.string.exstereo_github_url).trim()
    val builder = AlertDialog.Builder(this)
        .setTitle(getString(R.string.spatial_app_name))
        .setIcon(R.mipmap.exstereo_launcher)
        .setMessage(getString(R.string.exstereo_about_summary, BuildConfig.VERSION_NAME))
        .setPositiveButton(R.string.exstereo_about_close, null)
        .setNeutralButton(R.string.exstereo_about_licenses) { _, _ -> showExstereoLicenses() }
    if (githubUrl.isNotEmpty()) {
        builder.setNegativeButton(R.string.exstereo_about_github) { _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, githubUrl, Toast.LENGTH_LONG).show()
            }
        }
    }
    builder.show()
}

private fun Activity.showExstereoLicenses() {
    val catalog = if (resources.configuration.locales[0].language == "ru") "catalog-ru.json" else "catalog.json"
    val entries = assets.open("licenses/$catalog").bufferedReader().use { JSONArray(it.readText()) }
    val titles = Array(entries.length()) { entries.getJSONObject(it).getString("title") }
    AlertDialog.Builder(this)
        .setTitle(R.string.exstereo_about_licenses)
        .setItems(titles) { _, index ->
            val entry = entries.getJSONObject(index)
            val files = entry.getJSONArray("files")
            val text = buildString {
                append(entry.getString("notice"))
                for (i in 0 until files.length()) {
                    append("\n\n")
                    assets.open("licenses/${files.getString(i)}").bufferedReader().use { append(it.readText()) }
                }
            }
            val padding = (20 * resources.displayMetrics.density).toInt()
            val content = TextView(this).apply {
                autoLinkMask = Linkify.WEB_URLS
                this.text = text
                textSize = 14f
                setPadding(padding, padding, padding, padding)
                setTextIsSelectable(true)
            }
            AlertDialog.Builder(this)
                .setTitle(titles[index])
                .setView(ScrollView(this).apply { addView(content) })
                .setPositiveButton(R.string.exstereo_about_close, null)
                .setNeutralButton(R.string.exstereo_about_license_list) { _, _ -> showExstereoLicenses() }
                .show()
        }
        .setPositiveButton(R.string.exstereo_about_close, null)
        .show()
}

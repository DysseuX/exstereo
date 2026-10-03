/* Copyright (c) 2026 DysseuX (DysseuX Group)
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */

package org.dysseux.hrtf

import android.content.Context

/** Keep background messages unresolved so a system-language change also updates old results. */
data class UiText(val resource: Int, val arguments: List<Any>) {
    constructor(resource: Int, vararg arguments: Any) : this(resource, arguments.toList())

    fun render(context: Context): String {
        val localized = SystemLanguage.context(context)
        val values = arguments.map { if (it is UiText) it.render(localized) else it }.toTypedArray()
        return localized.getString(resource, *values)
    }

    companion object {
        fun error(error: Throwable): UiText = when (error) {
            is LocalizedFailure -> error.text
            else -> UiText(R.string.error_unexpected)
        }
    }
}

class LocalizedFailure(val text: UiText) : IllegalArgumentException("UI resource ${text.resource}") {
    constructor(resource: Int, vararg arguments: Any) : this(UiText(resource, *arguments))
}

internal fun requireUi(condition: Boolean, resource: Int, vararg arguments: Any) {
    if (!condition) throw LocalizedFailure(resource, *arguments)
}

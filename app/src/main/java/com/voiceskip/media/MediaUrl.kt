// SPDX-License-Identifier: GPL-3.0-or-later
package com.voiceskip.media

import java.net.URI

/** Accept one public HTTP(S) link, including the title + link text shared by media apps. */
object MediaUrl {
    fun parse(text: String): String? {
        val candidates = Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE)
            .findAll(text.trim()).map { it.value.trimEnd('.', ',', ';', ')', ']') }.toList()
        if (candidates.size != 1) return null
        return candidates.single().takeIf { candidate ->
            runCatching {
                val uri = URI(candidate)
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                    uri.scheme.lowercase() in setOf("https", "http")
            }.getOrDefault(false)
        }
    }
}

package com.v2ray.ang.handler

import java.net.URLDecoder
import java.util.Locale

/**
 * ATS: a subscription link ("key") may carry a backup address in its fragment, in the
 * same format Happ uses, so one link works in both apps:
 *
 * `https://main:2096/path/<subId>#ATS?fallback-url=https%3A%2F%2Fbackup%3A2097%2Fpath%2F<subId>`
 *
 * The fragment is never sent to the server. When the main address does not answer, the
 * subscription is downloaded from the backup address instead.
 *
 * Kept separate from [SubscriptionInfoParser]: that one reads the panel's response headers,
 * this one reads the stored link itself. Pure functions only, so the logic is covered by
 * plain JVM tests.
 */
object SubscriptionKeyLink {
    const val FALLBACK_PARAM = "fallback-url"

    /**
     * @property primaryUrl the link without its fragment: the address that is downloaded first.
     * @property fallbackUrl the decoded backup address, or null when the link has none.
     * @property name the name part of the fragment (before `?`), or null when empty.
     */
    data class Parts(
        val primaryUrl: String,
        val fallbackUrl: String?,
        val name: String?,
    )

    fun parse(link: String): Parts {
        val trimmed = link.trim()
        val hash = trimmed.indexOf('#')
        if (hash < 0) return Parts(trimmed, null, null)

        val primary = trimmed.substring(0, hash)
        val fragment = trimmed.substring(hash + 1)
        val question = fragment.indexOf('?')
        val namePart = if (question >= 0) fragment.substring(0, question) else fragment
        val query = if (question >= 0) fragment.substring(question + 1) else ""

        val fallback = query.split('&').firstNotNullOfOrNull { param ->
            val eq = param.indexOf('=')
            if (eq > 0 && decode(param.substring(0, eq)) == FALLBACK_PARAM) decode(param.substring(eq + 1)) else null
        }?.trim()?.takeIf { isHttpLink(it) && it != primary }

        return Parts(primary, fallback, decode(namePart)?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** True when both links point to the same subscription, whatever their fragments say. */
    fun sameSubscription(a: String, b: String): Boolean = parse(a).primaryUrl == parse(b).primaryUrl

    private fun isHttpLink(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return (lower.startsWith("https://") || lower.startsWith("http://")) && value.length > lower.indexOf("//") + 2
    }

    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }
}

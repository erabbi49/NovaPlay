package com.novaplay.app.web

import com.novaplay.app.data.Prefs
import com.novaplay.app.data.Source
import java.net.URLEncoder

/**
 * URL rules for the configured site (xhamster by default). Pure string handling, so it also
 * runs in plain JVM tests.
 */
object Site {
    /** Replaced in tests; in the app it reads the user's base URL setting. */
    @Volatile
    var baseProvider: () -> String = { Prefs.baseUrl }

    val base: String
        get() {
            val b = baseProvider().trim().trimEnd('/')
            return if (b.startsWith("http://") || b.startsWith("https://")) b else Prefs.DEFAULT_BASE_URL
        }

    val host: String get() = hostOf(base)

    /** JS regex source for video page paths: /videos/<slug>-<id>, optionally behind a language prefix. */
    const val VIDEO_PATH_RE = "^(?:/[a-z]{2})?/videos/[^/]+-[0-9a-zA-Z]+$"
    private val videoPath = Regex(VIDEO_PATH_RE)

    fun home(): String = "$base/"
    fun newest(): String = "$base/newest"
    fun best(): String = "$base/best"
    fun hd(): String = "$base/hd"
    fun fourK(): String = "$base/4k"

    fun search(query: String): String {
        val enc = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return "$base/search/$enc"
    }

    /** The chips shown on Home before the user adds any. */
    fun defaultChips(): List<Source> = listOf(
        Source("Home", home()),
        Source("Newest", newest()),
        Source("Best", best()),
        Source("HD", hd()),
        Source("4K", fourK())
    )

    // ---------- URL helpers ----------

    fun hostOf(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return ""
        var rest = url.substring(i + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) rest = rest.substring(0, end)
        rest = rest.substringAfterLast('@')
        return rest.substringBefore(':').lowercase()
    }

    fun pathOf(url: String): String {
        val i = url.indexOf("://")
        val rest = if (i < 0) url else url.substring(i + 3)
        val slash = rest.indexOf('/')
        if (slash < 0) return "/"
        return rest.substring(slash).substringBefore('?').substringBefore('#')
    }

    /** Last two labels of a host: www.xhamster.com -> xhamster.com. */
    fun registrable(host: String): String {
        val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
        return if (parts.size <= 2) parts.joinToString(".") else parts.takeLast(2).joinToString(".")
    }

    /** The domains the site is known to be served from (main domain, regional/blocked-country mirrors). */
    private val knownMirrors =
        Regex("^(?:[^.]+\\.)*(?:xhamster\\d*\\.(?:com|one|desi)|xhms\\.pro|xhday\\.com|xhvid\\.com)$")

    /** True for http(s) URLs on this site: same registrable domain as the base, or any known mirror of it. */
    fun isSiteUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val h = hostOf(url)
        if (h.isEmpty()) return false
        if (registrable(h) == registrable(host)) return true
        return knownMirrors.matches(h) && knownMirrors.matches(host)
    }

    fun isVideoUrl(url: String): Boolean = isSiteUrl(url) && videoPath.matches(pathOf(url))

    /** Resolves protocol-relative and root-relative URLs against the site. */
    fun absolute(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> base + u
            else -> "$base/$u"
        }
    }

    /** Points a link from another mirror or language subdomain at the configured base host. */
    fun rebase(url: String): String {
        if (!isSiteUrl(url) || hostOf(url) == host) return url
        val afterScheme = url.substringAfter("://")
        val hostEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val tail = if (hostEnd < 0) "" else afterScheme.substring(hostEnd)
        return base.substringBefore("://") + "://" + host + tail
    }

    /** Turns what the user typed into a site URL when it is one (a pasted link), else null. */
    fun asUrl(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.contains(' ')) return null
        val candidate = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && t.contains('/') -> "https://$t"
            else -> return null
        }
        return if (isSiteUrl(candidate)) candidate else null
    }

    /** Chrome-like user agent derived from the WebView default (drops the "wv" marker). */
    fun browserUserAgent(webViewDefault: String): String =
        webViewDefault.replace("; wv", "").replace(" Version/4.0", "")
}

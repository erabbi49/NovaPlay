package com.novaplay.app.web

import org.json.JSONObject

class SiteException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** The site could not be reached or the page failed to load. */
        NETWORK,

        /** The site wants a human check (Cloudflare etc.); the in-app browser can pass it once. */
        CHALLENGE,

        /** The page loaded but no videos were found on it. */
        EMPTY,

        /** The site says the video is gone or blocked. */
        UNAVAILABLE,

        /** The page loaded but no playable stream could be found. */
        NO_STREAM
    }
}

/** A sentence that can be shown to the user as is. */
fun SiteException.userMessage(): String = when (kind) {
    SiteException.Kind.CHALLENGE -> "The site wants a human check. Open it in the in-app browser once, then try again."
    SiteException.Kind.NETWORK -> "Can't reach the site ($message). Check the connection or the site address in Settings."
    SiteException.Kind.UNAVAILABLE -> message ?: "This video is not available"
    SiteException.Kind.NO_STREAM -> "No playable stream was found on this page."
    SiteException.Kind.EMPTY -> "Nothing found."
}

/** What the scraping logic needs from a browser page. Implemented by the hidden WebView (and by a fake in tests). */
interface PageDriver {
    val userAgent: String

    /** URLs of .m3u8 / .mp4 requests seen since the last load, oldest first. */
    fun sniffed(): List<String>

    /** Loads [url] and returns once the page finished loading. Throws [SiteException] on failure. */
    suspend fun load(url: String, timeoutMs: Long)

    /** Runs [script] in the page. Returns the JSON-decoded result (String, JSONObject, ...) or null. */
    suspend fun eval(script: String): Any?

    suspend fun title(): String

    /** Stops loading and blanks the page (kills playing media). */
    suspend fun stop()
}

suspend fun PageDriver.evalObject(script: String): JSONObject? {
    return when (val v = eval(script)) {
        is JSONObject -> v
        is String -> try {
            JSONObject(v)
        } catch (e: Exception) {
            null
        }
        else -> null
    }
}

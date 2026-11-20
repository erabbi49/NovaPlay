package com.novaplay.app.adblock

/** Resource type bit flags used by network filters. */
object ResType {
    const val DOCUMENT = 1
    const val SUBDOCUMENT = 2
    const val SCRIPT = 4
    const val IMAGE = 8
    const val STYLESHEET = 16
    const val XHR = 32
    const val MEDIA = 64
    const val FONT = 128
    const val PING = 256
    const val WEBSOCKET = 512
    const val OTHER = 1024
    const val POPUP = 2048

    /** What a rule without type options applies to (everything except page loads and popups). */
    const val DEFAULT = SUBDOCUMENT or SCRIPT or IMAGE or STYLESHEET or XHR or MEDIA or FONT or PING or WEBSOCKET or OTHER
    const val EVERYTHING = DEFAULT or DOCUMENT or POPUP
}

internal object Hosts {
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go", "mil")

    /** Lower-case host of [url] without user info and port. */
    fun hostOf(url: String): String {
        val s = url.indexOf("://")
        val start = if (s >= 0) s + 3 else 0
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        var host = url.substring(start, end)
        val at = host.lastIndexOf('@')
        if (at >= 0) host = host.substring(at + 1)
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            return (if (close > 0) host.substring(0, close + 1) else host).lowercase()
        }
        val colon = host.indexOf(':')
        if (colon >= 0) host = host.substring(0, colon)
        return host.lowercase()
    }

    /** Index in [url] where the host (without port) ends. */
    fun hostEndIndex(url: String): Int {
        val s = url.indexOf("://")
        val start = if (s >= 0) s + 3 else 0
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        val at = url.lastIndexOf('@', end - 1)
        val hostStart = if (at >= start) at + 1 else start
        if (hostStart < url.length && url[hostStart] == '[') {
            val close = url.indexOf(']', hostStart)
            return if (close in 0 until end) close + 1 else end
        }
        val colon = url.indexOf(':', hostStart)
        return if (colon in 0 until end) colon else end
    }

    /** Cheap "registrable domain" (eTLD+1) guess that understands co.uk / com.bd style suffixes. */
    fun registrable(host: String): String {
        if (host.isEmpty() || host.startsWith("[")) return host
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val last = labels[labels.size - 1]
        val second = labels[labels.size - 2]
        if (last.all { it.isDigit() } && second.all { it.isDigit() }) return host // IPv4
        val take = if (last.length == 2 && second in SECOND_LEVEL) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }

    fun matchesDomain(host: String, domain: String): Boolean {
        if (host == domain) return true
        return host.length > domain.length && host.endsWith(domain) && host[host.length - domain.length - 1] == '.'
    }

    fun matchesAny(host: String, domains: Array<String>): Boolean {
        for (d in domains) if (matchesDomain(host, d)) return true
        return false
    }
}

/** One request being checked: URL plus page context, with derived data computed once. */
internal class Req(val url: String, val pageHost: String, val type: Int) {
    val lurl: String = url.lowercase()
    val host: String = Hosts.hostOf(lurl)
    val hostEnd: Int = Hosts.hostEndIndex(lurl)
    val thirdParty: Boolean =
        pageHost.isNotEmpty() && type != ResType.DOCUMENT && Hosts.registrable(host) != Hosts.registrable(pageHost)

    /** Alphanumeric runs (length >= 2) of the lower-cased URL, used to look rules up in the token index. */
    val tokens: List<String> by lazy(LazyThreadSafetyMode.NONE) { tokenize(lurl) }

    companion object {
        fun tokenize(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            val n = s.length
            while (i < n && out.size < 80) {
                if (isAlnum(s[i])) {
                    var j = i + 1
                    while (j < n && isAlnum(s[j])) j++
                    if (j - i >= 2) out.add(s.substring(i, j))
                    i = j
                } else {
                    i++
                }
            }
            return out
        }

        fun isAlnum(c: Char): Boolean = (c in 'a'..'z') || (c in '0'..'9') || (c in 'A'..'Z')
    }
}

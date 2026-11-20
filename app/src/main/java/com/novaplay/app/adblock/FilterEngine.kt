package com.novaplay.app.adblock

import java.util.concurrent.ConcurrentHashMap

/**
 * uBlock-Origin / Adblock-Plus compatible filtering engine (static network filters, element hiding,
 * scriptlet rules). Immutable after [build], safe to share between threads. Pure JVM.
 */
class FilterEngine private constructor(private val b: FilterBuilder) {

    val networkRuleCount: Int = b.block.count + b.important.count + b.exception.count
    val cosmeticRuleCount: Int = b.cosmetic.count

    private val pageAllowCache = ConcurrentHashMap<String, Boolean>()

    /**
     * True when the request should be blocked. [pageUrl] is the document that triggered it
     * (null for unknown) and [type] one of the [ResType] flags.
     */
    fun shouldBlock(url: String, pageUrl: String?, type: Int): Boolean {
        if (url.length < 8) return false
        if (!(url.startsWith("http") || url.startsWith("ws"))) return false
        var pageHost = ""
        if (!pageUrl.isNullOrEmpty()) {
            pageHost = Hosts.hostOf(pageUrl.lowercase())
            if (pageHost.isNotEmpty() && isPageAllowed(pageUrl, pageHost)) return false
        }
        val req = Req(url, pageHost, type)
        if (b.important.find(req) != null) return true
        if (b.exception.find(req) != null) return false
        return b.block.find(req) != null
    }

    /** "@@||site^$document" style exceptions switch blocking off for the whole page. */
    private fun isPageAllowed(pageUrl: String, pageHost: String): Boolean {
        val cached = pageAllowCache[pageHost]
        if (cached != null) return cached
        val allowed = b.exception.find(Req(pageUrl, "", ResType.DOCUMENT)) != null
        if (pageAllowCache.size > 500) pageAllowCache.clear()
        pageAllowCache[pageHost] = allowed
        return allowed
    }

    /** CSS hiding the domain-specific element-hiding rules for [pageHost], one rule per selector. */
    fun cosmeticCss(pageHost: String): String = toCss(b.cosmetic.specificFor(pageHost.lowercase()))

    /** Generic selectors worth hiding on [pageHost] given the classes / ids that exist on the page. */
    fun genericSelectors(pageHost: String, classes: Collection<String>, ids: Collection<String>): List<String> {
        return b.cosmetic.genericFor(pageHost.lowercase(), classes, ids)
    }

    /** Scriptlet calls for [pageHost]; each entry is [name, arg1, arg2, ...]. */
    fun scriptlets(pageHost: String): List<List<String>> = b.cosmetic.scriptletsFor(pageHost.lowercase())

    /** Generic selectors that are not plain class / id selectors (not used by default, they are many). */
    fun genericComplexSelectors(): List<String> = b.cosmetic.genericComplex

    companion object {
        /** [lists] are (listId, rawText) pairs. */
        fun build(lists: List<Pair<String, String>>): FilterEngine {
            val builder = FilterBuilder()
            builder.build(lists)
            return FilterEngine(builder)
        }

        fun toCss(selectors: List<String>): String {
            if (selectors.isEmpty()) return ""
            val sb = StringBuilder(selectors.size * 40)
            for (s in selectors) {
                sb.append(s).append("{display:none!important}\n")
            }
            return sb.toString()
        }
    }
}

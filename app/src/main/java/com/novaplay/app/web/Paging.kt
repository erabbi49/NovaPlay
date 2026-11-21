package com.novaplay.app.web

import java.util.TreeSet

/**
 * Page-number arithmetic for the site's listing URLs. Pure string handling, so it also runs in plain JVM tests.
 * A "template" is a URL with [SLOT] where the page number goes, e.g. https://example.com/newest/{n}.
 */
object Paging {
    const val SLOT = "{n}"

    private class Parts(val head: String, val path: String, val query: String)

    private val PAGE_PARAM = Regex("(^|&)page=(\\d+)(?=&|$)")
    private val SEARCH_PATH = Regex("^(/[a-z]{2})?/search(/|$)")

    private fun split(url: String): Parts {
        val noFragment = url.substringBefore('#')
        val q = noFragment.indexOf('?')
        val beforeQuery = if (q >= 0) noFragment.substring(0, q) else noFragment
        val query = if (q >= 0) noFragment.substring(q + 1) else ""
        val schemeEnd = beforeQuery.indexOf("://")
        val pathStart = if (schemeEnd >= 0) beforeQuery.indexOf('/', schemeEnd + 3) else beforeQuery.indexOf('/')
        return if (pathStart < 0) {
            Parts(beforeQuery, "", query)
        } else {
            Parts(beforeQuery.substring(0, pathStart), beforeQuery.substring(pathStart), query)
        }
    }

    private fun isPageSegment(s: String): Boolean = s.isNotEmpty() && s.length <= 5 && s.all { it in '0'..'9' }

    /** 3 for https://x/newest/3 and https://x/search/a?page=3, otherwise 1. */
    fun pageOf(url: String): Int {
        val p = split(url)
        PAGE_PARAM.find(p.query)?.let { return it.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1 }
        val last = p.path.trimEnd('/').substringAfterLast('/')
        return if (isPageSegment(last)) last.toInt().coerceAtLeast(1) else 1
    }

    /** The URL pattern for the list that [url] belongs to ([url] may be any of its pages). */
    fun templateFor(url: String): String {
        val p = split(url)
        if (PAGE_PARAM.containsMatchIn(p.query)) {
            return p.head + p.path + "?" + PAGE_PARAM.replace(p.query) { it.groupValues[1] + "page=" + SLOT }
        }
        val trimmed = p.path.trimEnd('/')
        val last = trimmed.substringAfterLast('/')
        val q = if (p.query.isEmpty()) "" else "?" + p.query
        if (isPageSegment(last)) return p.head + trimmed.substringBeforeLast('/') + "/" + SLOT + q
        if (SEARCH_PATH.containsMatchIn(trimmed)) {
            return p.head + trimmed + "?" + (if (p.query.isEmpty()) "" else p.query + "&") + "page=" + SLOT
        }
        return p.head + trimmed + "/" + SLOT + q
    }

    /** The first page of the list [url] belongs to. */
    fun firstOf(url: String): String {
        val p = split(url)
        if (PAGE_PARAM.containsMatchIn(p.query)) {
            val rest = PAGE_PARAM.replace(p.query, "").trimStart('&').replace("&&", "&").trimEnd('&')
            return p.head + p.path + if (rest.isEmpty()) "" else "?$rest"
        }
        val trimmed = p.path.trimEnd('/')
        val last = trimmed.substringAfterLast('/')
        if (isPageSegment(last)) {
            val parent = trimmed.substringBeforeLast('/')
            return p.head + (if (parent.isEmpty()) "/" else parent) + if (p.query.isEmpty()) "" else "?" + p.query
        }
        return url.substringBefore('#')
    }

    fun fill(template: String, page: Int): String = template.replace(SLOT, page.toString())

    /** A template learned from one of the site's own numbered links (page [n] lives at [url]), or null. */
    fun inferTemplate(n: Int, url: String): String? {
        if (n < 2 || pageOf(url) != n) return null
        return templateFor(url)
    }

    /** What the page bar shows: page numbers, with 0 standing for "…". [max] is 0 when the site does not say. */
    fun window(cur: Int, max: Int, hasNext: Boolean): List<Int> {
        val c = cur.coerceAtLeast(1)
        val out = ArrayList<Int>()
        if (max > 0) {
            if (max <= 7) {
                for (i in 1..max) out.add(i)
                return out
            }
            val set = TreeSet<Int>()
            set.add(1)
            set.add(max)
            for (i in (c - 1)..(c + 1)) if (i in 1..max) set.add(i)
            if (c <= 3) for (i in 2..4) set.add(i)
            if (c >= max - 2) for (i in (max - 3)..(max - 1)) set.add(i)
            var prev = 0
            for (x in set) {
                if (prev != 0 && x - prev > 1) out.add(0)
                out.add(x)
                prev = x
            }
            return out
        }
        val lo = (c - 2).coerceAtLeast(1)
        val hi = if (hasNext) c + 1 else c
        if (lo > 1) {
            out.add(1)
            if (lo > 2) out.add(0)
        }
        for (i in lo..hi) out.add(i)
        return out
    }
}

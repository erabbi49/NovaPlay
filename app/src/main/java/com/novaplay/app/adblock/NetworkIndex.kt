package com.novaplay.app.adblock

/** Pattern matching with ABP semantics: '*' wildcard (already split away) and '^' separator. */
internal object Pat {
    fun isSep(c: Char): Boolean = !(Req.isAlnum(c) || c == '_' || c == '-' || c == '.' || c == '%')

    /** Matches [seg] (may contain '^') exactly at [pos]; returns the end index or -1. */
    fun segAt(s: String, seg: String, pos: Int): Int {
        var p = pos
        for (k in seg.indices) {
            val c = seg[k]
            if (c == '^') {
                if (p >= s.length) continue // '^' also matches the end of the address
                if (!isSep(s[p])) return -1
                p++
            } else {
                if (p >= s.length || s[p] != c) return -1
                p++
            }
        }
        return p
    }

    /** Earliest occurrence of [seg] at or after [from]; returns its end index or -1. */
    fun segFind(s: String, seg: String, from: Int): Int {
        if (seg.isEmpty()) return from
        if (seg.indexOf('^') < 0) {
            val i = s.indexOf(seg, from)
            return if (i < 0) -1 else i + seg.length
        }
        var i = from
        while (i <= s.length) {
            val e = segAt(s, seg, i)
            if (e >= 0) return e
            i++
        }
        return -1
    }

    fun endsAtEnd(s: String, seg: String, from: Int): Boolean {
        if (seg.indexOf('^') < 0) return s.length - seg.length >= from && s.endsWith(seg)
        var i = from
        while (i <= s.length) {
            if (segAt(s, seg, i) == s.length) return true
            i++
        }
        return false
    }

    private fun rest(s: String, segs: Array<String>, from: Int, startPos: Int, rightAnchor: Boolean): Boolean {
        var pos = startPos
        val n = segs.size
        for (k in from until n) {
            if (k == n - 1 && rightAnchor) return endsAtEnd(s, segs[k], pos)
            val e = segFind(s, segs[k], pos)
            if (e < 0) return false
            pos = e
        }
        return if (from >= n && rightAnchor) pos == s.length else true
    }

    /** [fixedStart] >= 0 pins the first segment to that index, otherwise it may match anywhere. */
    fun matchSegments(s: String, segs: Array<String>, fixedStart: Int, rightAnchor: Boolean): Boolean {
        if (segs.isEmpty()) return true
        if (fixedStart >= 0) {
            val e = segAt(s, segs[0], fixedStart)
            if (e < 0) return false
            if (segs.size == 1) return !rightAnchor || e == s.length
            return rest(s, segs, 1, e, rightAnchor)
        }
        if (segs.size == 1) {
            return if (rightAnchor) endsAtEnd(s, segs[0], 0) else segFind(s, segs[0], 0) >= 0
        }
        val e = segFind(s, segs[0], 0)
        if (e < 0) return false
        return rest(s, segs, 1, e, rightAnchor)
    }
}

internal class NetRule(
    /** Pattern pieces between '*' wildcards (for host-keyed rules: the part after the host). */
    val segments: Array<String>,
    val leftAnchor: Boolean,
    /** "||" rule whose first piece may start at any label boundary of the host. */
    val hostAnchor: Boolean,
    /** "||host..." rule looked up by host; [segments] are anchored right after the host. */
    val hostKeyed: Boolean,
    val rightAnchor: Boolean,
    val regex: Regex?,
    val literal: String?,
    val types: Int,
    /** 0 = any, 1 = third-party only, 2 = first-party only */
    val party: Int,
    val domains: Array<String>?,
    val notDomains: Array<String>?,
    val denyAllow: Array<String>?,
    val matchCase: Boolean
) {
    fun matches(req: Req): Boolean {
        if ((req.type and types) == 0) return false
        if (party == 1 && !req.thirdParty) return false
        if (party == 2 && req.thirdParty) return false
        val nd = notDomains
        if (nd != null && req.pageHost.isNotEmpty() && Hosts.matchesAny(req.pageHost, nd)) return false
        val d = domains
        if (d != null && (req.pageHost.isEmpty() || !Hosts.matchesAny(req.pageHost, d))) return false
        val da = denyAllow
        if (da != null && Hosts.matchesAny(req.host, da)) return false

        val rx = regex
        if (rx != null) {
            val lit = literal
            if (lit != null && !req.lurl.contains(lit)) return false
            return rx.containsMatchIn(req.url)
        }
        val s = if (matchCase) req.url else req.lurl
        if (hostKeyed) return Pat.matchSegments(s, segments, req.hostEnd, rightAnchor)
        if (hostAnchor) {
            val sch = s.indexOf("://")
            val hostStart = if (sch >= 0) sch + 3 else 0
            var i = hostStart
            val end = req.hostEnd
            while (i <= end) {
                if (Pat.matchSegments(s, segments, i, rightAnchor)) return true
                val dot = s.indexOf('.', i)
                if (dot < 0 || dot >= end) break
                i = dot + 1
            }
            return false
        }
        if (leftAnchor) return Pat.matchSegments(s, segments, 0, rightAnchor)
        return Pat.matchSegments(s, segments, -1, rightAnchor)
    }
}

/** Rules grouped for fast lookup: by host (for ||domain rules), by a rare token, or "rest" (always tried). */
internal class RuleIndex {
    private val byHost = HashMap<String, ArrayList<NetRule>>()
    private val byToken = HashMap<String, ArrayList<NetRule>>()
    private val rest = ArrayList<NetRule>()
    var count = 0
        private set

    fun addHost(key: String, r: NetRule) {
        var list = byHost[key]
        if (list == null) {
            list = ArrayList(2)
            byHost[key] = list
        }
        list.add(r)
        count++
    }

    fun addToken(token: String, r: NetRule) {
        var list = byToken[token]
        if (list == null) {
            list = ArrayList(2)
            byToken[token] = list
        }
        list.add(r)
        count++
    }

    fun addRest(r: NetRule) {
        rest.add(r)
        count++
    }

    fun find(req: Req): NetRule? {
        var h = req.host
        while (h.isNotEmpty()) {
            val list = byHost[h]
            if (list != null) {
                for (r in list) if (r.matches(req)) return r
            }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        for (t in req.tokens) {
            val list = byToken[t] ?: continue
            for (r in list) if (r.matches(req)) return r
        }
        for (r in rest) if (r.matches(req)) return r
        return null
    }
}

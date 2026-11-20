package com.novaplay.app.adblock

/** Turns filter-list text (ABP / uBlock Origin syntax) into indexes. Pure JVM. */
internal class FilterBuilder {
    val block = RuleIndex()
    val important = RuleIndex()
    val exception = RuleIndex()
    val cosmetic = CosmeticStore()

    private val badKeys = HashSet<String>()

    fun build(lists: List<Pair<String, String>>) {
        for (l in lists) collectBadFilters(l.second)
        for (l in lists) parseText(l.second)
    }

    // ---------- pass 1: $badfilter targets ----------

    private fun collectBadFilters(text: String) {
        if (!text.contains("badfilter")) return
        forEachLine(text) { line ->
            if (line.contains("badfilter") && line.indexOf('$') >= 0) {
                val dollar = line.lastIndexOf('$')
                val opts = line.substring(dollar + 1).split(',')
                val kept = opts.filter { it != "badfilter" }
                if (kept.size != opts.size) {
                    val target = line.substring(0, dollar) + (if (kept.isEmpty()) "" else "$" + kept.joinToString(","))
                    badKeys.add(target)
                }
            }
        }
    }

    private inline fun forEachLine(text: String, action: (String) -> Unit) {
        var start = 0
        val n = text.length
        while (start < n) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = n
            val line = text.substring(start, end).trim()
            if (line.isNotEmpty()) action(line)
            start = end + 1
        }
    }

    // ---------- pass 2 ----------

    private fun parseText(text: String) {
        forEachLine(text) { line ->
            try {
                parseLine(line)
            } catch (e: Exception) {
                // a malformed rule must never break the whole list
            }
        }
    }

    private fun parseLine(line: String) {
        val c0 = line[0]
        if (c0 == '!') return
        if (c0 == '[' && line.startsWith("[Adblock")) return
        if (badKeys.isNotEmpty() && badKeys.contains(line)) return
        val hash = line.indexOf('#')
        if (hash >= 0) {
            if (parseCosmetic(line, hash)) return
            if (c0 == '#') return
        }
        parseNetwork(line)
    }

    // ---------- cosmetic ----------

    private val proceduralMarkers = arrayOf(
        ":has-text(", ":contains(", ":-abp-", ":xpath(", ":matches-css", ":matches-media(", ":matches-path(",
        ":matches-attr(", ":min-text-length(", ":upward(", ":nth-ancestor(", ":others(", ":remove(",
        ":style(", ":watch-attr(", ":if(", ":if-not(", "-ext-", ":matches-prop("
    )

    private fun isProcedural(sel: String): Boolean {
        for (m in proceduralMarkers) if (sel.contains(m)) return true
        return false
    }

    /** Returns true when the line was a cosmetic rule (handled or deliberately skipped). */
    private fun parseCosmetic(line: String, hash: Int): Boolean {
        val hide: Boolean
        val sepLen: Int
        when {
            line.startsWith("##", hash) -> {
                hide = true
                sepLen = 2
            }
            line.startsWith("#@#", hash) -> {
                hide = false
                sepLen = 3
            }
            line.startsWith("#?#", hash) || line.startsWith("#$#", hash) || line.startsWith("#%#", hash) ||
                line.startsWith("#@?#", hash) || line.startsWith("#@$#", hash) || line.startsWith("#@%#", hash) -> {
                return domainPartOk(line, hash)
            }
            else -> return false
        }
        if (!domainPartOk(line, hash)) return false
        val body = line.substring(hash + sepLen).trim()
        if (body.isEmpty()) return true

        val includes = ArrayList<String>()
        val excludes = ArrayList<String>()
        var skippedInclude = false
        if (hash > 0) {
            for (raw in line.substring(0, hash).split(',')) {
                val t = raw.trim().lowercase()
                if (t.isEmpty()) continue
                val neg = t.startsWith("~")
                val name = if (neg) t.substring(1) else t
                if (name.isEmpty()) continue
                if (name.indexOf('*') >= 0) {
                    if (!neg) skippedInclude = true
                    continue
                }
                if (neg) excludes.add(name) else includes.add(name)
            }
        }
        if (skippedInclude && includes.isEmpty()) return true

        if (body.startsWith("+js(")) {
            val args = parseScriptletArgs(body) ?: return true
            if (hide) {
                if (args.isNotEmpty() && args[0].isNotEmpty()) cosmetic.addScriptlet(includes, excludes, args)
            } else {
                cosmetic.addScriptletException(includes, args)
            }
            return true
        }
        if (body.startsWith("^") || body.indexOf('{') >= 0 || body.length > 600 || isProcedural(body)) return true
        if (hide) cosmetic.addHide(includes, excludes, body) else cosmetic.addHideException(includes, body)
        return true
    }

    private fun domainPartOk(line: String, hash: Int): Boolean {
        for (i in 0 until hash) {
            val c = line[i]
            val ok = Req.isAlnum(c) || c == '.' || c == ',' || c == '~' || c == '*' || c == '_' || c == '-'
            if (!ok) return false
        }
        return true
    }

    private fun parseScriptletArgs(body: String): List<String>? {
        val close = body.lastIndexOf(')')
        if (close < 4) return null
        val inner = body.substring(4, close)
        if (inner.isBlank()) return emptyList()
        val args = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (ch == '\\' && i + 1 < inner.length && inner[i + 1] == ',') {
                sb.append(',')
                i += 2
                continue
            }
            if (ch == ',') {
                args.add(sb.toString().trim())
                sb.setLength(0)
            } else {
                sb.append(ch)
            }
            i++
        }
        args.add(sb.toString().trim())
        return args
    }

    // ---------- network ----------

    private fun findOptionsDollar(line: String): Int {
        if (line.startsWith("/")) {
            val i = line.lastIndexOf("/$")
            if (i > 0) return i + 1
            if (line.endsWith("/")) return -1
        }
        return line.lastIndexOf('$')
    }

    private fun parseNetwork(raw: String) {
        var line = raw
        var isException = false
        if (line.startsWith("@@")) {
            isException = true
            line = line.substring(2)
        }
        var pattern = line
        var optText: String? = null
        val dollar = findOptionsDollar(line)
        if (dollar >= 0) {
            pattern = line.substring(0, dollar)
            optText = line.substring(dollar + 1)
        }

        var typeMask = 0
        var negMask = 0
        var party = 0
        var isImportant = false
        var matchCase = false
        var cosmeticOpt = 0
        var domains: ArrayList<String>? = null
        var notDomains: ArrayList<String>? = null
        var denyAllow: ArrayList<String>? = null
        var unsupported = false

        if (optText != null) {
            for (o in optText.split(',')) {
                val opt = o.trim()
                if (opt.isEmpty()) continue
                var name = opt
                var value: String? = null
                val eq = opt.indexOf('=')
                if (eq >= 0) {
                    name = opt.substring(0, eq)
                    value = opt.substring(eq + 1)
                }
                var neg = false
                if (name.startsWith("~")) {
                    neg = true
                    name = name.substring(1)
                }
                name = name.lowercase()
                var typeBit = 0
                when (name) {
                    "script" -> typeBit = ResType.SCRIPT
                    "image" -> typeBit = ResType.IMAGE
                    "stylesheet", "css" -> typeBit = ResType.STYLESHEET
                    "xmlhttprequest", "xhr" -> typeBit = ResType.XHR
                    "subdocument", "frame" -> typeBit = ResType.SUBDOCUMENT
                    "media" -> typeBit = ResType.MEDIA
                    "font" -> typeBit = ResType.FONT
                    "ping" -> typeBit = ResType.PING
                    "websocket" -> typeBit = ResType.WEBSOCKET
                    "other", "object", "object-subrequest" -> typeBit = ResType.OTHER
                    "document", "doc" -> typeBit = ResType.DOCUMENT
                    "popup" -> typeBit = ResType.POPUP
                    "all" -> typeMask = typeMask or ResType.EVERYTHING
                    "third-party", "3p", "strict3p" -> party = if (neg) 2 else 1
                    "first-party", "1p", "strict1p" -> party = if (neg) 1 else 2
                    "important" -> isImportant = true
                    "match-case" -> matchCase = true
                    "badfilter" -> unsupported = true
                    "empty", "mp4", "redirect" -> { /* handled as a plain block */ }
                    "elemhide", "ehide" -> cosmeticOpt = cosmeticOpt or 1
                    "generichide", "ghide" -> cosmeticOpt = cosmeticOpt or 2
                    "specifichide", "shide" -> cosmeticOpt = cosmeticOpt or 4
                    "domain", "from" -> {
                        for (d in (value ?: "").lowercase().split('|')) {
                            if (d.isEmpty()) continue
                            val dn = d.startsWith("~")
                            val dd = if (dn) d.substring(1) else d
                            if (dd.indexOf('*') >= 0 || dd.isEmpty()) {
                                if (!dn) unsupported = true
                                continue
                            }
                            if (dn) {
                                if (notDomains == null) notDomains = ArrayList()
                                notDomains.add(dd)
                            } else {
                                if (domains == null) domains = ArrayList()
                                domains.add(dd)
                            }
                        }
                    }
                    "denyallow" -> {
                        for (d in (value ?: "").lowercase().split('|')) {
                            if (d.isEmpty() || d.indexOf('*') >= 0) continue
                            if (denyAllow == null) denyAllow = ArrayList()
                            denyAllow.add(d)
                        }
                    }
                    else -> unsupported = true
                }
                if (typeBit != 0) {
                    if (neg) negMask = negMask or typeBit else typeMask = typeMask or typeBit
                }
            }
        }
        if (unsupported) return

        var types = if (typeMask != 0) typeMask else ResType.DEFAULT
        types = types and negMask.inv()
        if (types == 0) return

        var p = pattern.trim()
        var hostAnchor = false
        var leftAnchor = false
        var rightAnchor = false
        if (p.startsWith("||")) {
            hostAnchor = true
            p = p.substring(2)
        } else if (p.startsWith("|")) {
            leftAnchor = true
            p = p.substring(1)
        }
        if (p.length > 1 && p.endsWith("|")) {
            rightAnchor = true
            p = p.substring(0, p.length - 1)
        }

        // ----- regex rule -----
        if (!hostAnchor && !leftAnchor && p.length >= 3 && p.startsWith("/") && p.endsWith("/")) {
            val src = p.substring(1, p.length - 1)
            val rx = try {
                if (matchCase) Regex(src) else Regex(src, RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                return
            }
            val rule = NetRule(
                emptyArray(), false, false, false, false, rx, literalOf(src), types, party,
                domains?.toTypedArray(), notDomains?.toTypedArray(), denyAllow?.toTypedArray(), matchCase
            )
            target(isException, isImportant).addRest(rule)
            return
        }

        if (!matchCase) p = p.lowercase()
        val trailingStar = p.endsWith("*")
        val leadingStar = p.startsWith("*")
        if (trailingStar) rightAnchor = false
        if (leadingStar) {
            leftAnchor = false
            hostAnchor = false
        }
        val parts = ArrayList<String>()
        for (piece in p.split('*')) if (piece.isNotEmpty()) parts.add(piece)

        // ----- host key for ||domain rules -----
        var hostKey: String? = null
        if (hostAnchor && parts.isNotEmpty()) {
            val first = parts[0]
            var k = 0
            while (k < first.length && (Req.isAlnum(first[k]) || first[k] == '.' || first[k] == '-')) k++
            if (k > 0 && k < first.length && first.substring(0, k).indexOf('.') > 0 && first[k - 1] != '.') {
                val next = first[k]
                if (next == '^' || next == '/' || next == ':' || next == '?') {
                    hostKey = first.substring(0, k)
                    parts[0] = first.substring(k)
                }
            }
        }

        if (cosmeticOpt != 0) {
            // @@||example.com^$generichide etc.: switch cosmetic filtering off for that site
            if (isException && hostKey != null) {
                if ((cosmeticOpt and 1) != 0 || (cosmeticOpt and 4) != 0) {
                    if ((cosmeticOpt and 1) != 0) cosmetic.elemhideOff.add(hostKey)
                }
                if ((cosmeticOpt and 2) != 0) cosmetic.genericOff.add(hostKey)
            }
            if (typeMask == 0) return
        }

        if (parts.isEmpty() && hostKey == null && domains == null && party == 0) return

        val rule = NetRule(
            parts.toTypedArray(), leftAnchor, hostAnchor && hostKey == null, hostKey != null, rightAnchor,
            null, null, types, party,
            domains?.toTypedArray(), notDomains?.toTypedArray(), denyAllow?.toTypedArray(), matchCase
        )
        val index = target(isException, isImportant)
        if (hostKey != null) {
            index.addHost(hostKey, rule)
            return
        }
        val token = pickToken(parts, leftAnchor, rightAnchor)
        if (token != null) index.addToken(token, rule) else index.addRest(rule)
    }

    private fun target(isException: Boolean, isImportant: Boolean): RuleIndex {
        return if (isException) exception else if (isImportant) important else block
    }

    /**
     * Longest alphanumeric run that is delimited on both sides inside the pattern, so that it
     * must show up as a whole token of any URL the rule can match.
     */
    private fun pickToken(parts: List<String>, leftAnchor: Boolean, rightAnchor: Boolean): String? {
        var best: String? = null
        for (pi in parts.indices) {
            val seg = parts[pi]
            var i = 0
            while (i < seg.length) {
                if (Req.isAlnum(seg[i])) {
                    var j = i + 1
                    while (j < seg.length && Req.isAlnum(seg[j])) j++
                    val leftOk = i > 0 || (pi == 0 && leftAnchor)
                    val rightOk = j < seg.length || (pi == parts.size - 1 && rightAnchor)
                    if (leftOk && rightOk && j - i >= 2) {
                        val t = seg.substring(i, j)
                        if (best == null || t.length > best.length) best = t
                    }
                    i = j
                } else {
                    i++
                }
            }
        }
        return best
    }

    /** Longest literal run that any match of the regex must contain (null when unsure). */
    private fun literalOf(src: String): String? {
        if (src.indexOf('(') >= 0 || src.indexOf('|') >= 0) return null
        var best = ""
        val cur = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' && i + 1 < src.length -> {
                    val n = src[i + 1]
                    if (n == '.' || n == '/' || n == '-' || n == '_' || n == '=' || n == '?' || n == '&' || n == '%') {
                        cur.append(n.lowercaseChar())
                    } else {
                        if (cur.length > best.length) best = cur.toString()
                        cur.setLength(0)
                    }
                    i += 2
                    continue
                }
                c == '[' -> {
                    if (cur.length > best.length) best = cur.toString()
                    cur.setLength(0)
                    val close = src.indexOf(']', i)
                    i = if (close < 0) src.length else close + 1
                    continue
                }
                c == '?' || c == '*' || c == '{' -> {
                    if (cur.isNotEmpty()) cur.setLength(cur.length - 1)
                    if (cur.length > best.length) best = cur.toString()
                    cur.setLength(0)
                    if (c == '{') {
                        val close = src.indexOf('}', i)
                        i = if (close < 0) src.length else close + 1
                        continue
                    }
                }
                c == '.' || c == '+' || c == '^' || c == '$' || c == ')' || c == '}' || c == ']' -> {
                    if (cur.length > best.length) best = cur.toString()
                    cur.setLength(0)
                }
                else -> cur.append(c.lowercaseChar())
            }
            i++
        }
        if (cur.length > best.length) best = cur.toString()
        return if (best.length >= 3) best else null
    }
}

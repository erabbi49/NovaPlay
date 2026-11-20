package com.novaplay.app.adblock

/** Element-hiding rules and scriptlet rules, indexed for per-page lookups. */
internal class CosmeticStore {
    /** domain -> selectors hidden on that domain (and its subdomains) */
    val specific = HashMap<String, ArrayList<String>>()
    /** domain -> selectors that must NOT be hidden there ("#@#" rules and negated domains) */
    val specificExc = HashMap<String, ArrayList<String>>()
    val globalExc = HashSet<String>()

    val genericByClass = HashMap<String, ArrayList<String>>()
    val genericById = HashMap<String, ArrayList<String>>()
    val genericComplex = ArrayList<String>()

    val scriptSpecific = HashMap<String, ArrayList<List<String>>>()
    val scriptGeneric = ArrayList<List<String>>()
    /** domain ("" = everywhere) -> argument key of an excepted scriptlet; "" key means "all scriptlets" */
    val scriptExc = HashMap<String, HashSet<String>>()

    /** Domains for which "$elemhide" / "$generichide" exceptions switch cosmetic filtering off. */
    val elemhideOff = HashSet<String>()
    val genericOff = HashSet<String>()

    var count = 0

    private val simpleClass = Regex("^[a-zA-Z0-9]*\\.([A-Za-z0-9_-]+)$")
    private val simpleId = Regex("^[a-zA-Z0-9]*#([A-Za-z0-9_-]+)$")

    fun addHide(includes: List<String>, excludes: List<String>, selector: String) {
        count++
        if (includes.isNotEmpty()) {
            for (d in includes) specific.getOrPut(d) { ArrayList(4) }.add(selector)
            for (d in excludes) specificExc.getOrPut(d) { ArrayList(2) }.add(selector)
            return
        }
        for (d in excludes) specificExc.getOrPut(d) { ArrayList(2) }.add(selector)
        val c = simpleClass.find(selector)
        if (c != null) {
            genericByClass.getOrPut(c.groupValues[1]) { ArrayList(2) }.add(selector)
            return
        }
        val i = simpleId.find(selector)
        if (i != null) {
            genericById.getOrPut(i.groupValues[1]) { ArrayList(2) }.add(selector)
            return
        }
        genericComplex.add(selector)
    }

    fun addHideException(includes: List<String>, selector: String) {
        if (includes.isEmpty()) {
            globalExc.add(selector)
        } else {
            for (d in includes) specificExc.getOrPut(d) { ArrayList(2) }.add(selector)
        }
    }

    fun addScriptlet(includes: List<String>, excludes: List<String>, args: List<String>) {
        count++
        if (includes.isEmpty()) {
            scriptGeneric.add(args)
            val key = args.joinToString(",")
            for (d in excludes) scriptExc.getOrPut(d) { HashSet() }.add(key)
        } else {
            for (d in includes) scriptSpecific.getOrPut(d) { ArrayList(2) }.add(args)
            val key = args.joinToString(",")
            for (d in excludes) scriptExc.getOrPut(d) { HashSet() }.add(key)
        }
    }

    fun addScriptletException(includes: List<String>, args: List<String>) {
        val key = if (args.isEmpty()) "" else args.joinToString(",")
        if (includes.isEmpty()) {
            scriptExc.getOrPut("") { HashSet() }.add(key)
        } else {
            for (d in includes) scriptExc.getOrPut(d) { HashSet() }.add(key)
        }
    }

    private inline fun forEachSuffix(host: String, action: (String) -> Unit) {
        var h = host
        while (h.isNotEmpty()) {
            action(h)
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
    }

    private fun hostIn(host: String, set: HashSet<String>): Boolean {
        if (set.isEmpty()) return false
        var found = false
        forEachSuffix(host) { if (it in set) found = true }
        return found
    }

    private fun exceptionsFor(host: String): HashSet<String> {
        val exc = HashSet<String>(globalExc)
        forEachSuffix(host) { d ->
            val l = specificExc[d]
            if (l != null) exc.addAll(l)
        }
        return exc
    }

    fun specificFor(host: String): List<String> {
        if (hostIn(host, elemhideOff)) return emptyList()
        val exc = exceptionsFor(host)
        val out = LinkedHashSet<String>()
        forEachSuffix(host) { d ->
            val l = specific[d]
            if (l != null) for (s in l) if (s !in exc) out.add(s)
        }
        return ArrayList(out)
    }

    fun genericFor(host: String, classes: Collection<String>, ids: Collection<String>): List<String> {
        if (hostIn(host, elemhideOff) || hostIn(host, genericOff)) return emptyList()
        val exc = exceptionsFor(host)
        val out = LinkedHashSet<String>()
        for (c in classes) {
            val l = genericByClass[c] ?: continue
            for (s in l) if (s !in exc) out.add(s)
        }
        for (i in ids) {
            val l = genericById[i] ?: continue
            for (s in l) if (s !in exc) out.add(s)
        }
        return ArrayList(out)
    }

    fun scriptletsFor(host: String): List<List<String>> {
        val excKeys = HashSet<String>()
        scriptExc[""]?.let { excKeys.addAll(it) }
        forEachSuffix(host) { d ->
            val l = scriptExc[d]
            if (l != null) excKeys.addAll(l)
        }
        if (excKeys.contains("")) return emptyList()
        val out = ArrayList<List<String>>()
        for (a in scriptGeneric) if (a.joinToString(",") !in excKeys) out.add(a)
        forEachSuffix(host) { d ->
            val l = scriptSpecific[d]
            if (l != null) for (a in l) if (a.joinToString(",") !in excKeys) out.add(a)
        }
        return out
    }
}

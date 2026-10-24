package com.nibrascode.pdf

// PdfBox ərəb yazısını birləşdirmir: hərfləri Presentation Forms-a çevirib vizual sıraya düzürük
object Shaper {
    // hərf -> [təkbaşına, son, əvvəl, orta] (2 formalı hərflər yalnız sağdan birləşir)
    private val forms = HashMap<Char, IntArray>()

    init {
        fun two(c: Int, iso: Int) { forms[c.toChar()] = intArrayOf(iso, iso + 1) }
        fun four(c: Int, iso: Int) { forms[c.toChar()] = intArrayOf(iso, iso + 1, iso + 2, iso + 3) }
        forms['\u0621'] = intArrayOf(0xFE80)
        two(0x622, 0xFE81); two(0x623, 0xFE83); two(0x624, 0xFE85); two(0x625, 0xFE87)
        four(0x626, 0xFE89); two(0x627, 0xFE8D); four(0x628, 0xFE8F); two(0x629, 0xFE93)
        four(0x62A, 0xFE95); four(0x62B, 0xFE99); four(0x62C, 0xFE9D); four(0x62D, 0xFEA1)
        four(0x62E, 0xFEA5); two(0x62F, 0xFEA9); two(0x630, 0xFEAB); two(0x631, 0xFEAD)
        two(0x632, 0xFEAF); four(0x633, 0xFEB1); four(0x634, 0xFEB5); four(0x635, 0xFEB9)
        four(0x636, 0xFEBD); four(0x637, 0xFEC1); four(0x638, 0xFEC5); four(0x639, 0xFEC9)
        four(0x63A, 0xFECD); four(0x641, 0xFED1); four(0x642, 0xFED5); four(0x643, 0xFED9)
        four(0x644, 0xFEDD); four(0x645, 0xFEE1); four(0x646, 0xFEE5); four(0x647, 0xFEE9)
        two(0x648, 0xFEED); two(0x649, 0xFEEF); four(0x64A, 0xFEF1)
        four(0x67E, 0xFB56); four(0x686, 0xFB7A); two(0x698, 0xFB8A); four(0x6AF, 0xFB92)
        four(0x6A9, 0xFB8E); four(0x6CC, 0xFBFC)
    }

    private fun transparent(c: Char) = c.code in 0x064B..0x065F || c.code == 0x0670 || c.code in 0x06D6..0x06ED

    private fun dual(c: Char?) = c != null && (c == '\u0640' || (forms[c]?.size ?: 0) == 4)

    private fun joining(c: Char?) = c != null && (c == '\u0640' || forms.containsKey(c))

    private fun lamAlef(a: Char): Int = when (a) {
        '\u0622' -> 0xFEF5; '\u0623' -> 0xFEF7; '\u0625' -> 0xFEF9; '\u0627' -> 0xFEFB; else -> 0
    }

    fun shape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        val n = s.length
        fun prevJ(k: Int): Char? {
            var j = k - 1
            while (j >= 0 && transparent(s[j])) j--
            return if (j >= 0) s[j] else null
        }
        fun nextIdx(k: Int): Int {
            var j = k + 1
            while (j < n && transparent(s[j])) j++
            return j
        }
        while (i < n) {
            val c = s[i]
            val f = forms[c]
            if (f == null) {
                sb.append(c)
                i++
                continue
            }
            val jp = dual(prevJ(i))
            val ni = nextIdx(i)
            val nc = if (ni < n) s[ni] else null
            // lam + əlif liqaturası
            if (c == '\u0644' && nc != null && lamAlef(nc) != 0) {
                sb.append((lamAlef(nc) + if (jp) 1 else 0).toChar())
                for (k in i + 1 until ni) sb.append(s[k])
                i = ni + 1
                continue
            }
            val jn = f.size == 4 && joining(nc)
            val idx = when {
                jp && jn -> 3
                jn -> 2
                jp && f.size >= 2 -> 1
                else -> 0
            }
            sb.append(f[idx].toChar())
            i++
        }
        return sb.toString()
    }

    private val mirror = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    // Məntiqi sıra -> vizual (soldan sağa) sıra
    fun visual(line: String): String {
        if (line.none { it.code in 0x0590..0x08FF || it.code in 0xFB50..0xFEFF }) return line
        val shaped = shape(line)
        val bidi = java.text.Bidi(shaped, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        if (bidi.isLeftToRight) return shaped
        val rc = bidi.runCount
        val levels = ByteArray(rc)
        val runs = arrayOfNulls<String>(rc)
        for (r in 0 until rc) {
            levels[r] = bidi.getRunLevel(r).toByte()
            val part = shaped.substring(bidi.getRunStart(r), bidi.getRunLimit(r))
            runs[r] = if (bidi.getRunLevel(r) % 2 == 1) reverseClusters(part) else part
        }
        java.text.Bidi.reorderVisually(levels, 0, runs, 0, rc)
        return runs.joinToString("")
    }

    // Hərəkələr öz hərfinin yanında qalsın deyə klaster-klaster tərsinə çevirir
    private fun reverseClusters(s: String): String {
        val clusters = ArrayList<String>()
        var cur = StringBuilder()
        for (c in s) {
            if (transparent(c) && cur.isNotEmpty()) {
                cur.append(c)
            } else {
                if (cur.isNotEmpty()) clusters.add(cur.toString())
                cur = StringBuilder()
                cur.append(mirror[c] ?: c)
            }
        }
        if (cur.isNotEmpty()) clusters.add(cur.toString())
        clusters.reverse()
        return clusters.joinToString("")
    }
}

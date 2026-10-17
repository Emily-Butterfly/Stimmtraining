package de.emilybutterfly.stimmtraining.data.document

/** HTML-Entitäten (`&auml;`, `&#228;`, `&#xE4;`) für HTML und Markdown. */
internal object HtmlEntities {
    private val LATIN1 = (
        "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn " +
            "sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave " +
            "Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml " +
            "ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN " +
            "szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute " +
            "icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml " +
            "yacute thorn yuml"
        ).split(' ')

    private val NAMED: Map<String, String> = buildMap {
        LATIN1.forEachIndexed { index, name -> put(name, (0xA0 + index).toChar().toString()) }
        val others = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "ndash" to "–", "mdash" to "—", "minus" to "−", "hellip" to "…", "bull" to "•",
            "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
            "lsaquo" to "‹", "rsaquo" to "›", "prime" to "′", "Prime" to "″",
            "euro" to "€", "trade" to "™", "dagger" to "†", "Dagger" to "‡", "permil" to "‰",
            "OElig" to "Œ", "oelig" to "œ", "Scaron" to "Š", "scaron" to "š", "Yuml" to "Ÿ",
            "fnof" to "ƒ", "circ" to "ˆ", "tilde" to "˜",
            "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009",
            "zwnj" to "\u200C", "zwj" to "\u200D", "lrm" to "\u200E", "rlm" to "\u200F",
            "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔",
        )
        putAll(others)
    }

    /**
     * Entschlüsselt die Entität, die bei [start] (einem '&') beginnt. Liefert den Text und die
     * Position dahinter, oder null, wenn dort keine bekannte Entität steht.
     */
    fun decodeAt(s: CharSequence, start: Int): Pair<String, Int>? {
        var i = start + 1
        if (i >= s.length) return null
        if (s[i] == '#') {
            i++
            val hex = i < s.length && (s[i] == 'x' || s[i] == 'X')
            if (hex) i++
            val digitsStart = i
            val radix = if (hex) 16 else 10
            while (i < s.length && i - digitsStart < 7 && Character.digit(s[i], radix) >= 0) i++
            if (i == digitsStart || i >= s.length || s[i] != ';') return null
            return codePoint(s.substring(digitsStart, i).toInt(radix)) to i + 1
        }
        val nameStart = i
        while (i < s.length && i - nameStart < 32 && (s[i] in 'a'..'z' || s[i] in 'A'..'Z' || s[i] in '0'..'9')) i++
        if (i == nameStart || i >= s.length || s[i] != ';') return null
        val value = NAMED[s.substring(nameStart, i)] ?: return null
        return value to i + 1
    }

    private fun codePoint(code: Int): String = when {
        // Wie im Browser: 128–159 sind in Wahrheit Windows-1252-Zeichen (z. B. &#150; für –).
        code in 0x80..0x9F -> String(byteArrayOf(code.toByte()), TextDecoding.WINDOWS_1252)
        code == 0 || code > 0x10FFFF || code in 0xD800..0xDFFF -> "�"
        else -> String(Character.toChars(code))
    }
}

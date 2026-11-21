// Minimal JSON reader (enough for GeoJSON). No external libraries.
package converter

class Json(private val s: String) {
    private var i = 0
    fun parse(): Any? { ws(); return value() }
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> obj(); '[' -> arr(); '"' -> str()
            't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
            else -> num()
        }
    }
    private fun obj(): Map<String, Any?> {
        val m = HashMap<String, Any?>(); i++; ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws(); val k = str(); ws(); i++ /* : */; m[k] = value(); ws()
            if (s[i] == ',') { i++; continue }; i++; return m
        }
    }
    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>(); i++; ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value()); ws()
            if (s[i] == ',') { i++; continue }; i++; return l
        }
    }
    private fun str(): String {
        i++; val sb = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(s[i])
                }
            } else sb.append(s[i])
            i++
        }
        i++; return sb.toString()
    }
    private fun num(): Double {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
        return s.substring(st, i).toDouble()
    }
}

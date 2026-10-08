package tech.whitewolf.app.subapp.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * composeJs (WWT-253) splices a caller-controlled mailto: link into script text, so the
 * one property that matters is that the link can only ever be a single string literal.
 */
class ComposeJsTest {
    private val prefix = "(window.wwtCompose ? window.wwtCompose("
    private val middle = ") : (window.wwtPendingCompose = "
    private val suffix = "))"

    /** The two literals the script passes, decoded as JSON (a subset of JS string syntax). */
    private fun literals(js: String): Pair<String, String> {
        check(js.startsWith(prefix) && js.endsWith(suffix)) { js }
        val inner = js.removePrefix(prefix).removeSuffix(suffix)
        val (a, b) = inner.split(middle).also { check(it.size == 2) { js } }
        fun decode(lit: String) = Json.parseToJsonElement(lit).jsonPrimitive.also { check(it.isString) }.content
        return decode(a) to decode(b)
    }

    private fun assertRoundTrips(mailto: String) {
        val js = composeJs(mailto)
        assertEquals(mailto to mailto, literals(js))
        // Line terminators would end a JS string literal (U+2028/9 did, before ES2019).
        for (c in listOf('\n', '\r', ' ', ' ')) assertFalse("raw ${c.code} in $js", c in js)
    }

    @Test fun plainLink() = assertRoundTrips("mailto:a@b.com?subject=Hi%20there")

    @Test fun quotesCannotBreakOut() {
        assertRoundTrips("mailto:a@b.com?subject=\"); alert(1); (\"")
        assertRoundTrips("mailto:a@b.com?subject='); alert(1); ('")
    }

    @Test fun backslash() = assertRoundTrips("mailto:a@b.com?subject=a\\\"b\\")

    @Test fun scriptCloseTag() = assertRoundTrips("mailto:a@b.com?body=</script><script>alert(1)</script>")

    @Test fun lineSeparators() = assertRoundTrips("mailto:a@b.com?body=x y z")

    @Test fun newlines() = assertRoundTrips("mailto:a@b.com?body=line1\r\nline2\n")

    // The substitution must not re-scan the quoted value: a link that itself contains the
    // placeholder text the template would otherwise use is still just one literal.
    @Test fun valueIsNotRescanned() = assertRoundTrips("mailto:M@b.com?subject=M M \$q \${q}")
}

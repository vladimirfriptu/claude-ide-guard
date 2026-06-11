package dev.friptu.ideguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun parsesFlatStringObject() {
        val m = MiniJson.parseFlatObject("""{"path":"/a/b.ts","action":"start","sessionId":"abc"}""")
        assertEquals("/a/b.ts", m["path"])
        assertEquals("start", m["action"])
        assertEquals("abc", m["sessionId"])
    }

    @Test
    fun handlesWhitespaceAndOrdering() {
        val m = MiniJson.parseFlatObject("""  {  "action" : "end" ,  "path":"/x"  }  """)
        assertEquals("end", m["action"])
        assertEquals("/x", m["path"])
    }

    @Test
    fun unescapesStringValues() {
        val m = MiniJson.parseFlatObject("""{"path":"/a/with \"quote\" and \\slash\\ and \n newline"}""")
        assertEquals("/a/with \"quote\" and \\slash\\ and \n newline", m["path"])
    }

    @Test
    fun nullLiteralBecomesNull() {
        val m = MiniJson.parseFlatObject("""{"sessionId":null,"path":"/a"}""")
        assertTrue(m.containsKey("sessionId"))
        assertNull(m["sessionId"])
    }

    @Test
    fun malformedInputDoesNotThrow() {
        // Truncated object — must not throw, just return what it could read.
        val m = MiniJson.parseFlatObject("""{"path":"/a""")
        assertEquals("/a", m["path"])
    }

    @Test
    fun emptyOrGarbageReturnsEmpty() {
        assertTrue(MiniJson.parseFlatObject("").isEmpty())
        assertTrue(MiniJson.parseFlatObject("not json").isEmpty())
        assertTrue(MiniJson.parseFlatObject("{}").isEmpty())
    }

    @Test
    fun objEscapesKeysAndValues() {
        val s = MiniJson.obj("decision" to "ask", "reason" to "say \"hi\"\nbye")
        assertEquals("""{"decision":"ask","reason":"say \"hi\"\nbye"}""", s)
    }

    @Test
    fun roundTripThroughObjAndParse() {
        val path = """/Users/me/проект/file with spaces & "quotes".ts"""
        val json = MiniJson.obj("path" to path)
        val parsed = MiniJson.parseFlatObject(json)
        assertEquals(path, parsed["path"])
    }
}

package dev.maia.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `parses the session envelope the server actually sends`() {
        val root = Json.parse(
            """{"data":{"id":"ses_f4eac","projectID":"2d84a837","agent":"fusion","cost":0}}"""
        )
        assertEquals("ses_f4eac", root.string("data", "id"))
        assertEquals("2d84a837", root.string("data", "projectID"))
        assertEquals(0L, root.long("data", "cost"))
    }

    @Test
    fun `a missing key is null rather than an exception`() {
        val root = Json.parse("""{"data":{"id":"ses_1"}}""")
        assertNull(root.string("data", "projectID"))
        assertNull(root.string("nothing", "here", "at", "all"))
        // Wrong type, not absent: still null, because a client that throws on
        // an unexpected shape stops working on a server upgrade.
        assertNull(root.long("data", "id"))
    }

    @Test
    fun `escapes survive a round trip`() {
        val awkward = "quote \" backslash \\ newline \n tab \t unicode é"
        val written = Json.write(mapOf("t" to awkward))
        assertEquals(awkward, Json.parse(written).string("t"))
    }

    @Test
    fun `nested objects and arrays`() {
        val v = Json.parse("""{"a":[1,{"b":[true,false,null]},"x"],"c":{}}""")
        assertEquals(3, v.list("a")?.size)
        assertEquals(true, (v.list("a")?.get(1)).list("b")?.get(0))
        assertEquals(emptyMap<String, Any?>(), v.at("c"))
    }

    @Test
    fun `whole numbers are written without a decimal point`() {
        // The server's schemas say integer for sequence numbers, and a
        // "4096.0" in a body is a 400 waiting to happen.
        assertEquals("""{"port":4096}""", Json.obj("port" to 4096))
        assertEquals("""{"ratio":0.5}""", Json.obj("ratio" to 0.5))
    }

    @Test
    fun `a null value drops its key`() {
        // delivery is optional and additionalProperties is false, so sending
        // an explicit null is not the same as leaving it out.
        assertEquals("""{"prompt":"go"}""", Json.obj("prompt" to "go", "delivery" to null))
    }

    @Test
    fun `bad input is rejected rather than half parsed`() {
        for (bad in listOf("", "{", """{"a"}""", """{"a":}""", "[1,]", "tru", """{"a":1}x""")) {
            val failed = runCatching { Json.parse(bad) }.isFailure
            assertTrue("should have rejected: $bad", failed)
        }
    }
}

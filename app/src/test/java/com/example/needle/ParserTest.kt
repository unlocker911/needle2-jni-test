package com.example.needle

import com.google.gson.Gson
import org.junit.Test
import org.junit.Assert.*

class ParserTest {

    @Test
    fun testCallResponseParsing() {
        val gson = Gson()
        val json = """{"type":"call","success":true,"error":null,"error_code":null,"reason":null,"function_calls":[{"name":"device.flashlight_on","arguments":{}}],"reasoning":null,"confidence":1.0000,"prefill_tps":66.5,"decode_tps":21.6,"peak_ram_mb":286.4,"validation":{"ungrounded":[],"negation":false}}"""
        
        val response = gson.fromJson(json, NeedleResponse::class.java)
        
        assertEquals("call", response.typeNonNull)
        assertTrue(response.success)
        assertEquals(1, response.functionCallsNonNull.size)
        assertEquals("device.flashlight_on", response.functionCallsNonNull[0].name)
        assertEquals(1.0f, response.confidenceFloat, 0.001f)
        assertEquals(66.5f, response.prefillTpsFloat, 0.1f)
        assertEquals(21.6f, response.decodeTpsFloat, 0.1f)
        assertEquals(286, response.peakRamMbInt)
        assertNotNull(response.validation)
        assertEquals(0, response.validation!!.ungrounded.size)
        assertFalse(response.validation!!.negation)
    }

    @Test
    fun testRespondResponseParsing() {
        val gson = Gson()
        val json = """{"type":"respond","success":true,"error":null,"error_code":null,"reason":null,"function_calls":[],"reasoning":"User asked for flashlight on; respond with the result.","confidence":0.5068,"prefill_tps":48.3,"decode_tps":78.6,"peak_ram_mb":290.5}"""
        
        val response = gson.fromJson(json, NeedleResponse::class.java)
        
        assertEquals("respond", response.typeNonNull)
        assertTrue(response.success)
        assertTrue(response.functionCallsNonNull.isEmpty())
        assertEquals("User asked for flashlight on; respond with the result.", response.effectiveReasoning)
        assertEquals(0.5068f, response.confidenceFloat, 0.0001f)
        assertEquals(48.3f, response.prefillTpsFloat, 0.1f)
        assertEquals(78.6f, response.decodeTpsFloat, 0.1f)
        assertEquals(290, response.peakRamMbInt)
    }
}
package com.neurovox.ira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IraServerSettingsTest {
    @Test
    fun acceptsWebSocketAddressesForLanHosts() {
        assertEquals(
            "ws://ira-pi.local:8765/",
            IraServerSettings.normalizeEndpoint(" ws://ira-pi.local:8765/ "),
        )
        assertEquals(
            "wss://ira.example.com/ira",
            IraServerSettings.normalizeEndpoint("wss://ira.example.com/ira"),
        )
    }

    @Test
    fun rejectsNonWebSocketAndMalformedAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            IraServerSettings.normalizeEndpoint("http://192.168.1.50:8765/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IraServerSettings.normalizeEndpoint("ws:///8765/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            IraServerSettings.normalizeEndpoint("ws://192.168.1.50:70000/")
        }
    }
}

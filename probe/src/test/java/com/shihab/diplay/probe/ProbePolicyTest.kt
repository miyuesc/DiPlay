package com.shihab.diplay.probe

import org.junit.Assert.*
import org.junit.Test

class ProbePolicyTest {
    @Test fun rejectsPrimaryOwnPrivateInvalidAndNonPresentationDisplays() {
        assertFalse(DisplayProbePolicy.eligible(0, 2, true, true, false))
        assertFalse(DisplayProbePolicy.eligible(2, 2, true, true, false))
        assertFalse(DisplayProbePolicy.eligible(3, 2, true, true, true))
        assertFalse(DisplayProbePolicy.eligible(3, 2, false, true, false))
        assertFalse(DisplayProbePolicy.eligible(3, 2, true, false, false))
        assertTrue(DisplayProbePolicy.eligible(3, 2, true, true, false))
    }

    @Test fun exportRedactsIdentifiersAndCredentialLines() {
        assertEquals("[redacted]", ProbeText.safe("SSID=family-car"))
        assertEquals("[redacted]", ProbeText.safe("token:secret"))
        assertEquals("[address] [address]", ProbeText.safe("AA:BB:CC:DD:EE:FF 192.168.1.2"))
        assertEquals("[identifier]", ProbeText.safe("12345678-1234-1234-1234-123456789abc"))
        assertEquals("[identifier]", ProbeText.safe("1234567890abcdef1234567890abcdef"))
        assertEquals("[identifier]", ProbeText.safe("LFZ63AL52RD1657231"))
        assertEquals("a b c", ProbeText.safe("a\nb\tc"))
    }

    @Test fun versionInputCannotCarryFreeformPersonalNotes() {
        assertEquals("15.10.0", ProbeText.mapVersion(" 15.10.0 "))
        assertEquals("unknown", ProbeText.mapVersion("password=hello"))
        assertEquals("unknown", ProbeText.mapVersion("Alice's phone"))
        assertEquals("unknown", ProbeText.mapVersion("15.10\nprivate"))
        assertEquals("unknown", ProbeText.mapVersion(""))
    }

    @Test fun malformedOrFloodedDiagnosticTextRemainsBounded() {
        assertEquals(240, ProbeText.safe("x".repeat(1000)).length)
        repeat(250) { ProbeState.record("test", "item=$it") }
        val entries = ProbeState.snapshot()
        assertEquals(200, entries.size)
        assertEquals("item=50", entries.first().detail)
        assertEquals("item=249", entries.last().detail)
        // A caller cannot mutate the live log through a returned snapshot.
        ProbeState.record("test", "last")
        assertEquals("item=249", entries.last().detail)
    }
}

package com.shihab.diplay.probe

/** Only bounded, structured facts enter the report. Never store intent extras or device names. */
internal object ProbeState {
    private val startedAt = System.nanoTime()
    private val events = ArrayDeque<ProbeEvent>()
    @Synchronized fun record(kind: String, detail: String) {
        if (events.size == 200) events.removeFirst()
        events.addLast(ProbeEvent(kind, ProbeText.safe(detail), (System.nanoTime() - startedAt) / 1_000_000))
    }
    @Synchronized fun snapshot(): List<ProbeEvent> = events.toList()
}

internal data class ProbeEvent(val kind: String, val detail: String, val elapsedMillis: Long)

internal object ProbeText {
    private val mac = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val ipv4 = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b")
    private val vin = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")
    private val secret = Regex("(?i)(password|passphrase|ssid|token|private.?key|certificate)\\s*[:=]")
    fun safe(value: String): String {
        if (secret.containsMatchIn(value)) return "[redacted]"
        return value.replace(mac, "[address]").replace(ipv4, "[address]")
            .replace(identifier, "[identifier]").replace(vin, "[identifier]")
            .replace(Regex("[\\r\\n\\t]"), " ").take(240)
    }
    // A version is deliberately a narrow field, not arbitrary user notes.
    fun mapVersion(value: String): String = value.trim().takeIf {
        it.matches(Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){1,3}"))
    } ?: "unknown"
}

internal object DisplayProbePolicy {
    fun eligible(id: Int, ownId: Int, valid: Boolean, presentation: Boolean, isPrivate: Boolean): Boolean =
        id != 0 && id != ownId && valid && presentation && !isPrivate
}

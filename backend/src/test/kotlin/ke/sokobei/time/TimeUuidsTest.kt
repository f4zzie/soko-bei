package ke.sokobei.time

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TimeUuidsTest {
    private val id = UUID.fromString("7b0f5a52-1d3c-4a7e-9f0b-1c2d3e4f5a6b")
    private val ms = 1_760_692_800_000L

    @Test fun `same input gives same uuid`() {
        assertEquals(TimeUuids.forReport(id, ms), TimeUuids.forReport(id, ms))
    }

    @Test fun `different report id gives different uuid`() {
        assertNotEquals(TimeUuids.forReport(id, ms), TimeUuids.forReport(UUID.randomUUID(), ms))
    }

    @Test fun `is version 1 with RFC variant and embedded millisecond matches`() {
        val u = TimeUuids.forReport(id, ms)
        assertEquals(1, u.version())
        assertEquals(2, u.variant())
        assertEquals(ms, TimeUuids.timestampMs(u))
    }

    @Test fun `sub millisecond offset stays within the same millisecond`() {
        repeat(500) {
            val u = TimeUuids.forReport(UUID.randomUUID(), ms)
            assertEquals(ms, TimeUuids.timestampMs(u))
        }
    }

    @Test fun `later milliseconds order after earlier ones regardless of id`() {
        repeat(200) {
            val a = TimeUuids.forReport(UUID.randomUUID(), ms)
            val b = TimeUuids.forReport(UUID.randomUUID(), ms + 1)
            assertTrue(a.timestamp() < b.timestamp())
        }
    }

    @Test fun `java timestamp equals expected 100ns count`() {
        val u = TimeUuids.forReport(id, ms)
        val base = (ms * 10_000) + 122_192_928_000_000_000L
        assertTrue(u.timestamp() in base until base + 10_000)
    }
}

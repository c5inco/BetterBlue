package com.betterblue.kit.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class Uuid5Test {
    @Test
    fun `RFC 4122 test vector for the DNS namespace`() {
        // Well-known UUIDv5 vector: uuid5(NAMESPACE_DNS, "www.example.org")
        assertEquals(
            UUID.fromString("74738ff5-5367-5958-9aee-98fffdcd1876"),
            Uuid5.of(Uuid5.NAMESPACE_DNS, "www.example.org"),
        )
    }

    @Test
    fun `python uuid5 vector for python dot org`() {
        // python: uuid.uuid5(uuid.NAMESPACE_DNS, 'python.org')
        assertEquals(
            UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"),
            Uuid5.of(Uuid5.NAMESPACE_DNS, "python.org"),
        )
    }

    @Test
    fun `version and variant bits are stamped`() {
        val uuid = Uuid5.of(Uuid5.NAMESPACE_DNS, "any-device-id")
        assertEquals(5, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `derivation is deterministic`() {
        val a = Uuid5.of(Uuid5.NAMESPACE_DNS, "DEVICE-1234")
        val b = Uuid5.of(Uuid5.NAMESPACE_DNS, "DEVICE-1234")
        assertEquals(a, b)
    }
}

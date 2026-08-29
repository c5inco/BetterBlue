package com.betterblue.kit.util

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** RFC 4122 name-based UUIDs (version 5, SHA-1). */
object Uuid5 {
    /** The RFC 4122 DNS namespace, used by Kia USA's `clientuuid` derivation. */
    val NAMESPACE_DNS: UUID = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    fun of(namespace: UUID, name: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(toBytes(namespace))
        digest.update(name.toByteArray(Charsets.UTF_8))
        val hash = digest.digest()

        // Take the first 16 bytes, then stamp version 5 and the RFC 4122 variant.
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x50).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()

        val buffer = ByteBuffer.wrap(hash, 0, 16)
        return UUID(buffer.long, buffer.long)
    }

    private fun toBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
}

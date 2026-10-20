package com.personalos.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The one encoding for a stored vector: little-endian float32, no header. */
object EmbeddingBlob {
    fun encode(vector: FloatArray): ByteArray =
        ByteBuffer
            .allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { it.asFloatBuffer().put(vector) }
            .array()

    fun decode(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / Float.SIZE_BYTES)
        ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(floats)
        return floats
    }
}

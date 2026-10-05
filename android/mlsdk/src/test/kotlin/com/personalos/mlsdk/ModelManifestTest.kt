package com.personalos.mlsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelManifestTest {
    private val gemma =
        ModelSpec(
            name = "embeddinggemma",
            version = 1,
            fileName = "mofy_embedder_seq256.tflite",
            url = "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/mofy_embedder_seq256.tflite",
            sizeBytes = 179_131_736L,
            sha256 = "37115e",
            dim = 256,
        )

    @Test
    fun `a manifest round-trips through write and parse`() {
        assertEquals(listOf(gemma), ModelManifest.parse(ModelManifest.write(listOf(gemma))))
    }

    @Test
    fun `unreadable json is null, not a crash`() {
        assertNull(ModelManifest.parse("{ this is not json"))
    }

    @Test
    fun `unknown keys are ignored, so a newer manifest still parses`() {
        val json =
            """
            {"version":2,"future":"x","models":[
              {"name":"a","version":1,"file":"a.bin","url":"u","size":1,"sha256":"s","extra":2}]}
            """.trimIndent()
        assertEquals(listOf(ModelSpec("a", 1, "a.bin", "u", 1, "s", null)), ModelManifest.parse(json))
    }
}

package com.personalos.mlsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManifestTest {
    private val graph =
        ArtifactFile(
            name = "embeddinggemma",
            version = 2,
            fileName = "model_q4f16.onnx",
            url = "https://example.test/model_q4f16.onnx",
            sizeBytes = 705_221L,
            sha256 = "4df4",
            role = FileRole.GRAPH,
            dim = 256,
        )
    private val weights =
        ArtifactFile(
            name = "embeddinggemma",
            version = 2,
            fileName = "model_q4f16.onnx_data",
            url = "https://example.test/model_q4f16.onnx_data",
            sizeBytes = 175_410_176L,
            sha256 = "c9cc",
            role = FileRole.WEIGHTS,
        )
    private val embedder =
        Artifact.of(
            name = "embeddinggemma",
            version = 2,
            files = listOf(graph, weights),
            type = ArtifactType.EMBEDDER,
        )

    @Test
    fun `a registry round-trips through write and parse`() {
        val registry = ArtifactRegistry.of(listOf(embedder))
        assertEquals(registry, Manifest.parse(Manifest.write(registry)))
    }

    @Test
    fun `roles and type survive the round trip`() {
        val parsed = Manifest.parse(Manifest.write(ArtifactRegistry.of(listOf(embedder))))!!
        val artifact = parsed.artifact("embeddinggemma")!!
        assertEquals(ArtifactType.EMBEDDER, artifact.type)
        assertEquals(FileRole.GRAPH, artifact.fileWithRole(FileRole.GRAPH)!!.role)
        assertEquals(FileRole.WEIGHTS, artifact.fileWithRole(FileRole.WEIGHTS)!!.role)
    }

    @Test
    fun `a scoped artifact keeps its scope, and does not answer to the bare name`() {
        val scoped = Artifact.of(name = "embeddinggemma", version = 1, files = listOf(graph), scope = Scope.of("personalos"))
        val parsed = Manifest.parse(Manifest.write(ArtifactRegistry.of(listOf(scoped))))!!
        assertNotNull(parsed.artifact("personalos:embeddinggemma"))
        // Unprefixed means global only — the app-scoped copy must not answer for it.
        assertNull(parsed.artifact("embeddinggemma"))
    }

    @Test
    fun `unreadable json is null, not a crash`() {
        assertNull(Manifest.parse("{ this is not json"))
    }

    @Test
    fun `unknown keys are ignored, so a newer manifest still parses`() {
        val json =
            """
            {"version":2,"future":"x","artifacts":[
              {"name":"a","version":1,"dir":"global/a",
               "files":[{"file":"a.bin","url":"u","size":1,"sha256":"s","extra":2}]}]}
            """.trimIndent()
        assertEquals("global/a", Manifest.parse(json)?.artifact("a")?.dir)
    }

    @Test
    fun `a v1 flat manifest is read as a global artifact`() {
        val json =
            """
            {"version":1,"models":[{"name":"a","version":1,"file":"a.bin","url":"u","size":1,"sha256":"s"}]}
            """.trimIndent()
        val parsed = Manifest.parse(json)!!
        assertEquals("global/a", parsed.artifact("a")?.dir)
        assertEquals(ArtifactType.OTHER, parsed.artifact("a")?.type)
    }
}

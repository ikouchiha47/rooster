package com.personalos.mlsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * References are the API, so the rules that matter are: unprefixed is **global
 * only**, a prefix selects a scope, and both copies coexist.
 */
class ArtifactRouterTest {
    private fun file(
        name: String,
        role: FileRole = FileRole.OTHER,
    ) = ArtifactFile(
        name = name,
        version = 1,
        fileName = "$name.bin",
        url = "https://example.test/$name.bin",
        sizeBytes = 1L,
        sha256 = "s",
        role = role,
    )

    private val shared =
        Artifact.of(name = "embeddinggemma", version = 2, files = listOf(file("embeddinggemma")), type = ArtifactType.EMBEDDER)
    private val retrained =
        Artifact.of(
            name = "embeddinggemma",
            version = 1,
            files = listOf(file("embeddinggemma")),
            scope = Scope.of("personalos"),
            type = ArtifactType.EMBEDDER,
        )
    private val vocabulary =
        Artifact.of(name = "vocabulary", version = 1, files = listOf(file("vocabulary")), scope = Scope.of("personalos"), type = ArtifactType.DATASET)

    private val router = ArtifactRouter.of(shared, retrained, vocabulary)

    @Test
    fun `a bare name resolves to the shared artifact`() {
        assertEquals(shared, router.artifact("embeddinggemma"))
    }

    @Test
    fun `a prefix selects the app's own copy, and both survive`() {
        assertEquals(retrained, router.artifact("personalos:embeddinggemma"))
        assertEquals(2, router.ofType(ArtifactType.EMBEDDER).size)
    }

    @Test
    fun `an unknown reference is null, never a guess`() {
        assertNull(router.artifact("nope"))
        assertNull(router.artifact("other:embeddinggemma"))
    }

    @Test
    fun `an unprefixed name does not resolve an app-scoped artifact`() {
        val onlyScoped = ArtifactRouter.of(retrained)
        assertNull(onlyScoped.artifact("embeddinggemma"))
        assertNotNull(onlyScoped.artifact("personalos:embeddinggemma"))
    }

    @Test
    fun `typed lookups do not hand back the wrong type`() {
        assertEquals(shared, router.embedder("embeddinggemma"))
        assertNull(router.embedder("personalos:vocabulary"))
        assertEquals(vocabulary, router.dataset("personalos:vocabulary"))
    }

    @Test
    fun `scopes and types are listable`() {
        assertEquals(listOf("global", "personalos"), router.scopes())
        assertEquals(listOf(retrained, vocabulary), router.inScope("personalos"))
    }

    @Test
    fun `a flat list of files becomes one global artifact`() {
        val grouped = ArtifactRouter.from(listOf(file("a"), file("a"), file("b")))
        assertEquals(2, grouped.all().size)
        assertEquals("global/a", grouped.artifact("a")?.dir)
        assertEquals(2, grouped.artifact("a")?.files?.size)
    }
}

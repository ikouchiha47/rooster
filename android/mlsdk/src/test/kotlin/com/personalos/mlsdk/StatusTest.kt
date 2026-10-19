package com.personalos.mlsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {
    private val spec =
        ArtifactFile(
            name = "embeddinggemma",
            version = 1,
            fileName = "mofy_embedder_seq256.tflite",
            url = "https://example/m",
            sizeBytes = 171L,
            sha256 = "abc123",
            dim = 256,
        )

    @Test
    fun `no file is absent`() {
        assertEquals(ArtifactStatus.ABSENT, artifactStatus(null, spec))
    }

    @Test
    fun `a file of the wrong size is partial`() {
        assertEquals(ArtifactStatus.PARTIAL, artifactStatus(PresentFile(sizeBytes = 100, sha256 = ""), spec))
    }

    @Test
    fun `a right-sized file with the wrong hash is stale`() {
        assertEquals(ArtifactStatus.STALE, artifactStatus(PresentFile(171, "deadbeef"), spec))
    }

    @Test
    fun `matching size and hash is ready, case-insensitively`() {
        assertEquals(ArtifactStatus.READY, artifactStatus(PresentFile(171, "ABC123"), spec))
        assertTrue(artifactStatus(PresentFile(171, "abc123"), spec).usable)
        assertFalse(ArtifactStatus.PARTIAL.usable)
    }

    @Test
    fun `integrity needs both facts`() {
        assertTrue(Integrity.matches(171, "ABC123", spec))
        assertFalse(Integrity.matches(9, "abc123", spec))
        assertFalse(Integrity.matches(171, "xyz", spec))
    }
}

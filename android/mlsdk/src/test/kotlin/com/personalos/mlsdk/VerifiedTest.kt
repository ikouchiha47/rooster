package com.personalos.mlsdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The marker is a cache of a fact, so the only thing it must get right is
 * refusing to answer for a different fact: a changed spec or a replaced file
 * must invalidate it rather than skip the hash.
 */
class VerifiedTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun spec(
        sha: String = "abc123",
        size: Long = 4L,
    ) = ArtifactFile(
        name = "m",
        version = 1,
        fileName = "m.onnx",
        url = "https://example.test/m.onnx",
        sizeBytes = size,
        sha256 = sha,
    )

    @Test
    fun `a marker written for a spec matches that spec`() {
        val file = temp.newFile("m.onnx").apply { writeText("data") }
        Verified.write(file, spec())
        assertTrue(Verified.matches(file, spec()))
    }

    @Test
    fun `a different spec does not match, even at the same size`() {
        val file = temp.newFile("m.onnx").apply { writeText("data") }
        Verified.write(file, spec(sha = "abc123"))
        assertFalse("a new version must pay for one fresh hash", Verified.matches(file, spec(sha = "def456")))
    }

    @Test
    fun `the marker is case-insensitive about the sha it was written with`() {
        val file = temp.newFile("m.onnx").apply { writeText("data") }
        Verified.write(file, spec(sha = "ABC123"))
        assertTrue(Verified.matches(file, spec(sha = "abc123")))
    }

    @Test
    fun `no marker means no claim`() {
        val file = temp.newFile("m.onnx").apply { writeText("data") }
        assertFalse(Verified.matches(file, spec()))
    }

    @Test
    fun `the marker sits beside the file, never inside the kept set`() {
        val file = File(temp.root, "m.onnx")
        assertTrue(Verified.marker(file).name.endsWith(Verified.SUFFIX))
        assertTrue(Verified.marker(file).parentFile == file.parentFile)
    }
}

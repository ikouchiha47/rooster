package com.personalos.mlsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A URL is composed from its parts, so nothing has to remember a scheme. */
class SourcesTest {
    @Test
    fun `a github release asset is the static download path, no api and no token`() {
        assertEquals(
            "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/model.tflite",
            Sources.githubRelease("ikouchiha47", "mofy", "v0.1-facet", "model.tflite"),
        )
    }

    @Test
    fun `coordinates are owner slash repo`() {
        assertEquals(
            "https://github.com/ikouchiha47/mofy/releases/download/v1/a.bin",
            Sources.githubRelease("ikouchiha47/mofy", "v1", "a.bin"),
        )
    }

    @Test
    fun `malformed coordinates are refused, not silently rooted`() {
        assertThrows(IllegalArgumentException::class.java) { Sources.githubRelease("mofy", "v1", "a.bin") }
        assertThrows(IllegalArgumentException::class.java) { Sources.githubRelease("a/b/c", "v1", "a.bin") }
    }

    @Test
    fun `raw files and hugging face files are composed the same way`() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/a/b.json",
            Sources.githubRaw("o", "r", "main", "a/b.json"),
        )
        assertEquals(
            "https://huggingface.co/o/r/resolve/main/onnx/model.onnx",
            Sources.huggingFace("o/r", "onnx/model.onnx"),
        )
    }
}

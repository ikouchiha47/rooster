package com.personalos.mlsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Resolution order is the promise that moving the shared root does not strand a
 * model that is already on disk.
 */
class LookupTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the first root holding the file wins`() {
        val shared = temp.newFolder("shared")
        val legacy = temp.newFolder("legacy")
        File(shared, "model.onnx").writeText("new")
        File(legacy, "model.onnx").writeText("old")

        val found = Lookup.findExisting(listOf(shared, legacy), "model.onnx")

        assertEquals(shared, found?.parentFile)
    }

    @Test
    fun `a file only in the legacy root is still found`() {
        val shared = temp.newFolder("shared")
        val legacy = temp.newFolder("legacy")
        File(legacy, "model.onnx").writeText("old")

        val found = Lookup.findExisting(listOf(shared, legacy), "model.onnx")

        assertEquals(legacy, found?.parentFile)
    }

    @Test
    fun `a missing file is null rather than a guess`() {
        val shared = temp.newFolder("shared")
        assertNull(Lookup.findExisting(listOf(shared), "model.onnx"))
    }

    @Test
    fun `a directory with the right name is not a model file`() {
        val root = temp.newFolder("root")
        File(root, "model.onnx").mkdirs()
        assertNull(Lookup.findExisting(listOf(root), "model.onnx"))
    }
}

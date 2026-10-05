package com.personalos.mlsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(
        name: String,
        bytes: Int = 4,
    ): File = tmp.newFile(name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `a file the bundle dropped is an orphan`() {
        file("mofy_embedder_seq256.tflite", 16)
        file("model_q4f16.onnx", 8)

        val orphans =
            ModelFiles.orphans(
                tmp.root,
                keep = listOf("model_q4f16.onnx"),
            )

        assertEquals(listOf("mofy_embedder_seq256.tflite"), orphans.map { it.name })
    }

    @Test
    fun `an abandoned part file is an orphan even when nothing changed`() {
        file("model_q4f16.onnx_data.part", 3)

        val orphans = ModelFiles.orphans(tmp.root, keep = listOf("model_q4f16.onnx_data"))

        assertEquals(listOf("model_q4f16.onnx_data.part"), orphans.map { it.name })
    }

    @Test
    fun `nothing is an orphan when every file is kept`() {
        file("a.onnx")
        file("b.onnx_data")

        val orphans = ModelFiles.orphans(tmp.root, keep = listOf("a.onnx", "b.onnx_data"))

        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `directories are never orphans, so a nested layout survives`() {
        tmp.newFolder("nested")
        file("keep.onnx")

        val orphans = ModelFiles.orphans(tmp.root, keep = listOf("keep.onnx"))

        assertTrue("a directory is not a model file to delete", orphans.isEmpty())
    }
}

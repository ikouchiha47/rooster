package com.personalos.mlsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The content checks are the cheap half of "is this the model?" — they decide on
 * the first bytes, before a 176 MB hash. Each case here is a failure seen in the
 * wild (see `ModelValidation`), not a hypothetical.
 */
class ModelValidationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(
        name: String,
        vararg bytes: Int,
    ): File = temp.newFile(name).apply { writeBytes(ByteArray(bytes.size) { bytes[it].toByte() }) }

    @Test
    fun `empty file is rejected`() {
        val empty = temp.newFile("model.onnx")
        assertEquals("empty file", ModelValidation.problem(empty))
    }

    @Test
    fun `an html page is rejected even without a content type`() {
        val page = file("model.onnx", '<'.code, 'h'.code, 't'.code, 'm'.code, 'l'.code, '>'.code)
        assertEquals("server returned a web page, not a model file", ModelValidation.problem(page))
    }

    @Test
    fun `an html content type is rejected even when the body does not start with a tag`() {
        val body = file("model.onnx", 0x08, 0x01, 0x02, 0x03)
        assertEquals(
            "server returned a web page, not a model file",
            ModelValidation.problem(body, contentType = "text/html; charset=utf-8"),
        )
    }

    @Test
    fun `a tflite file with the TFL3 marker is plausible`() {
        val model = file("model.tflite", 0x00, 0x00, 0x00, 0x00, 0x54, 0x46, 0x4C, 0x33, 0x00)
        assertNull(ModelValidation.problem(model))
    }

    @Test
    fun `a tflite file without the marker is rejected`() {
        val model = file("model.tflite", 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals("not a valid TensorFlow Lite model", ModelValidation.problem(model))
    }

    @Test
    fun `an onnx file with the protobuf tag is plausible`() {
        val model = file("model.onnx", 0x08, 0x07, 0x12)
        assertNull(ModelValidation.problem(model))
    }

    @Test
    fun `an onnx file without the tag is rejected`() {
        val model = file("model.onnx", 0x00, 0x07, 0x12)
        assertEquals("not a valid ONNX model", ModelValidation.problem(model))
    }

    @Test
    fun `a tokenizer is accepted — it has no magic bytes to check`() {
        val tokenizer = file("tokenizer.json", '{'.code, '"'.code, 'v'.code)
        assertNull(ModelValidation.problem(tokenizer))
    }
}

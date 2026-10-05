package com.personalos.app.data.models

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * The on-device EmbeddingGemma (ONNX, 256-dim), one implementation of [Embedder] —
 * the one that can **batch**.
 *
 * The mofy TFLite export is a fixed `[1, 256]`, so its `embedMany` is a loop; this
 * export keeps a dynamic `batch_size` axis, so a chunk of up to [batchSize] texts
 * is one model run. Measured on this device's class of CPU: ~1.6x over per-item,
 * and far faster in absolute terms than the quantized TFLite — which is what makes
 * re-tagging a whole store tractable.
 *
 * Same two load-bearing details as [TfliteEmbedder] — MRL truncation (768 → 256)
 * **before** normalising, and no IO of its own — plus the batch sizing rule:
 * `batch × seq ≤ ~4096`, so short SMS widen the batch and long articles narrow it.
 *
 * One session behind a mutex. The prompt is **not** here: it belongs to the tagger
 * that was tuned with it.
 */
class OnnxEmbedder(
    private val modelFile: File,
    private val tokenizerFile: File,
    private val batchSize: Int = 16,
    private val maxSeqLen: Int = 256,
    private val embeddingDim: Int = 256,
) : Embedder {
    private val lock = Mutex()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: HuggingFaceTokenizer? = null
    private var outputName: String = DEFAULT_OUTPUT

    override fun isReady(): Boolean = session != null && tokenizer != null

    /** Loads the model; false when it cannot (the caller then runs the lexicon alone). */
    override suspend fun init(): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (isReady()) return@withLock true
                runCatching {
                    val environment = OrtEnvironment.getEnvironment()
                    val options =
                        OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(2)
                            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        }
                    // runInterruptible: `createSession` is a long blocking native call
                    // that would otherwise ignore cancellation, so a cancelled
                    // backfill kept loading a second model. Interrupt makes it stop.
                    val loaded = runInterruptible { environment.createSession(modelFile.absolutePath, options) }
                    // The pooled sentence embedding; fall back to whatever single output exists.
                    outputName = loaded.outputNames.firstOrNull { it == DEFAULT_OUTPUT } ?: loaded.outputNames.first()
                    tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
                    env = environment
                    session = loaded
                }.onFailure { Log.w(TAG, "embedder failed to load; the heuristic will tag", it) }
                    .isSuccess
            }
        }

    override suspend fun embed(text: String): FloatArray? = embedMany(listOf(text)).firstOrNull()

    override suspend fun embedMany(texts: List<String>): List<FloatArray?> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            lock.withLock {
                val loaded = session ?: return@withLock List(texts.size) { null }
                val tok = tokenizer ?: return@withLock List(texts.size) { null }
                val out = arrayOfNulls<FloatArray>(texts.size)
                for (start in texts.indices step batchSize) {
                    val end = minOf(start + batchSize, texts.size)
                    // A chunk that fails leaves its entries null — "no opinion" per
                    // item — rather than nulling the whole batch.
                    runCatching { runChunk(loaded, tok, texts.subList(start, end), out, start) }
                        .onFailure { Log.w(TAG, "embed batch failed", it) }
                }
                out.toList()
            }
        }

    private suspend fun runChunk(
        loaded: OrtSession,
        tok: HuggingFaceTokenizer,
        chunk: List<String>,
        out: Array<FloatArray?>,
        offset: Int,
    ) {
        val environment = env ?: return
        val encoded = chunk.map { tok.encode(it).ids }
        val seq = encoded.maxOf { it.size }.coerceIn(1, maxSeqLen)
        val rows = chunk.size
        val ids = LongArray(rows * seq)
        val mask = LongArray(rows * seq)
        encoded.forEachIndexed { i, row ->
            val take = row.size.coerceAtMost(seq)
            for (j in 0 until take) {
                ids[i * seq + j] = row[j]
                mask[i * seq + j] = 1L
            }
        }
        val shape = longArrayOf(rows.toLong(), seq.toLong())
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape).use { idTensor ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape).use { maskTensor ->
                // Bounded: `session.run` is a blocking native call the coroutine
                // cannot otherwise interrupt. runInterruptible lets the timeout act
                // on it; withTimeoutOrNull returns null on timeout (a slow chunk is
                // "no opinion") without swallowing real cancellation. ORT may ignore
                // the interrupt — best-effort, like the load.
                val result =
                    withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                        runInterruptible { loaded.run(mapOf(INPUT_IDS to idTensor, ATTENTION_MASK to maskTensor)) }
                    }
                if (result == null) {
                    Log.w(TAG, "inference timed out after ${INFERENCE_TIMEOUT_MS}ms; chunk left untagged")
                    return
                }
                result.use { r ->
                    // `get(name)` hands back a java.util.Optional; the pooled output is [rows, 768].
                    val pooled = r.get(outputName).orElse(null) ?: return

                    @Suppress("UNCHECKED_CAST")
                    val embeddings = pooled.value as Array<FloatArray>
                    for (i in 0 until rows) {
                        out[offset + i] = normalize(embeddings[i].copyOf(embeddingDim))
                    }
                }
            }
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 0f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    private companion object {
        const val TAG = "OnnxEmbedder"
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val DEFAULT_OUTPUT = "sentence_embedding"

        /** Generous bound for one inference chunk; a healthy chunk is well under 1s. */
        const val INFERENCE_TIMEOUT_MS = 15_000L
    }
}

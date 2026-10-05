package com.personalos.app.data.models

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.tensorflow.lite.Interpreter
import java.io.File
import kotlin.math.sqrt

/**
 * The on-device EmbeddingGemma (TFLite, 256-dim), one implementation of [Embedder]
 * — the batch-of-one one.
 *
 * Mirrors mofy's proven setup — LiteRT `Interpreter` on CPU (2 threads) + DJL
 * `HuggingFaceTokenizer` — because those are the versions verified on this device.
 * Two details are load-bearing:
 *
 *  - **MRL truncation happens before normalising** (768 → 256): the truncated
 *    prefix is a valid embedding only pre-norm; slicing a normalised vector would
 *    leave the wrong norm.
 *  - **It does no IO of its own** — the files are handed in already verified by
 *    the SDK's `ModelStore`. A failure to load returns false/null and the caller
 *    falls back to the heuristic; ingest is never blocked.
 *
 * The exported graph is a fixed `[1, 256]`, so [Embedder.embedMany] is inherited
 * as a loop — this runtime cannot batch. [OnnxEmbedder] is the batched one; both
 * are shipped, and which is active is a `KnownModels` choice, not an SDK one.
 *
 * One `Interpreter` behind a mutex: it is not thread-safe.
 */
class TfliteEmbedder(
    private val modelFile: File,
    private val tokenizerFile: File,
    private val maxSeqLen: Int = 256,
    private val nativeDim: Int = 768,
    private val embeddingDim: Int = 256,
) : Embedder {
    private val lock = Mutex()
    private var interpreter: Interpreter? = null
    private var tokenizer: HuggingFaceTokenizer? = null

    override fun isReady(): Boolean = interpreter != null && tokenizer != null

    /** Loads the model; false when it cannot (the caller then runs the lexicon alone). */
    override suspend fun init(): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (isReady()) return@withLock true
                runCatching {
                    tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
                    interpreter = runInterruptible { Interpreter(modelFile, Interpreter.Options().apply { setNumThreads(2) }) }
                }.onFailure { Log.w(TAG, "embedder failed to load; the heuristic will tag", it) }
                    .isSuccess
            }
        }

    /** The embedding, or null when not loaded. */
    override suspend fun embed(text: String): FloatArray? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val interp = interpreter ?: return@withLock null
                val tok = tokenizer ?: return@withLock null
                runCatching {
                    // Same bound as [OnnxEmbedder]: the run is a blocking native call,
                    // so it is wrapped to be interruptible and given a timeout. Null
                    // on timeout means "no opinion" for this item.
                    withTimeoutOrNull(INFERENCE_TIMEOUT_MS) { runInterruptible { compute(interp, tok, text) } }
                }.onFailure { Log.w(TAG, "embed failed", it) }.getOrNull()
            }
        }

    private fun compute(
        interp: Interpreter,
        tok: HuggingFaceTokenizer,
        text: String,
    ): FloatArray {
        val ids = tok.encode(text).ids
        val input = Array(1) { IntArray(maxSeqLen) { i -> if (i < ids.size) ids[i].toInt() else 0 } }
        val shape = interp.getOutputTensor(0).shape()
        val raw =
            when (shape.size) {
                // [1, seq, dim]: mean-pool the real tokens.
                3 -> {
                    val buf = Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
                    interp.run(input, buf)
                    val n = ids.size.coerceIn(1, shape[1])
                    FloatArray(shape[2]) { d -> (0 until n).sumOf { t -> buf[0][t][d].toDouble() }.toFloat() / n }
                }
                // [1, dim]: already pooled.
                2 -> {
                    val buf = Array(1) { FloatArray(shape[1]) }
                    interp.run(input, buf)
                    buf[0]
                }
                else -> {
                    val buf = FloatArray(nativeDim)
                    interp.run(input, buf)
                    buf
                }
            }
        return normalize(raw.copyOf(embeddingDim))
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
        const val TAG = "TfliteEmbedder"

        /** Generous bound for one inference; a healthy run is well under 1s. */
        const val INFERENCE_TIMEOUT_MS = 15_000L
    }
}

package com.personalos.app.data.models

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.LongBuffer
import kotlin.coroutines.cancellation.CancellationException
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
    // 16 was too many: on this device a 16-item chunk of 256-token texts took
    // longer than the 15 s watchdog, so every chunk was terminated and reported
    // as an ORT failure. 8 keeps a chunk well inside a timeout that is meant to
    // be a safety net, not the normal path.
    private val batchSize: Int = 8,
    private val maxSeqLen: Int = 256,
    private val embeddingDim: Int = 256,
) : Embedder {
    /** One-time load: the session is created exactly once. */
    private val loadLock = Mutex()

    /** Bounds concurrent runs on the single session to what the CPU can actually feed. */
    private val runLimiter = Semaphore(CONCURRENT_RUNS)

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: HuggingFaceTokenizer? = null
    private var outputName: String = DEFAULT_OUTPUT

    override fun isReady(): Boolean = session != null && tokenizer != null

    /** Loads the model; false when it cannot (the caller then runs the lexicon alone). */
    override suspend fun init(): Boolean =
        withContext(Dispatchers.IO) {
            loadLock.withLock {
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
            // A semaphore, not a mutex: the one session is thread-safe, so a couple
            // of families may run at once — bounded so they do not fight for cores.
            runLimiter.withPermit {
                val loaded = session ?: return@withPermit List(texts.size) { null }
                val tok = tokenizer ?: return@withPermit List(texts.size) { null }
                val out = arrayOfNulls<FloatArray>(texts.size)
                for (start in texts.indices step batchSize) {
                    val end = minOf(start + batchSize, texts.size)
                    // A chunk that fails leaves its entries null — "no opinion" per
                    // item — rather than nulling the whole batch.
                    val startedAt = System.nanoTime()
                    runCatching { runChunk(loaded, tok, texts.subList(start, end), out, start) }
                        .onFailure { error ->
                            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                            if (error is CancellationException) {
                                // Not a failure: the caller went away. Propagate, because
                                // swallowing it here would run every remaining chunk against
                                // a cancelled job and log one "failure" per chunk.
                                Log.d(
                                    TAG,
                                    "embed cancelled at chunk $start..${end - 1} of ${texts.size} after ${elapsedMs}ms: ${error.reason()}",
                                )
                                throw error
                            }
                            // The elapsed time is the tell: a chunk that fails at the
                            // watchdog budget is a timeout, whatever ORT calls it.
                            Log.w(
                                TAG,
                                "embed batch failed at chunk $start..${end - 1} of ${texts.size} after ${elapsedMs}ms " +
                                    "(limit ${INFERENCE_TIMEOUT_MS}ms): ${error.reason()}",
                                error,
                            )
                        }
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
                // Real cancellation. A Kotlin cancel only interrupts the *thread*, which
                // ORT's native run ignores; the only thing that stops the work is ORT's
                // own cooperative terminate, keyed by a run tag. So a watchdog child
                // calls `setTerminate` when the run is cancelled (timeout or a clean
                // kill), and ORT aborts between operators — no leaked native work.
                val options = OrtSession.RunOptions().apply { setRunTag("ort-${System.nanoTime()}") }
                try {
                    val result =
                        withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                            coroutineScope {
                                val watchdog =
                                    launch(Dispatchers.Default) {
                                        try {
                                            awaitCancellation()
                                        } finally {
                                            runCatching { options.setTerminate(true) }
                                        }
                                    }
                                try {
                                    loaded.run(mapOf(INPUT_IDS to idTensor, ATTENTION_MASK to maskTensor), options)
                                } finally {
                                    watchdog.cancel()
                                }
                            }
                        }
                    if (result == null) {
                        Log.w(TAG, "inference timed out after ${INFERENCE_TIMEOUT_MS}ms at offset $offset (${chunk.size} texts); chunk left untagged")
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
                } finally {
                    options.close()
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
        const val INFERENCE_TIMEOUT_MS = 60_000L

        /**
         * Concurrent inference runs. **1** — one at a time. Two concurrent runs on the
         * one session fight for the phone's cores and each slows past the timeout; the
         * serialized run is ~1s and needs no bound.
         */
        const val CONCURRENT_RUNS = 1
    }
}

/**
 * The exception in one line, so a log *summary* carries the cause. The stack is
 * still passed to Log; this is what makes `grep "batch failed"` readable when the
 * stack has been interleaved with another thread's.
 */
private fun Throwable.reason(): String = "${this::class.simpleName}: ${message ?: "(no message)"}"

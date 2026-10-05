package com.personalos.app.data

import com.personalos.mlsdk.ModelSpec

/**
 * The models this app knows how to ask the SDK for.
 *
 * Each is a [ModelSpec] pinned to a size and sha, so the SDK can tell a good copy
 * from a truncated or foreign one.
 *
 * Both EmbeddingGemma runtimes are pinned, both 256-dim via MRL, because the SDK
 * is runtime-agnostic (it only sees files) and either can back the tagger:
 *
 *  - **ONNX q4f16** — the **active** runtime. Its `batch_size` axis is dynamic, so
 *    `embedMany` sends a chunk in one run; that is what makes re-tagging a whole
 *    store tractable. 176 MB, three files (graph + external weights + tokenizer).
 *  - **TFLite** (mofy's export) — kept as the alternative runtime: 179 MB, but a
 *    fixed `[1, 256]`, so it cannot batch. Not fetched by default.
 *
 * [embeddingGemmaBundle] is the **active** set, so `ModelStore.evictUnlisted` only
 * keeps what is in use: the inactive runtime's files are orphans once a device has
 * both. Switching runtimes is a bundle swap here, nothing in the SDK.
 */
object KnownModels {
    /** The ONNX graph; its weights are the `.onnx_data` sibling below, resolved relative to it. */
    val embeddingGemmaOnnx =
        ModelSpec(
            name = "embeddinggemma",
            version = 2,
            fileName = "model_q4f16.onnx",
            url = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main/onnx/model_q4f16.onnx",
            sizeBytes = 705_221L,
            sha256 = "4df4a2a44253865800b8882a497badf67c2707a487267460813f78da339c753f",
            dim = 256,
        )

    /**
     * The external weights. The name is **load-bearing**: the graph stores this
     * relative location, and ONNX Runtime resolves it beside the graph, so it must
     * be downloaded under exactly this name.
     */
    val embeddingGemmaOnnxData =
        ModelSpec(
            name = "embeddinggemma",
            version = 2,
            fileName = "model_q4f16.onnx_data",
            url = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main/onnx/model_q4f16.onnx_data",
            sizeBytes = 175_410_176L,
            sha256 = "c9cc456a345e6aa9bc5fb75b54c10b3e0edbb4f80708f749dc4c45dbed5b6edf",
            dim = null,
        )

    val embeddingGemmaOnnxTokenizer =
        ModelSpec(
            name = "embeddinggemma",
            version = 2,
            fileName = "embeddinggemma_onnx_tokenizer.json",
            url = "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main/tokenizer.json",
            sizeBytes = 20_323_312L,
            sha256 = "4dda02faaf32bc91031dc8c88457ac272b00c1016cc679757d1c441b248b9c47",
            dim = null,
        )

    /** Everything the ONNX embedder needs, in fetch order: tokenizer, graph, then weights. */
    val embeddingGemmaBundle: List<ModelSpec> = listOf(embeddingGemmaOnnxTokenizer, embeddingGemmaOnnx, embeddingGemmaOnnxData)

    /**
     * The tag vocabulary, downloadable so a new leaf or description reaches a device
     * without an app release. Bundled at `assets/taxonomy/taxonomy.v1.json`; the
     * downloaded copy layers over it. Publish the same bytes at [url] before release.
     */
    val taxonomy =
        ModelSpec(
            name = "taxonomy",
            version = 1,
            fileName = "taxonomy.v1.json",
            url = "https://raw.githubusercontent.com/ikouchiha47/rooster/main/android/app/src/main/assets/taxonomy/taxonomy.v1.json",
            sizeBytes = 5_846L,
            sha256 = "dfe511cde7aadbf4b2529114f21755e3fb59de69991d4997de570fca4592d04f",
            dim = null,
        )

    /** The vocabulary bundle the Settings screen fetches. */
    val vocabularyBundle: List<ModelSpec> = listOf(taxonomy)

    /** mofy's TFLite export (256-dim, sequence 256); the shared/fallback runtime. */
    val tfliteEmbeddingGemma =
        ModelSpec(
            name = "embeddinggemma",
            version = 1,
            fileName = "mofy_embedder_seq256.tflite",
            url = "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/mofy_embedder_seq256.tflite",
            sizeBytes = 179_131_736L,
            sha256 = "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5",
            dim = 256,
        )

    val tfliteEmbeddingGemmaTokenizer =
        ModelSpec(
            name = "embeddinggemma",
            version = 1,
            fileName = "embeddinggemma_tokenizer.json",
            url = "https://github.com/ikouchiha47/mofy/releases/download/v0.1-facet/embeddinggemma_tokenizer.json",
            sizeBytes = 33_385_008L,
            sha256 = "6852f8d561078cc0cebe70ca03c5bfdd0d60a45f9d2e0e1e4cc05b68e9ec329e",
            dim = null,
        )

    /** mofy's bundle, in fetch order: tokenizer, then model. */
    val tfliteEmbeddingGemmaBundle: List<ModelSpec> = listOf(tfliteEmbeddingGemmaTokenizer, tfliteEmbeddingGemma)
}

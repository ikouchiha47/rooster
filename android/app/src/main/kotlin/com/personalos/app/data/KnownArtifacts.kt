package com.personalos.app.data

import com.personalos.mlsdk.Artifact
import com.personalos.mlsdk.ArtifactFile
import com.personalos.mlsdk.ArtifactType
import com.personalos.mlsdk.FileRole
import com.personalos.mlsdk.Scope
import com.personalos.mlsdk.Sources

/**
 * Everything this app declares to the SDK.
 *
 * The SDK is agnostic — it knows files, hashes, directories and references, and
 * nothing about embedders or vocabularies. This file is where the app's knowledge
 * lives, and **only** the app's: which artifacts it uses, where each comes from,
 * and what each file is for.
 *
 * Each file is an [ArtifactFile] pinned to a size and sha, so the SDK can tell a
 * good copy from a truncated or foreign one, and carries a [FileRole] so the
 * runtime asks for "the graph" instead of restating a file name.
 *
 * **Where they live.** An [Artifact] owns a directory, `<scope>/<name>`, and the
 * scope is declared *here* — the SDK has no idea this app is called PersonalOS:
 *
 *  - [embeddingGemma] is [Scope.GLOBAL]: it is not retrained, and other apps may
 *    share it.
 *  - [vocabulary] is a **dataset**, not a model, so it is [personalos] — this
 *    app's own tag vocabulary, at `personalos/taxonomy/`. A retrained embedder
 *    would go there too.
 */
object KnownArtifacts {
    /** This app's own scope: artifacts it curates or retrains, not shared copies. */
    val personalos: Scope = Scope.of("personalos")

    /** The ONNX graph. Its weights sit beside it; see [embeddingGemmaWeights]. */
    val embeddingGemmaGraph =
        ArtifactFile(
            name = "embeddinggemma",
            version = 2,
            fileName = "model_q4f16.onnx",
            url = Sources.huggingFace("onnx-community/embeddinggemma-300m-ONNX", "onnx/model_q4f16.onnx"),
            sizeBytes = 705_221L,
            sha256 = "4df4a2a44253865800b8882a497badf67c2707a487267460813f78da339c753f",
            role = FileRole.GRAPH,
            dim = 256,
        )

    /**
     * The external weights. The name is **load-bearing**: the graph stores this
     * relative location and resolves it beside itself, which is what a per-artifact
     * directory guarantees.
     */
    val embeddingGemmaWeights =
        ArtifactFile(
            name = "embeddinggemma",
            version = 2,
            fileName = "model_q4f16.onnx_data",
            url = Sources.huggingFace("onnx-community/embeddinggemma-300m-ONNX", "onnx/model_q4f16.onnx_data"),
            sizeBytes = 175_410_176L,
            sha256 = "c9cc456a345e6aa9bc5fb75b54c10b3e0edbb4f80708f749dc4c45dbed5b6edf",
            role = FileRole.WEIGHTS,
        )

    val embeddingGemmaTokenizer =
        ArtifactFile(
            name = "embeddinggemma",
            version = 2,
            fileName = "embeddinggemma_onnx_tokenizer.json",
            url = Sources.huggingFace("onnx-community/embeddinggemma-300m-ONNX", "tokenizer.json"),
            sizeBytes = 20_323_312L,
            sha256 = "4dda02faaf32bc91031dc8c88457ac272b00c1016cc679757d1c441b248b9c47",
            role = FileRole.TOKENIZER,
        )

    /**
     * The active embedder, at `global/embeddinggemma/`. Its `batch_size` axis is
     * dynamic, so `embedMany` sends a chunk in one run — which is what makes
     * re-tagging the whole store tractable. 176 MB, three files.
     */
    val embeddingGemma: Artifact =
        Artifact.of(
            name = "embeddinggemma",
            version = 2,
            files = listOf(embeddingGemmaTokenizer, embeddingGemmaGraph, embeddingGemmaWeights),
            type = ArtifactType.EMBEDDER,
        )

    /**
     * The tag vocabulary as data — leaves, groups, descriptions. Bundled at
     * `assets/taxonomy/taxonomy.v1.json`; this copy layers over it, so a new leaf
     * or description reaches a device without an app release.
     */
    val taxonomy =
        ArtifactFile(
            name = "taxonomy",
            version = 1,
            fileName = "taxonomy.v1.json",
            url =
                Sources.githubRelease(
                    "ikouchiha47/rooster",
                    "datasets-v1",
                    "taxonomy.v1.json",
                ),
            sizeBytes = 6_277L,
            sha256 = "3485b3ccc4e3dcbcd023c4e165326b9b4c26c3c2146c21f5b9d394755fd9e091",
            role = FileRole.DATA,
        )

    /** This app's vocabulary dataset, at `personalos/taxonomy/`. */
    val vocabulary: Artifact =
        Artifact.of(
            name = "taxonomy",
            version = 1,
            files = listOf(taxonomy),
            scope = personalos,
            type = ArtifactType.DATASET,
        )

    /**
     * The labelled examples the ML rung votes over. Bundled at
     * `assets/tagging/examples.v1.json`; a downloaded copy extends it, so a tag
     * that no bundled example covers can be taught without an app release.
     */
    val examplesFile =
        ArtifactFile(
            name = "examples",
            version = 1,
            fileName = "examples.v1.json",
            url = Sources.githubRelease("ikouchiha47/rooster", "datasets-v1", "examples.v1.json"),
            sizeBytes = 4_642L,
            sha256 = "a50f0c4f648be4815a9721272db717ec74528da8875bceda0ce45d06d2769659",
            role = FileRole.DATA,
        )

    /** This app's labelled-example dataset, at `personalos/examples/`. */
    val examples: Artifact =
        Artifact.of(
            name = "examples",
            version = 1,
            files = listOf(examplesFile),
            scope = personalos,
            type = ArtifactType.DATASET,
        )

    /**
     * The trained classifier over the frozen embedder: one logistic head per coarse
     * class, with the taxonomy tags each may emit. Absent, the ML rung falls back to
     * kNN over labelled examples; both sit behind the same `Tagger`.
     */
    val headsFile =
        ArtifactFile(
            name = "heads",
            version = 1,
            fileName = "heads.v1.json",
            url = Sources.githubRelease("ikouchiha47/rooster", "models-v1", "heads.v1.json"),
            sizeBytes = 35_731L,
            sha256 = "aa1dde748c71026b33af301691241ababd7cc9dde178bd9a388c5f85b92b9e64",
            role = FileRole.OTHER,
        )

    val heads: Artifact =
        Artifact.of(
            name = "heads",
            version = 1,
            files = listOf(headsFile),
            scope = personalos,
            type = ArtifactType.CLASSIFIER,
        )

    /**
     * The artifacts that **must** be on disk. The app fetches any that are missing,
     * so an enabled artifact absent from the device is a bug to fix, not a state to
     * live in. The dataset is deliberately *not* here: the app works fully on the
     * bundled vocabulary, so it is fetched when the user asks, not at every start.
     */
    val enabled: List<Artifact> = listOf(embeddingGemma)

    /** Everything this app declares, and registers, so another app can find it. */
    val all: List<Artifact> = enabled + listOf(vocabulary, examples, heads)
}

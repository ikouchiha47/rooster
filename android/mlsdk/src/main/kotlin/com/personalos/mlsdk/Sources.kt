package com.personalos.mlsdk

/**
 * Where a model file comes from, as a URL **composed from parts** rather than
 * pasted as a literal.
 *
 * A pasted URL hides its own structure: you cannot tell whether a model came from
 * a release asset, a raw file on a branch, or a host that has since moved — and a
 * hub that lets a user add a model needs to ask for *owner, repo, tag, asset*, not
 * for the reader to know the URL scheme.
 *
 * These build **static** paths deliberately. A release asset is public and
 * downloadable without a token, so no GitHub API call, no rate limit, and no
 * credential in the SDK. (The API is only needed to *discover* assets — listing a
 * release, or a private repo — which is a different job.)
 */
object Sources {
    /** A GitHub release asset: `releases/download/<tag>/<asset>`. */
    fun githubRelease(
        owner: String,
        repo: String,
        tag: String,
        asset: String,
    ): String = "https://github.com/$owner/$repo/releases/download/$tag/$asset"

    /** A file on a branch or commit: `raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>`. */
    fun githubRaw(
        owner: String,
        repo: String,
        ref: String,
        path: String,
    ): String = "https://raw.githubusercontent.com/$owner/$repo/$ref/$path"

    /** A Hugging Face repository file (`resolve/main/<path>`). */
    fun huggingFace(
        repo: String,
        path: String,
        revision: String = "main",
    ): String = "https://huggingface.co/$repo/resolve/$revision/$path"

    /**
     * The convenience mofy uses, spelled out: `ikouchiha47/mofy` at tag
     * `v0.1-facet` ships `mofy_embedder_seq256.tflite`.
     */
    fun githubRelease(
        coordinates: String,
        tag: String,
        asset: String,
    ): String {
        val parts = coordinates.split('/')
        require(parts.size == 2 && parts.all { it.isNotBlank() }) {
            "coordinates must be owner/repo: $coordinates"
        }
        return githubRelease(parts[0], parts[1], tag, asset)
    }
}

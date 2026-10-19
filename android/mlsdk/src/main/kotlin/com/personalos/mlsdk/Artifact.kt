package com.personalos.mlsdk

/**
 * Who a model belongs to, as the first path segment — the answer to "is this the
 * same model as that app's?":
 *
 * ```
 * Downloads/ml-models/
 *   global/embeddinggemma/     a stock or upstream model, not retrained
 *   <app>/embeddinggemma/      retrained on that app's own data
 * ```
 *
 * So a retrained embedder coexists with the stock one instead of overwriting it —
 * they differ in sha, and the scope keeps them apart on disk.
 *
 * **This library names no app.** [GLOBAL] is the one scope it can own, because it
 * genuinely means "every app". An app declares its own — `Scope.of("personalos")`
 * in PersonalOS, whatever Mofy calls itself in Mofy — so this module stays reusable
 * and an app's name never becomes an SDK constant.
 */
@JvmInline
value class Scope private constructor(
    val slug: String,
) {
    /** The directory a model of this scope and [name] occupies. */
    fun dir(name: String): String = "$slug/$name"

    override fun toString(): String = slug

    companion object {
        /** Shared by every app: stock weights, an upstream export. */
        val GLOBAL = Scope("global")

        /**
         * A scope the **caller** owns, e.g. `of("personalos")`. A single safe path
         * segment, because it becomes a directory name.
         */
        fun of(slug: String): Scope {
            require(slug.isNotBlank()) { "a scope needs a name" }
            require(!slug.startsWith("/") && !slug.contains("..") && !slug.contains('/')) {
                "unsafe scope: $slug"
            }
            require(!slug.contains('\\')) { "unsafe scope: $slug" }
            return Scope(slug)
        }
    }
}

/**
 * What a model *is*, so a consumer can ask for "the embedder" without knowing its
 * name.
 *
 * This vocabulary belongs to the SDK, unlike [Scope]: "an embedder" is a fact
 * about ML runtimes, not about any one app. An unknown slug reads as [OTHER]
 * rather than failing, so a model registered by a newer app still lists.
 */
enum class ArtifactType(
    val slug: String,
) {
    /** Turns text into vectors. */
    EMBEDDER("embedder"),

    /** Generates text. */
    GENERATIVE("generative"),

    /** Labels or classifies. */
    CLASSIFIER("classifier"),

    /** Not a model: files a consumer reads as data (a vocabulary, a directory). */
    DATASET("dataset"),

    /** Anything else — a reranker, a detector, a tokenizer bundle. */
    OTHER("other"),
    ;

    companion object {
        fun from(slug: String): ArtifactType = entries.firstOrNull { it.slug == slug } ?: OTHER
    }
}

/**
 * One artifact: the **name** you ask for it by, its **type**, the **directory** it
 * occupies inside the shared root, and the files that make it up.
 *
 * A directory per model is not tidiness — it is what makes two models coexist. The
 * ONNX graph resolves its external weights *beside itself*, so `model.onnx` and
 * `model.onnx_data` must share a folder; two models that each ship a
 * `tokenizer.json` must not. Both are free once a model owns a directory.
 *
 * [dir] is validated, because it becomes a path: a name from a manifest is
 * untrusted input, and `../..` in it would write outside the models root.
 */
data class Artifact(
    val name: String,
    val type: ArtifactType,
    val version: Int,
    /** The folder inside the shared root, e.g. `"personalos/embeddinggemma"`. */
    val dir: String,
    val files: List<ArtifactFile>,
) {
    init {
        require(files.isNotEmpty()) { "a model has at least one file" }
        require(dir.isNotBlank()) { "a model needs a directory" }
        require(!dir.startsWith("/") && !dir.contains("..")) { "unsafe model directory: $dir" }
        require(!dir.contains('\\')) { "unsafe model directory: $dir" }
    }

    /** The file with this name, or null — the lookup a runtime does. */
    fun file(fileName: String): ArtifactFile? = files.firstOrNull { it.fileName == fileName }

    /**
     * The file playing [role], or null — how a runtime asks for the graph or the
     * tokenizer without knowing a file name. Null when nothing carries the role,
     * so a consumer fails on a missing part rather than on a wrong guess.
     */
    fun fileWithRole(role: FileRole): ArtifactFile? = files.firstOrNull { it.role == role }

    /** Every file playing [role] — a model may ship more than one data file. */
    fun filesWithRole(role: FileRole): List<ArtifactFile> = files.filter { it.role == role }

    /** The names of the files this artifact owns — what `prune` must keep. */
    fun fileNames(): List<String> = files.map { it.fileName }

    /**
     * `<scope>:<name>` — how this artifact is asked for. The scope is already the
     * first segment of [dir], so a reference is the directory read backwards, and
     * there is nothing extra to keep in step.
     */
    val ref: String get() = "${scope()}:$name"

    /** Total bytes when every file is present, for a size the user can be shown. */
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }

    companion object {
        /**
         * A model living at `<scope>/<name>`.
         *
         * [scope] defaults to [Scope.GLOBAL] — **nothing is app-specific
         * unless an app says so**. An app that retrains a model passes its own
         * scope (`Scope.of("personalos")`) and gets `<app>/<name>` instead,
         * leaving the shared one alone.
         */
        fun of(
            name: String,
            version: Int,
            files: List<ArtifactFile>,
            scope: Scope = Scope.GLOBAL,
            type: ArtifactType = ArtifactType.OTHER,
        ): Artifact = Artifact(name = name, type = type, version = version, dir = scope.dir(name), files = files)
    }
}

/** The scope an artifact belongs to, read back from its directory (`<scope>/<name>`). */
fun Artifact.scope(): String = dir.substringBefore('/').takeIf { it.isNotBlank() } ?: Scope.GLOBAL.slug

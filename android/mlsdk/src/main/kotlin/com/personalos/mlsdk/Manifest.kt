package com.personalos.mlsdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class FileDto(
    val file: String,
    val url: String,
    val size: Long,
    val sha256: String,
    /** Absent in a document written before roles existed — read as `other`. */
    val role: String? = null,
    val dim: Int? = null,
)

@Serializable
private data class ArtifactDto(
    val name: String,
    val version: Int,
    val dir: String,
    val files: List<FileDto>,
    /** Absent in a document written before types existed — read as `other`. */
    val type: String? = null,
)

/**
 * **v2.** [artifacts] has no default on purpose: a v1 document has no `artifacts`
 * key, and with `ignoreUnknownKeys` a defaulted field would let v1 decode as an
 * *empty* v2 registry instead of falling through to [LegacyManifestDto].
 */
@Serializable
private data class RegistryDto(
    val version: Int = 2,
    val scopes: List<String> = emptyList(),
    val artifacts: List<ArtifactDto>,
)

/**
 * **v1**: one flat file entry per row, no directory, no scope, no role. The key is
 * spelled `models` because that is what is already on disk — renaming it here
 * would make old documents unreadable.
 */
@Serializable
private data class LegacyFileDto(
    val name: String,
    val version: Int,
    val file: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val dim: Int? = null,
)

@Serializable
private data class LegacyManifestDto(
    val version: Int = 1,
    val models: List<LegacyFileDto> = emptyList(),
)

/**
 * `manifest.json` — the persistent registry, read by every app.
 *
 * It is the written form of [ArtifactRegistry], and it lives beside the artifacts
 * in the shared folder, so it answers the question no single app can: *what exists
 * on this device, and who owns it?* An app registers here, and another app (or the
 * hub) can then find an artifact by reference without being told.
 *
 * A document written before directories and scopes existed is read as **v1** and
 * placed in [Scope.GLOBAL] — the same place its files are looked for, so nothing
 * is re-downloaded.
 */
object Manifest {
    private val json = Json { ignoreUnknownKeys = true }

    /** The registry [text] describes, or null when it is unreadable. */
    fun parse(text: String): ArtifactRegistry? =
        runCatching {
            val registry = json.decodeFromString<RegistryDto>(text)
            ArtifactRegistry(
                scopes = registry.scopes,
                artifacts = registry.artifacts.map { it.toArtifact() },
            )
        }.recoverCatching {
            val legacy = json.decodeFromString<LegacyManifestDto>(text)
            val files =
                legacy.models.map {
                    ArtifactFile(
                        name = it.name,
                        version = it.version,
                        fileName = it.file,
                        url = it.url,
                        sizeBytes = it.size,
                        sha256 = it.sha256,
                        dim = it.dim,
                    )
                }
            // A flat manifest is the state of the world before scopes: everything
            // in it is shared, so it lands in `global/`.
            ArtifactRegistry.of(ArtifactRouter.from(files).all())
        }.getOrNull()

    /** The registry as a document, ready to write beside the artifacts. */
    fun write(registry: ArtifactRegistry): String =
        json.encodeToString(
            RegistryDto(
                scopes = registry.scopes,
                artifacts =
                    registry.artifacts.map { artifact ->
                        ArtifactDto(
                            name = artifact.name,
                            version = artifact.version,
                            dir = artifact.dir,
                            type = artifact.type.slug,
                            files =
                                artifact.files.map {
                                    FileDto(
                                        file = it.fileName,
                                        url = it.url,
                                        size = it.sizeBytes,
                                        sha256 = it.sha256,
                                        role = it.role.slug,
                                        dim = it.dim,
                                    )
                                },
                        )
                    },
            ),
        )
}

private fun ArtifactDto.toArtifact(): Artifact =
    Artifact(
        name = name,
        type = ArtifactType.from(type ?: ArtifactType.OTHER.slug),
        version = version,
        dir = dir,
        files =
            files.map {
                ArtifactFile(
                    name = name,
                    version = version,
                    fileName = it.file,
                    url = it.url,
                    sizeBytes = it.size,
                    sha256 = it.sha256,
                    role = FileRole.from(it.role ?: FileRole.OTHER.slug),
                    dim = it.dim,
                )
            },
    )

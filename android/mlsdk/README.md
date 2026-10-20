# mlsdk — shared on-device artifacts

Locate, fetch, verify and share on-device ML files. A plain Android library with
no opinion about *what* an artifact does — it knows files, hashes, directories and
references — so the same module works in PersonalOS, Mofy, or a small hub app
whose only job is to download once.

**The SDK names no app and no artifact.** What an embedder is, which model to use,
what this app declares — all of that lives in the app that consumes the SDK. This
module knows the *shape* every artifact has, and nothing else.

## Vocabulary

```kotlin
ArtifactFile(name, version, fileName, url, sizeBytes, sha256, role, dim)
Artifact    (name, type, version, dir, files)      // dir is "<scope>/<name>"
ArtifactType{ EMBEDDER, GENERATIVE, CLASSIFIER, DATASET, OTHER }
FileRole    { GRAPH, WEIGHTS, TOKENIZER, DATA, OTHER }
Scope       (GLOBAL, of("personalos"))             // apps declare their own
ref          = "name" | "scope:name"               // Artifact.ref
```

- `FileRole` exists so a runtime asks for **the graph**, not for
  `model_q4f16.onnx`. A file name has exactly one owner: `ArtifactFile.fileName`.
- `ArtifactType` exists so a consumer asks for **the embedder**, not for a name.
- `DATASET` is how a non-model (a vocabulary, a directory) registers without
  pretending to be a model.

## The directory

```
<external storage>/Downloads/ml-models/
  manifest.json                 a registry written before scopes existed (read only)
  global/
    manifest.json               what every app shares
    embeddinggemma/
      model_q4f16.onnx
      model_q4f16.onnx_data
      embeddinggemma_onnx_tokenizer.json
  personalos/
    manifest.json               what this app registered
    taxonomy/                   a dataset, not a model
```

**A directory per artifact** is not tidiness. ONNX resolves its external weights
*beside the graph*, so the two cannot be separated; and two artifacts that each
ship a `tokenizer.json` must not collide.

**`global` is the default.** An app that declares nothing app-specific lands in
`global/<name>`. A retrained copy, or a dataset, takes the app's own scope —
`personalos/<name>` — and **coexists** with the shared one, because everything is
keyed by `scope:name`, never by a bare name.

A read checks, in order: the writable root's per-artifact directory, the writable
root itself (where files used to land flat), the legacy root
`PersonalOS/ml/models`, then the app's private `files/models`. Moving the root
never strands an artifact already on disk.

## The interface

```kotlin
val hub = Hub(context, appDeclarations)

// lookup — never touches the network
hub.router().artifact("embeddinggemma")             // the shared one
hub.router().embedder("personalos:embeddinggemma")  // the app's own copy
hub.router().dataset("personalos:vocabulary")
hub.list()                                          // everything on this device
hub.status(ref)                                     // ABSENT | PARTIAL | STALE | READY
hub.files(ref)                                      // Map<ArtifactFile, File?>
hub.path(ref)                                       // the directory

// plan, then fetch
hub.plan(refs)                                      // what would download
hub.ensure(ref)                                     // complete directory, or throws
hub.ensure(ref, localOnly = true)                   // refuse the network
hub.download(refs)                                  // via the foreground service

// maintain
hub.register()                                      // persist per scope, for other apps
hub.remove(ref)
hub.prune()                                         // reclaim what the SDK owns
```

Three guarantees worth stating:

- **A set is complete or it fails.** `ensure` returns a directory with every file
  present and verified, or throws — never a partial artifact, which is what leaves
  a runtime loading a half-downloaded model.
- **Lookups never fetch.** `status`, `files`, `path` and `plan` read the disk only,
  so a screen can ask what is here without spending data.
- **Unprefixed means `global`, and only that.** A miss is `null`, not a fallback to
  an app's private copy: adding `personalos:embeddinggemma` must not silently
  change what `artifact("embeddinggemma")` means.

## Downloads

- **Resumable** — bytes stream into `<name>.part`; a retry sends `Range:` from the
  partial length, and a `416` restarts clean.
- **Verified before published** — size and SHA-256 are checked on the `.part`;
  only then is it renamed into place. A reject deletes the `.part`.
- **Verified once, then remembered.** When the bytes and the hash agree, a
  `<file>.verified` marker records the spec's size + SHA-256. Every later start
  then answers *"is this the artifact?"* with a `stat` instead of re-reading
  167 MB; a file that has changed fails the marker and pays for one fresh hash.
  Re-verifying a 300M-parameter model on every launch is minutes of IO for an
  answer that cannot have changed.
- **Published size is confirmed.** After the `.part` → final rename the downloader
  waits until the final path reports the exact expected size. A FUSE-backed shared
  folder can briefly report the *old* length at the new path, and ONNX Runtime
  reads its external-data length with a `stat` — without this check it opens a
  short weights file and fails with "external initializer out of bounds".
- **Content-checked first** — empty bodies, HTML error pages (by first byte *and*
  `Content-Type`), and files without the TFLite `TFL3` / ONNX `0x08` magic bytes
  are rejected before hashing.
- **Foreground, not WorkManager** — the user is watching a progress bar, and a
  176 MB transfer must not be killed when the screen goes dark. `DownloadService`
  starts foreground immediately, holds a CPU wake lock only for the transfer
  (capped at 30 minutes), throttles its notification independently of the progress
  stream — Android sheds `notify()` calls, and will drop the final "complete" — and
  reports every tick on `DownloadEvents`.
- **No force flag.** Correctness is size + SHA-256, so a verified file *is* the
  artifact; there is no stale-but-valid state to re-fetch. A changed source is a
  new version.

## The registry

`manifest.json`, one per scope, written only by the app that owns that scope. Per
scope rather than one shared file, so two apps registering at once do not
read-modify-write over each other: there is no lock to take and no update to lose.
A reader merges whatever it finds, which is how one app finds another's download
without being told.

## Permissions the host app needs

| permission | why |
|---|---|
| `INTERNET` | fetch |
| `MANAGE_EXTERNAL_STORAGE` | the shared folder. Without it the SDK uses the private `files/models` — it still works, just not shared |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | `DownloadService` |
| `POST_NOTIFICATIONS` (API 33+) | the download notification |
| `WAKE_LOCK` | held only while a transfer runs, capped |

Declared in this module's manifest; the app still requests the runtime ones.

## Adding an artifact by hand

Drop a file into the right directory under the shared root, and give it a
`manifest.json` entry (or a declaration in the app) carrying its real size and
SHA-256 — otherwise it is reported `STALE` and replaced. `prune` never deletes a
complete file from the shared directory for this reason: it cannot know which
unrecognised files are yours.

## What this module does not do

- **Run artifacts.** No ONNX Runtime, no TFLite, no tokenizers — so two apps can
  pick different runtimes for the same verified bytes.
- **Store progress durably.** `DownloadEvents` is in-process. An app that wants a
  persisted "downloading / failed" state keeps it itself.
- **Delete your files.** See `prune` above.

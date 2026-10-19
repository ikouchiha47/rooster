# mlsdk — shared on-device models

Locate, fetch, verify and share on-device ML models. A plain Android library with
no opinion about *what* the models do — it only knows files — so the same module
works in PersonalOS, Mofy, or a small hub app whose only job is to download once.

## Why a module

Three apps, one 176 MB embedder. Without a shared SDK each one downloads its own
copy, each has its own "is this file the model?" check, and a fix to the download
resume logic has to be made three times. Here the contract is one `ModelSpec`
(file name, size, SHA-256) and one directory convention.

## The shared directory

```
<external storage>/Downloads/ml-models/
```

Readable and writable by every app that holds `MANAGE_EXTERNAL_STORAGE`, and
visible to the user in a file manager. That is deliberate: a model placed there by
hand is found and used with no download and no app involvement.

Resolution order (`ModelPaths.lookup`) — **a model is found wherever it already
is**, so moving the root never strands an existing download:

1. the shared dir above (writable);
2. `<external storage>/PersonalOS/ml/models` — the root this SDK used before, kept
   as a read path only;
3. the app's private `files/models` (used as the write path when all-files access
   is not granted).

`ModelPaths.dir()` is where new downloads go. `ModelPaths.resolve(name)` finds the
first existing copy across the three.

### Custom models

A custom model is a file you drop into the shared folder under the name a
`ModelSpec` (or `manifest.json`) declares. Because the read path checks all three
roots, it needs no code change and no download.

**Integrity caveat:** verification is exact `size + SHA-256`. A model you built
yourself needs a spec with its real size and hash, or it will be reported as
`STALE` and replaced. Publishing a `manifest.json` (see `ModelManifest.write`) is
the intended way to share one.

`ModelStore.evictUnlisted` never deletes a complete file from the shared folder —
only abandoned `.part` files. It cannot assume a file it does not recognise is
garbage, because a custom model is exactly that.

## Three entry points

```kotlin
// 1. The fast path: locate, verify, fetch-if-needed, return a ready file.
val file = ModelStore(context).ensure(spec) { written, total -> /* progress */ }

// 2. The durable path: a foreground service, so a 176 MB transfer survives the
//    app being backgrounded. Progress also arrives on ModelDownloadEvents.
ModelDownloadService.start(context, KnownModels.embeddingGemmaBundle)

// 3. The contract: publish or read what exists, shared between apps.
val json = ModelManifest.write(specs)          // writer
val specs = ModelManifest.parse(json)          // reader, null if unreadable
```

`ModelStore.ensure` is idempotent and safe to call on every start. It hashes a file
only when its size already matches, so the common "already downloaded" case is a
`stat`.

## Downloads

- **Resumable.** Bytes stream into `<name>.part`; a retry sends `Range:` from the
  partial length. A `416` (the partial is longer than the file) restarts clean.
- **Verified before published.** Size and SHA-256 are checked on the `.part`;
  only then is it renamed into place. A reject deletes the `.part`, so a wedged
  resume cannot survive.
- **Content-checked first.** Empty bodies, HTML error pages (GitHub returns `200`
  with a "not found" page), and files without the TFLite `TFL3` / ONNX `0x08`
  magic bytes are rejected before hashing.
- **Foreground, not WorkManager.** The user is watching a progress bar; the work
  must not be killed when the screen goes dark. See `ModelDownloadService`.

## Permissions the host app needs

| permission | why |
|---|---|
| `INTERNET` | fetch models |
| `MANAGE_EXTERNAL_STORAGE` | the shared folder. Without it the SDK silently uses the private `files/models` — it still works, just not shared |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | `ModelDownloadService` |
| `POST_NOTIFICATIONS` (API 33+) | the download notification |
| `WAKE_LOCK` | the transfer is held only while it runs, capped at 30 min |

Declared in this module's manifest; the app still requests the runtime ones.

## What this module does not do

- **Run models.** No ONNX Runtime, no TFLite, no tokenizers. That keeps the
  dependency surface (and the APK) small, and lets two apps pick different runtimes
  for the same verified bytes.
- **Store progress durably.** `ModelDownloadEvents` is in-process. An app that
  wants a persisted "downloading / failed" state writes it itself.
- **Delete the user's files.** See `evictUnlisted` above.

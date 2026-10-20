#!/usr/bin/env bash
# Publish a model file as a GitHub release asset, and print the declaration the
# app needs. The binary never enters git: it lives only on the release.
#
#   tools/publish-model.sh <file> [tag] [asset] [name] [version] [role]
#
# Defaults: tag=models-v1, asset=basename, name=<basename without .onnx/_data>,
# version=1, role inferred from the extension (.onnx=GRAPH, .onnx_data=WEIGHTS,
# .json=TOKENIZER).
#
# Why: the SDK pins size + SHA-256, so the app must be told exactly what was
# uploaded. This prints both, plus a paste-ready ArtifactFile(...).
set -euo pipefail

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

DRY_RUN=0
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=1
  shift
fi

[ $# -ge 1 ] || die "usage: $0 [--dry-run] <file> [tag] [asset] [name] [version] [role]"
FILE=$1
[ -f "$FILE" ] || die "no such file: $FILE"
[ -s "$FILE" ] || die "file is empty: $FILE"

BASENAME=$(basename "$FILE")
TAG=${2:-models-v1}
ASSET=${3:-$BASENAME}
NAME=${4:-${BASENAME%%.onnx*}}
VERSION=${5:-1}

case "${6:-}" in
  GRAPH|WEIGHTS|TOKENIZER|DATA|OTHER) ROLE=$6 ;;
  "") case "$BASENAME" in
        *.onnx_data) ROLE=WEIGHTS ;;
        *.onnx)      ROLE=GRAPH ;;
        *.json)      ROLE=TOKENIZER ;;
        *)           ROLE=DATA ;;
      esac ;;
  *) die "role must be GRAPH, WEIGHTS, TOKENIZER, DATA or OTHER" ;;
esac

# --- repo, from origin, so nothing is hard-coded -----------------------------
URL=$(git remote get-url origin 2>/dev/null) || die "not a git repo, or no origin"
REPO=$(printf '%s' "$URL" | sed -E 's#^.*github\.com[:/]##; s#\.git$##')
case "$REPO" in
  */*) ;;
  *) die "could not read owner/repo from origin: $URL" ;;
esac
OWNER=${REPO%%/*}
REPO_NAME=${REPO##*/}

# --- sanity: this is a model, not an error page ------------------------------
SIZE=$(wc -c <"$FILE" | tr -d ' ')
[ "$SIZE" -gt 0 ] || die "file is empty"
[ "$SIZE" -lt 2147483648 ] || die "larger than GitHub's 2 GB per-asset limit"
if [ "${SIZE}" -gt 0 ]; then
  FIRST_WS=$(LC_ALL=C head -c 64 "$FILE" | tr -d '[:space:]' | cut -c1)
  [ "$FIRST_WS" != "<" ] || die "looks like an HTML page, not a model"
fi
case "$ASSET" in
  *" "*) die "asset names cannot contain spaces; pass one as the third argument" ;;
esac
if [ "$ROLE" = "GRAPH" ]; then
  BYTE=$(LC_ALL=C head -c 1 "$FILE" | od -An -tu1 | tr -d ' ')
  [ "$BYTE" = "8" ] || die "not a protobuf/ONNX file (first byte $BYTE, expected 8)"
fi

command -v gh >/dev/null || die "gh is not installed"
gh auth status >/dev/null 2>&1 || die "gh is not authenticated (run: gh auth login)"

# --- checksum ---------------------------------------------------------------
if command -v sha256sum >/dev/null; then
  SHA=$(sha256sum "$FILE" | cut -d' ' -f1)
else
  SHA=$(shasum -a 256 "$FILE" | cut -d' ' -f1)
fi

# --- upload (idempotent) -----------------------------------------------------
if [ "$DRY_RUN" = 1 ]; then
  printf 'dry run — would upload %s (%s bytes) to %s/%s @ %s\n' \
    "$ASSET" "$SIZE" "$OWNER" "$REPO_NAME" "$TAG"
else
  gh release view "$TAG" >/dev/null 2>&1 || \
    gh release create "$TAG" --title "$TAG" --notes "Model assets. Binaries are published here, never committed."

  printf 'uploading %s (%s bytes) to %s/%s @ %s\n' "$ASSET" "$SIZE" "$OWNER" "$REPO_NAME" "$TAG"
  gh release upload "$TAG" "$FILE" --clobber
fi

URL="https://github.com/$OWNER/$REPO_NAME/releases/download/$TAG/$ASSET"

cat <<EOF

published: $URL
  size:    ${SIZE}L
  sha256:  $SHA

paste into KnownArtifacts (adjust the artifact name if this is not its own model):

    ArtifactFile(
        name = "$NAME",
        version = $VERSION,
        fileName = "$ASSET",
        url = Sources.githubRelease("$OWNER/$REPO_NAME", "$TAG", "$ASSET"),
        sizeBytes = ${SIZE}L,
        sha256 = "$SHA",
        role = FileRole.$ROLE,
    )

EOF

#!/usr/bin/env bash
# Demo script for Person A's half of the Mini Distributed Cloud Object Store.
#
# Start the controller first, with no Docker and no real workers needed:
#   mvn -pl controller spring-boot:run -Dspring-boot.run.profiles=fake
#
# Then run this script against it, optionally naming your own file to upload:
#   bash docs/demo-A.sh
#   bash docs/demo-A.sh /path/to/your/file.pdf
#
# Covers:
#   1. Store/retrieve an object with matching SHA-256 (end to end integrity)
#   2. Chunking and distribution (file split into >1 chunk, placed across workers)
#   3. Replication (2 copies per chunk, on different workers)
#   4. Metadata read and modify, with no re-upload

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

SOURCE_FILE="${1:-}"

if [ -n "$SOURCE_FILE" ]; then
  if [ ! -f "$SOURCE_FILE" ]; then
    echo "No such file: $SOURCE_FILE" >&2
    exit 1
  fi
  echo "== Using your file: $SOURCE_FILE =="
  ORIGINAL_FILE="$SOURCE_FILE"
else
  echo "== No file given, generating a ~2.5 MB random test file (forces multiple chunks) =="
  echo "   (pass a path to use your own: bash docs/demo-A.sh /path/to/file)"
  dd if=/dev/urandom of="$WORKDIR/original.bin" bs=1024 count=2560 status=none
  ORIGINAL_FILE="$WORKDIR/original.bin"
fi

ORIGINAL_SHA256=$(sha256sum "$ORIGINAL_FILE" | awk '{print $1}')
echo "original sha256: $ORIGINAL_SHA256"

echo
echo "== 1 & 2 & 3: Upload (store, chunk, replicate) =="
UPLOAD_RESPONSE=$(curl -sS -X POST "$BASE_URL/api/v1/objects" \
  -F "file=@$ORIGINAL_FILE" \
  -F 'metadata={"owner":"demoA","purpose":"cloud-demo"}')
echo "$UPLOAD_RESPONSE"
OBJECT_ID=$(echo "$UPLOAD_RESPONSE" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
UPLOAD_CHECKSUM=$(echo "$UPLOAD_RESPONSE" | sed -n 's/.*"checksum":"\([^"]*\)".*/\1/p')
echo "object id: $OBJECT_ID"

if [ "$ORIGINAL_SHA256" != "$UPLOAD_CHECKSUM" ]; then
  echo "MISMATCH: controller-reported checksum does not match the local file!" >&2
  exit 1
fi
echo "checksum matches the controller's own record."

echo
echo "== 2 & 3: Inspect chunking and replica placement =="
curl -sS "$BASE_URL/api/v1/objects/$OBJECT_ID/metadata" | tee "$WORKDIR/metadata.json"
echo
echo "(look at chunkCount > 1, and 2 distinct workerIds per chunk's replicas above)"

echo
echo "== 1: Download and verify the round trip =="
curl -sS "$BASE_URL/api/v1/objects/$OBJECT_ID" -o "$WORKDIR/downloaded.bin"
DOWNLOADED_SHA256=$(sha256sum "$WORKDIR/downloaded.bin" | awk '{print $1}')
echo "downloaded sha256: $DOWNLOADED_SHA256"
if [ "$ORIGINAL_SHA256" != "$DOWNLOADED_SHA256" ]; then
  echo "MISMATCH: downloaded file does not match the original!" >&2
  exit 1
fi
echo "round trip OK: original == downloaded."

echo
echo "== 4: Read and modify metadata without re-uploading =="
curl -sS -X PATCH "$BASE_URL/api/v1/objects/$OBJECT_ID/metadata" \
  -H "Content-Type: application/json" \
  -d '{"owner": null, "reviewed": "true"}'
echo
echo "(owner removed, reviewed added, modifiedAt changed, chunks untouched above)"

echo
echo "== List objects =="
curl -sS "$BASE_URL/api/v1/objects?page=0&size=10"
echo

echo
echo "== Clean up: delete the object =="
curl -sS -o /dev/null -w "DELETE status: %{http_code}\n" -X DELETE "$BASE_URL/api/v1/objects/$OBJECT_ID"
curl -sS -o /dev/null -w "GET after delete status (expect 404): %{http_code}\n" "$BASE_URL/api/v1/objects/$OBJECT_ID"

echo
echo "Demo complete."

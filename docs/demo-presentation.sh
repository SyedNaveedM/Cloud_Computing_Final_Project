#!/usr/bin/env bash
#
# One-stop live presentation demo for Mini Distributed Cloud Object Store.
#
# Builds the images, brings up the whole stack (1 controller + 4 workers), waits
# for it to be healthy, and then walks through, in order, the six demonstrations
# required by PDF section 10:
#   1. Object Storage          - upload/download, verify the bytes match
#   2. Chunking & Distribution - upload something >1MB, show chunk layout + worker spread
#   3. Replication             - show each chunk has two copies, on two different workers
#   4. Metadata                - upload custom metadata, read it back, patch it without re-upload
#   5. Worker Failure          - docker stop a holder, show it still serves, show it heals, restart it
#   6. Data Integrity          - corrupt a replica on disk, show detection + correct-copy serving + repair
#
# Meant to be run live in front of an audience: every HTTP call and its result is
# printed, with a plain-English explanation before each stage. By default it narrates
# each stage and keeps moving on its own after a short pause - no key presses needed.
# Pass -manual if you want to control pacing yourself by pressing Enter instead.
#
# Usage (run from the project root - this is the only command you need):
#   ./docs/demo-presentation.sh
#
# Options:
#   -manual               Pause and wait for Enter before each stage (instead of auto-pacing)
#   -pause-seconds=N      Seconds to pause before each stage when not -manual (default 3)
#   -no-build             Skip the docker image build (assumes images already built)
#   -controller-url=URL   Override the controller base URL (default http://localhost:8081)
#
# Requires: Docker (with the daemon running), curl, python3. Builds and starts the
# project's docker compose stack itself - nothing else needs to be running first.

set -uo pipefail

CONTROLLER_URL='http://localhost:8081'
MANUAL=false
SKIP_BUILD=false
PAUSE_SECONDS=3
for arg in "$@"; do
    case "$arg" in
        -manual|-interactive|-i) MANUAL=true ;;
        -no-build|-skip-build) SKIP_BUILD=true ;;
        -pause-seconds=*) PAUSE_SECONDS="${arg#*=}" ;;
        -controller-url=*) CONTROLLER_URL="${arg#*=}" ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
EVIDENCE_DIR="$PROJECT_DIR/data/presentation-demo-$RUN_ID"
mkdir -p "$EVIDENCE_DIR"
HELPER="$EVIDENCE_DIR/_json_helper.py"
STAGE_NUM=0

for tool in curl docker python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Missing required tool: $tool" >&2; exit 1; }
done

CYAN=$'\033[0;36m'; YELLOW=$'\033[0;33m'; GREEN=$'\033[0;32m'; GRAY=$'\033[0;90m'; NC=$'\033[0m'

# -------------------------------- JSON helper (python, no jq needed)

cat > "$HELPER" <<'PYEOF'
import json, sys

def load(path):
    with open(path) as f:
        return json.load(f)

mode = sys.argv[1]
file = sys.argv[2]
rest = sys.argv[3:]

if mode == "field":
    d = load(file)
    v = d[rest[0]]
    print(v if isinstance(v, (str, int, float)) else json.dumps(v))
elif mode == "custom_json":
    d = load(file)
    print(json.dumps(d.get("customMetadata", {})))
elif mode == "custom_get":
    d = load(file)
    print(d.get("customMetadata", {}).get(rest[0], ""))
elif mode == "chunk_rows":
    d = load(file)
    for c in sorted(d["chunks"], key=lambda c: c["index"]):
        workers = ",".join(r["workerId"] for r in c["replicas"])
        print(f'{c["index"]}\t{c["chunkId"]}\t{c["size"]}\t{workers}')
elif mode == "worker_rows":
    d = load(file)
    for w in sorted(d["workers"], key=lambda w: w["workerId"]):
        print(f'{w["workerId"]}\t{w["status"]}\t{w.get("lastHeartbeat","")}\t{w.get("chunkCount","")}')
elif mode == "alive_count":
    d = load(file)
    print(sum(1 for w in d["workers"] if w["status"] == "ALIVE"))
elif mode == "worker_status":
    d = load(file)
    wid = rest[0]
    print(next((w["status"] for w in d["workers"] if w["workerId"] == wid), "UNKNOWN"))
elif mode == "chunk_status_rows":
    d = load(file)
    oid = rest[0]
    rows = sorted((c for c in d["chunks"] if c["objectId"] == oid), key=lambda c: c["chunkIndex"])
    for c in rows:
        # Only list replicas that are currently usable (worker alive, copy verified
        # OK). A dead worker's stale record is omitted here entirely; its status
        # is shown separately in the worker status table.
        workers = ", ".join(r["workerId"] for r in c["replicas"] if r.get("usable"))
        print(f'{c["chunkIndex"]}\t{c["chunkId"]}\t{c["healthyCopies"]}\t{workers}')
elif mode == "all_healthy_min":
    d = load(file)
    oid, n = rest[0], int(rest[1])
    rows = [c for c in d["chunks"] if c["objectId"] == oid]
    ok = len(rows) == 4 and all(c["healthyCopies"] >= n for c in rows)
    print(1 if ok else 0)
elif mode == "all_healthy_exact":
    d = load(file)
    oid, n = rest[0], int(rest[1])
    rows = [c for c in d["chunks"] if c["objectId"] == oid]
    ok = len(rows) == 4 and all(c["healthyCopies"] == n for c in rows)
    print(1 if ok else 0)
elif mode == "min_healthy":
    d = load(file)
    oid = rest[0]
    rows = [c for c in d["chunks"] if c["objectId"] == oid]
    print(min((c["healthyCopies"] for c in rows), default=-1))
elif mode == "chunk_id_at":
    d = load(file)
    oid, idx = rest[0], int(rest[1])
    c = next(c for c in d["chunks"] if c["objectId"] == oid and c["chunkIndex"] == idx)
    print(c["chunkId"])
elif mode == "workers_for_chunk":
    d = load(file)
    oid, idx = rest[0], int(rest[1])
    c = next(c for c in d["chunks"] if c["objectId"] == oid and c["chunkIndex"] == idx)
    print(",".join(r["workerId"] for r in c["replicas"] if r.get("usable")))
elif mode == "pick_usable_worker":
    d = load(file)
    oid = rest[0]
    c = next(c for c in d["chunks"] if c["objectId"] == oid)
    print(next(r["workerId"] for r in c["replicas"] if r.get("usable")))
elif mode == "pick_chunk_id":
    d = load(file)
    oid = rest[0]
    c = next(c for c in d["chunks"] if c["objectId"] == oid)
    print(c["chunkId"])
elif mode == "list_contains":
    d = load(file)
    oid = rest[0]
    print(1 if any(item.get("id") == oid for item in d.get("items", [])) else 0)
else:
    print(f"unknown helper mode: {mode}", file=sys.stderr)
    sys.exit(1)
PYEOF

jget() { python3 "$HELPER" "$@"; }

# -------------------------------- presentation helpers

rule() { printf '%s\n' "$(printf '=%.0s' $(seq 1 78))"; }

banner() {
    echo
    printf '%s%s%s\n' "$CYAN" "$(rule)" "$NC"
    printf '%s  %s%s\n' "$CYAN" "$1" "$NC"
    printf '%s%s%s\n' "$CYAN" "$(rule)" "$NC"
}

narration_pause() {
    if $MANUAL; then
        printf '\nPress Enter to continue'
        read -r _
    elif [ "$PAUSE_SECONDS" -gt 0 ] 2>/dev/null; then
        sleep "$PAUSE_SECONDS"
    fi
}

# A banner for a named section that is NOT one of the six required stages
# (e.g. the API walkthrough) - same pacing, but doesn't bump STAGE_NUM.
section() {
    banner "$1"
    printf '%s%s%s\n' "$GRAY" "$2" "$NC"
    narration_pause
    echo
}

stage() {
    STAGE_NUM=$((STAGE_NUM + 1))
    banner "STAGE $STAGE_NUM : $1"
    printf '%s%s%s\n' "$GRAY" "$2" "$NC"
    narration_pause
    echo
}

step() { printf '\n%s>> %s%s\n' "$YELLOW" "$1" "$NC"; }
callhttp() { printf '   %s%s %s%s\n' "$CYAN" "$1" "$2" "$NC"; }
ok() { printf '   %sPASS: %s%s\n' "$GREEN" "$1" "$NC"; }
info() { printf '   %s\n' "$1"; }
stage_done() { printf '\n%s-- STAGE %s COMPLETE: %s --%s\n' "$GREEN" "$STAGE_NUM" "$1" "$NC"; }
fail() { printf 'FAILED: %s\n' "$1" >&2; exit 1; }
expect_status() { # actual expected description
    if [ "$1" = "$2" ]; then ok "$3 -> HTTP $1"; else fail "$3: expected HTTP $2, got $1"; fi
}

# -------------------------------- plumbing helpers

worker_url() {
    case "$1" in
        worker1) echo 'http://localhost:8082' ;;
        worker2) echo 'http://localhost:8083' ;;
        worker3) echo 'http://localhost:8084' ;;
        worker4) echo 'http://localhost:8085' ;;
        *) fail "unknown worker id: $1" ;;
    esac
}

get_code() { curl -s -o /dev/null -w '%{http_code}' -max-time 30 -X "${2:-GET}" "$1"; }

sha() { sha256sum "$1" | awk '{print $1}'; }

cluster_refresh() {
    CLUSTER_FILE="$EVIDENCE_DIR/_cluster.json"
    curl -sf -max-time 10 "$CONTROLLER_URL/internal/status" -o "$CLUSTER_FILE"
}

# Timestamp to pass to `docker compose logs -since` right before a download, so we
# only look at log lines produced by that specific request (with a small safety margin).
log_watermark() { date -u -d '-2 seconds' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u +%Y-%m-%dT%H:%M:%SZ; }

# Prints which worker actually served each chunk of a download, by reading the
# controller's "Served chunk <id> from worker <id>" log line (added to DownloadService
# for exactly this purpose) since a given watermark. Needs a metadata JSON file with
# a "chunks" array (index + chunkId) to turn chunk IDs into readable chunk indices.
show_served_from() { # metadataFile sinceWatermark label [workerThatMustNotServeAnyChunk]
    local meta="$1" since="$2" label="$3" forbidden="${4:-}"
    sleep 1
    local logfile="$EVIDENCE_DIR/_controller-logs-$(date +%s%N).txt"
    SERVED_LOGFILE="$logfile" # exposed for callers that need a targeted per-chunk check afterward
    ( cd "$PROJECT_DIR" && docker compose logs -no-color -since "$since" controller ) > "$logfile" 2>/dev/null || true
    step "$label"
    python3 -c "
import re, json
pattern = re.compile(r'Served chunk (\S+) from worker (\S+)')
last = {}
with open('$logfile') as f:
    for line in f:
        m = pattern.search(line)
        if m:
            last[m.group(1)] = m.group(2)
with open('$meta') as f:
    meta = json.load(f)
print('INDEX\tCHUNK_ID\tSERVED_FROM_WORKER')
for c in sorted(meta['chunks'], key=lambda c: c['index']):
    print(f\"{c['index']}\t{c['chunkId']}\t{last.get(c['chunkId'], '(not found in controller logs)')}\")
" | table
    if [ -n "$forbidden" ]; then
        local offenders
        offenders="$(grep -F "from worker $forbidden" "$logfile" || true)"
        if [ -z "$offenders" ]; then
            ok "Confirmed from the controller's own logs: no chunk was served from $forbidden"
        else
            fail "A chunk was served from $forbidden, which is supposed to be down!"
        fi
    fi
}

# Looks up which worker served one specific chunk ID in a log file captured by
# show_served_from (via $SERVED_LOGFILE). Prints the worker ID found, or "" if none.
served_from_for_chunk() { # logfile chunkId
    grep -F "Served chunk $2 from worker" "$1" 2>/dev/null | tail -1 | sed -E 's/.*from worker ([^[:space:]]+).*/\1/'
}

wait_until() {
    local desc="$1" timeout="$2"
    shift 2
    step "Waiting: $desc"
    local start=$SECONDS
    while (( SECONDS - start < timeout )); do
        if "$@"; then ok "$desc"; return 0; fi
        sleep 2
    done
    fail "timed out waiting for: $desc"
}

four_alive() { cluster_refresh 2>/dev/null || return 1; [ "$(jget alive_count "$CLUSTER_FILE")" = "4" ]; }
worker_is_dead() { cluster_refresh 2>/dev/null || return 1; [ "$(jget worker_status "$CLUSTER_FILE" "$1")" = "DEAD" ]; }
healthy_min() { cluster_refresh 2>/dev/null || return 1; [ "$(jget all_healthy_min "$CLUSTER_FILE" "$1" "$2")" = "1" ]; }
healthy_exact() { cluster_refresh 2>/dev/null || return 1; [ "$(jget all_healthy_exact "$CLUSTER_FILE" "$1" "$2")" = "1" ]; }
chunk_repaired_and_readable() {
    healthy_exact "$1" 2 || return 1
    [ "$(get_code "$(worker_url "$2")/internal/chunks/$3")" = "200" ]
}

# Prints a labeled snapshot of worker health + an object's chunk/replica layout,
# then an explicit verdict on whether replication factor 2 is currently maintained.
snapshot() { # label objectId
    local label="$1" oid="$2"
    cluster_refresh
    step "$label: worker status"
    { printf 'WORKER_ID\tSTATUS\tLAST_HEARTBEAT\tCHUNK_COUNT\n'; jget worker_rows "$CLUSTER_FILE"; } | table
    step "$label: chunk/replica layout for this object"
    { printf 'INDEX\tCHUNK_ID\tHEALTHY_COPIES\tREPLICAS\n'; jget chunk_status_rows "$CLUSTER_FILE" "$oid"; } | table
    local min_healthy
    min_healthy="$(jget min_healthy "$CLUSTER_FILE" "$oid")"
    if [ "$min_healthy" -ge 2 ]; then
        ok "$label: replication factor 2 IS maintained (minimum healthy copies per chunk = $min_healthy)"
    else
        info "$label: replication factor 2 is NOT currently maintained (minimum healthy copies per chunk = $min_healthy)"
    fi
}

do_upload() { # file contentType metadataJsonOrEmpty
    local file="$1" ctype="$2" meta="$3"
    UPLOAD_BODY="$EVIDENCE_DIR/upload-$(date +%s%N).json"
    if [ -n "$meta" ]; then
        local metafile="$EVIDENCE_DIR/meta-$(date +%s%N).json"
        printf '%s' "$meta" > "$metafile"
        UPLOAD_STATUS=$(curl -s -o "$UPLOAD_BODY" -w '%{http_code}' -max-time 120 \
            -F "file=@${file};type=${ctype}" \
            -F "metadata=<${metafile};type=application/json" \
            "$CONTROLLER_URL/api/v1/objects")
    else
        UPLOAD_STATUS=$(curl -s -o "$UPLOAD_BODY" -w '%{http_code}' -max-time 120 \
            -F "file=@${file};type=${ctype}" \
            "$CONTROLLER_URL/api/v1/objects")
    fi
}

do_download() { # objectId outFile
    DOWNLOAD_STATUS=$(curl -s -o "$2" -w '%{http_code}' -max-time 120 "$CONTROLLER_URL/api/v1/objects/$1")
}

do_metadata() { # objectId
    META_BODY="$EVIDENCE_DIR/metadata-$(date +%s%N).json"
    META_STATUS=$(curl -s -o "$META_BODY" -w '%{http_code}' -max-time 30 "$CONTROLLER_URL/api/v1/objects/$1/metadata")
}

do_patch() { # objectId jsonBody
    PATCH_BODY="$EVIDENCE_DIR/patch-$(date +%s%N).json"
    PATCH_STATUS=$(curl -s -o "$PATCH_BODY" -w '%{http_code}' -max-time 30 -X PATCH \
        -H 'Content-Type: application/json' -d "$2" "$CONTROLLER_URL/api/v1/objects/$1/metadata")
}

do_list() { # page size
    LIST_BODY="$EVIDENCE_DIR/list-$(date +%s%N).json"
    LIST_STATUS=$(curl -s -o "$LIST_BODY" -w '%{http_code}' -max-time 30 \
        "$CONTROLLER_URL/api/v1/objects?page=${1:-0}&size=${2:-50}")
}

do_delete() { # objectId
    DELETE_STATUS="$(get_code "$CONTROLLER_URL/api/v1/objects/$1" DELETE)"
}

table() { column -t -s "$(printf '\t')"; }

# ======================================================================= START

banner 'MINI DISTRIBUTED CLOUD OBJECT STORE - LIVE DEMONSTRATION'
echo "Controller: $CONTROLLER_URL"
echo "Workers:    worker1, worker2, worker3, worker4"
echo "Evidence saved to: $EVIDENCE_DIR"

step 'Checking Docker is available'
docker info >/dev/null 2>&1 || fail 'Docker does not appear to be running. Start Docker (Desktop/daemon) and try again.'
ok 'Docker is running'

if $SKIP_BUILD; then
    step 'Skipping build (-no-build): using whatever images already exist'
else
    # All four worker services share one image tag (see the x-worker anchor in
    # compose.yaml). Building them in parallel (e.g. `docker compose build` or
    # `up -build`) races on tagging that shared image and fails with
    # "image ... already exists". Building controller and ONE worker service,
    # strictly one after another, avoids the race; the other three workers then
    # reuse that same cached image during `up`.
    step 'Building images (controller, then the shared worker image) - sequentially, to avoid a known Docker Compose build race'
    ( cd "$PROJECT_DIR" && docker compose build controller ) || fail 'Building the controller image failed'
    ok 'Controller image built'
    ( cd "$PROJECT_DIR" && docker compose build worker1 ) || fail 'Building the worker image failed'
    ok 'Worker image built (shared by worker1-4)'
fi

step 'Starting the stack: 1 controller + 4 workers'
( cd "$PROJECT_DIR" && docker compose up -d ) || fail 'docker compose up failed'
( cd "$PROJECT_DIR" && docker compose ps )
ok 'Containers started'

wait_until 'All 4 workers registered and ALIVE' 120 four_alive
cluster_refresh
jget worker_rows "$CLUSTER_FILE" | table

# ================================================================ API WALKTHROUGH
section 'API WALKTHROUGH: every endpoint in the spec, exercised once' \
'Before the six required demonstrations, we call every client-facing and internal API this project implements and confirm each returns the status code the spec requires. All data here is throwaway test data, separate from the objects used in the stages that follow.'

banner 'Client REST API (/api/v1/objects...)'

API_PATH="$EVIDENCE_DIR/api-check-object.txt"
printf 'API walkthrough test object.\n' > "$API_PATH"
API_HASH="$(sha "$API_PATH")"

step 'POST /api/v1/objects - upload an object'
callhttp POST "$CONTROLLER_URL/api/v1/objects"
do_upload "$API_PATH" 'text/plain' '{"purpose":"api-walkthrough"}'
expect_status "$UPLOAD_STATUS" 201 'Upload object'
API_ID="$(jget field "$UPLOAD_BODY" id)"
info "Object ID: $API_ID"

step 'GET /api/v1/objects/{id} - download the object'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$API_ID"
API_DL_PATH="$EVIDENCE_DIR/api-check-download.txt"
do_download "$API_ID" "$API_DL_PATH"
expect_status "$DOWNLOAD_STATUS" 200 'Download object'
[ "$(sha "$API_DL_PATH")" = "$API_HASH" ] || fail 'downloaded bytes do not match what was uploaded'
ok 'Downloaded bytes match the upload'

step 'GET /api/v1/objects/{id}/metadata - read metadata without downloading data'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$API_ID/metadata"
do_metadata "$API_ID"
expect_status "$META_STATUS" 200 'Get metadata'
[ "$(jget custom_get "$META_BODY" purpose)" = "api-walkthrough" ] || fail 'custom metadata missing from upload'
ok 'Custom metadata from upload is present'

step 'PATCH /api/v1/objects/{id}/metadata - update custom metadata'
callhttp PATCH "$CONTROLLER_URL/api/v1/objects/$API_ID/metadata"
do_patch "$API_ID" '{"purpose":"api-walkthrough-updated"}'
expect_status "$PATCH_STATUS" 200 'Update metadata'
[ "$(jget custom_get "$PATCH_BODY" purpose)" = "api-walkthrough-updated" ] || fail 'PATCH did not update the metadata'
ok 'Metadata updated without re-uploading the file'

step 'GET /api/v1/objects - list stored objects'
callhttp GET "$CONTROLLER_URL/api/v1/objects"
do_list 0 50
expect_status "$LIST_STATUS" 200 'List objects'
[ "$(jget list_contains "$LIST_BODY" "$API_ID")" = "1" ] || fail 'uploaded object is missing from the listing'
ok 'Listing includes the uploaded object'

step 'DELETE /api/v1/objects/{id} - delete the object'
callhttp DELETE "$CONTROLLER_URL/api/v1/objects/$API_ID"
do_delete "$API_ID"
expect_status "$DELETE_STATUS" 204 'Delete object'

step 'Error handling: GET a just-deleted object'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$API_ID"
expect_status "$(get_code "$CONTROLLER_URL/api/v1/objects/$API_ID")" 404 'Download deleted object'

step 'Error handling: GET metadata for an object ID that never existed'
RANDOM_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
callhttp GET "$CONTROLLER_URL/api/v1/objects/$RANDOM_ID/metadata"
expect_status "$(get_code "$CONTROLLER_URL/api/v1/objects/$RANDOM_ID/metadata")" 404 'Metadata for unknown object'

step 'Error handling: repeat DELETE on an already-deleted object'
callhttp DELETE "$CONTROLLER_URL/api/v1/objects/$API_ID"
expect_status "$(get_code "$CONTROLLER_URL/api/v1/objects/$API_ID" DELETE)" 404 'Repeated delete'

step 'Error handling: upload with malformed custom metadata'
BAD_META="$EVIDENCE_DIR/api-check-bad-meta.json"
printf '{not-valid-json' > "$BAD_META"
callhttp POST "$CONTROLLER_URL/api/v1/objects"
BAD_CODE=$(curl -s -o /dev/null -w '%{http_code}' -max-time 30 \
    -F "file=@${API_PATH};type=text/plain" -F "metadata=<${BAD_META};type=application/json" \
    "$CONTROLLER_URL/api/v1/objects")
expect_status "$BAD_CODE" 400 'Upload with malformed metadata'

banner 'Internal Worker API (/internal/...)'
info 'Registration (POST /internal/workers/register) and heartbeat (POST /internal/workers/heartbeat) are already'
info 'being exercised continuously by the four real worker processes - see the live lastHeartbeat timestamps in'
info 'the worker status table printed above. We do not re-invoke those two here, since doing so would mean forging'
info "a worker's own registration token rather than demonstrating the real thing."

PROBE_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
PROBE_PATH="$EVIDENCE_DIR/api-check-probe.bin"
head -c 256 /dev/urandom > "$PROBE_PATH"
PROBE_HASH="$(sha "$PROBE_PATH")"
PROBE_URL="$(worker_url worker1)/internal/chunks/$PROBE_ID"

step "PUT /internal/chunks/{chunk_id} - store a chunk directly on worker1"
callhttp PUT "$PROBE_URL"
PUT_CODE=$(curl -s -o /dev/null -w '%{http_code}' -max-time 30 -X PUT \
    -H 'Content-Type: application/octet-stream' -H "X-Checksum: $PROBE_HASH" \
    -data-binary "@$PROBE_PATH" "$PROBE_URL")
expect_status "$PUT_CODE" 201 'Store chunk on worker1'

step "GET /internal/chunks/{chunk_id} - retrieve it back directly from worker1"
callhttp GET "$PROBE_URL"
PROBE_DL="$EVIDENCE_DIR/api-check-probe-download.bin"
GET_CODE=$(curl -s -o "$PROBE_DL" -w '%{http_code}' -max-time 30 "$PROBE_URL")
expect_status "$GET_CODE" 200 'Retrieve chunk from worker1'
[ "$(sha "$PROBE_DL")" = "$PROBE_HASH" ] || fail 'chunk bytes from worker1 do not match what was stored'
ok 'Retrieved bytes match what was stored'

step "GET /internal/workers/{worker_id}/chunks - worker1's chunk inventory"
callhttp GET "$(worker_url worker1)/internal/workers/worker1/chunks"
INVENTORY="$EVIDENCE_DIR/api-check-inventory.json"
INV_CODE=$(curl -s -o "$INVENTORY" -w '%{http_code}' -max-time 30 "$(worker_url worker1)/internal/workers/worker1/chunks")
expect_status "$INV_CODE" 200 "List worker1's inventory"
FOUND=$(python3 -c "
import json
d = json.load(open('$INVENTORY'))
print(1 if any(e.get('chunkId') == '$PROBE_ID' for e in d) else 0)
")
[ "$FOUND" = "1" ] || fail 'probe chunk is missing from worker1 inventory'
ok 'Inventory includes the probe chunk'

step "DELETE /internal/chunks/{chunk_id} - remove the probe chunk"
callhttp DELETE "$PROBE_URL"
DEL_CODE=$(curl -s -o /dev/null -w '%{http_code}' -max-time 30 -X DELETE "$PROBE_URL")
expect_status "$DEL_CODE" 204 'Delete chunk from worker1'

step 'Confirming the deleted probe chunk is really gone'
callhttp GET "$PROBE_URL"
expect_status "$(get_code "$PROBE_URL")" 404 'Retrieve deleted chunk'

printf '\n%sAPI WALKTHROUGH COMPLETE - every endpoint responded as the spec requires.%s\n' "$GREEN" "$NC"

# ======================================================================= STAGE 1
stage 'Object Storage' \
'Upload an arbitrary binary object and retrieve it back. We verify the downloaded bytes are byte-for-byte identical to what we uploaded (SHA-256 match).'

SMALL_PATH="$EVIDENCE_DIR/stage1-demo-file.txt"
printf 'Hello from the Mini Distributed Cloud Object Store demo.\nThis is a small plain-text object.\n' > "$SMALL_PATH"
ORIGINAL_HASH1="$(sha "$SMALL_PATH")"
step "Uploading a small text file ($(stat -c%s "$SMALL_PATH") bytes)"
callhttp POST "$CONTROLLER_URL/api/v1/objects"
do_upload "$SMALL_PATH" 'text/plain' ''
[ "$UPLOAD_STATUS" = "201" ] || fail "upload returned $UPLOAD_STATUS, expected 201"
ok 'Upload accepted -> 201 Created'
SMALL_ID="$(jget field "$UPLOAD_BODY" id)"
info "Object ID: $SMALL_ID"

do_metadata "$SMALL_ID"
SMALL_META_BODY="$META_BODY"

DOWNLOAD_PATH1="$EVIDENCE_DIR/stage1-downloaded.txt"
step 'Downloading it back through the controller'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$SMALL_ID"
SINCE1="$(log_watermark)"
do_download "$SMALL_ID" "$DOWNLOAD_PATH1"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS, expected 200"
DOWNLOADED_HASH1="$(sha "$DOWNLOAD_PATH1")"
[ "$DOWNLOADED_HASH1" = "$ORIGINAL_HASH1" ] || fail 'downloaded bytes differ from the original!'
ok "SHA-256 matches: $ORIGINAL_HASH1"
show_served_from "$SMALL_META_BODY" "$SINCE1" 'Which worker actually served this download'
stage_done 'Object Storage'

# ======================================================================= STAGE 2
stage 'Chunking & Distribution' \
'Upload an object larger than 1 MB. The controller must split it into 1 MB chunks (last chunk smaller), preserve their order, and spread them across the 4 worker nodes.'

BIG_PATH="$EVIDENCE_DIR/stage2-large-object.bin"
BIG_SIZE=$((3 * 1024 * 1024 + 777))
python3 -c "
import random
random.seed(12345)
with open('$BIG_PATH', 'wb') as f:
    f.write(bytes(random.randrange(256) for _ in range($BIG_SIZE)))
"
ORIGINAL_HASH2="$(sha "$BIG_PATH")"
step "Uploading a $BIG_SIZE-byte object (3 MB + 777 bytes -> should split into 4 chunks: three 1,048,576-byte chunks and one 777-byte chunk)"
callhttp POST "$CONTROLLER_URL/api/v1/objects"
do_upload "$BIG_PATH" 'application/octet-stream' ''
[ "$UPLOAD_STATUS" = "201" ] || fail "upload returned $UPLOAD_STATUS, expected 201"
BIG_ID="$(jget field "$UPLOAD_BODY" id)"
BIG_CHUNK_COUNT="$(jget field "$UPLOAD_BODY" chunkCount)"
info "Object ID: $BIG_ID"
[ "$BIG_CHUNK_COUNT" = "4" ] || fail "expected 4 chunks, got $BIG_CHUNK_COUNT"
ok "Object split into $BIG_CHUNK_COUNT chunks, as expected"

step 'Fetching metadata to see the chunk layout'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$BIG_ID/metadata"
do_metadata "$BIG_ID"
[ "$META_STATUS" = "200" ] || fail "metadata GET returned $META_STATUS"
BIG_META_BODY="$META_BODY"
ok 'Metadata retrieved'

step 'Chunk layout (index, size, which workers hold it):'
{ printf 'INDEX\tCHUNK_ID\tBYTES\tWORKERS\n'; jget chunk_rows "$BIG_META_BODY"; } | table
USED_WORKERS="$(jget chunk_rows "$BIG_META_BODY" | cut -f4 | tr ',' '\n' | sort -u | tr '\n' ' ')"
ok "Chunks are distributed across workers: $USED_WORKERS"

DOWNLOAD_PATH2="$EVIDENCE_DIR/stage2-downloaded.bin"
step 'Downloading the full object back (reassembled from all 4 chunks)'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$BIG_ID"
SINCE2="$(log_watermark)"
do_download "$BIG_ID" "$DOWNLOAD_PATH2"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS"
[ "$(sha "$DOWNLOAD_PATH2")" = "$ORIGINAL_HASH2" ] || fail 'reassembled object does not match the original!'
ok 'Reassembled object matches the original byte-for-byte'
show_served_from "$BIG_META_BODY" "$SINCE2" 'Which worker each chunk of this download actually came from'
stage_done 'Chunking & Distribution'

# ======================================================================= STAGE 3
stage 'Replication' \
"Every chunk must exist in two copies, on two different workers. We show the replica locations from the controller's view, then confirm the bytes physically exist on both workers by reading each chunk directly from its worker."

step 'Verifying replication factor 2 for every chunk of the large object'
wait_until 'Controller reports 2 healthy copies for every chunk' 60 healthy_exact "$BIG_ID" 2

step "Reading each chunk directly from both of its workers (bypassing the controller) to prove the bytes are really there"
ALL_VERIFIED=1
{
printf 'INDEX\tCHUNK_ID\tREPLICA_1\tREPLICA_2\tBOTH_VERIFIED\n'
for i in 0 1 2 3; do
    CHUNK_ID="$(python3 -c "
import json
d=json.load(open('$BIG_META_BODY'))
c=[c for c in d['chunks'] if c['index']==$i][0]
print(c['chunkId'])
")"
    WORKERS_CSV="$(python3 -c "
import json
d=json.load(open('$BIG_META_BODY'))
c=[c for c in d['chunks'] if c['index']==$i][0]
print(','.join(r['workerId'] for r in c['replicas']))
")"
    IFS=',' read -r W1 W2 <<< "$WORKERS_CSV"
    C1=$(get_code "$(worker_url "$W1")/internal/chunks/$CHUNK_ID")
    C2=$(get_code "$(worker_url "$W2")/internal/chunks/$CHUNK_ID")
    BOTH="yes"
    [ "$C1" = "200" ] && [ "$C2" = "200" ] || { BOTH="no"; ALL_VERIFIED=0; }
    printf '%s\t%s\t%s\t%s\t%s\n' "$i" "$CHUNK_ID" "$W1" "$W2" "$BOTH"
done
} | table
[ "$ALL_VERIFIED" = "1" ] || fail 'not every chunk verified on both physical workers'
ok 'All chunks physically confirmed on two distinct workers'
stage_done 'Replication'

# ======================================================================= STAGE 4
stage 'Metadata' \
'Upload an object with custom user metadata, read system + custom metadata back, then update (PATCH) the custom metadata WITHOUT re-uploading the file, and show the object bytes/checksum are untouched.'

META_PATH="$EVIDENCE_DIR/stage4-object.bin"
head -c 200 /dev/urandom > "$META_PATH"
step 'Uploading an object with custom metadata: {"course":"cloud","phase":"initial"}'
callhttp POST "$CONTROLLER_URL/api/v1/objects"
do_upload "$META_PATH" 'application/octet-stream' '{"course":"cloud","phase":"initial"}'
[ "$UPLOAD_STATUS" = "201" ] || fail "upload returned $UPLOAD_STATUS"
META_ID="$(jget field "$UPLOAD_BODY" id)"
info "Object ID: $META_ID"

step 'Fetching metadata without downloading the object data'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$META_ID/metadata"
do_metadata "$META_ID"
[ "$META_STATUS" = "200" ] || fail "metadata GET returned $META_STATUS"
BEFORE_BODY="$META_BODY"
NAME_B="$(jget field "$BEFORE_BODY" name)"
SIZE_B="$(jget field "$BEFORE_BODY" size)"
CTYPE_B="$(jget field "$BEFORE_BODY" contentType)"
CHECKSUM_B="$(jget field "$BEFORE_BODY" checksum)"
info "Name: $NAME_B  Size: $SIZE_B  ContentType: $CTYPE_B"
info "Checksum: $CHECKSUM_B"
info "Custom metadata: $(jget custom_json "$BEFORE_BODY")"
COURSE_B="$(jget custom_get "$BEFORE_BODY" course)"
PHASE_B="$(jget custom_get "$BEFORE_BODY" phase)"
[ "$COURSE_B" = "cloud" ] && [ "$PHASE_B" = "initial" ] || fail 'custom metadata missing after upload'
ok 'Custom metadata preserved from upload'

step 'PATCHing metadata: phase -> "updated", adding reviewer -> "demo-audience" (no file re-upload)'
callhttp PATCH "$CONTROLLER_URL/api/v1/objects/$META_ID/metadata"
do_patch "$META_ID" '{"phase":"updated","reviewer":"demo-audience"}'
[ "$PATCH_STATUS" = "200" ] || fail "PATCH returned $PATCH_STATUS"
ok 'PATCH returned 200'
info "Custom metadata now: $(jget custom_json "$PATCH_BODY")"
PHASE_A="$(jget custom_get "$PATCH_BODY" phase)"
REVIEWER_A="$(jget custom_get "$PATCH_BODY" reviewer)"
COURSE_A="$(jget custom_get "$PATCH_BODY" course)"
CHECKSUM_A="$(jget field "$PATCH_BODY" checksum)"
[ "$PHASE_A" = "updated" ] && [ "$REVIEWER_A" = "demo-audience" ] && [ "$COURSE_A" = "cloud" ] || fail 'PATCH did not merge metadata correctly'
[ "$CHECKSUM_A" = "$CHECKSUM_B" ] || fail 'checksum changed after a metadata-only PATCH!'
ok 'Object checksum unchanged - confirms no re-upload was needed'
stage_done 'Metadata'

# ======================================================================= STAGE 5
stage 'Worker Failure' \
'We stop one of the Docker containers holding a replica of our large object (docker stop), and explicitly show worker status and replication factor BEFORE, DURING (both immediately and once the controller has reacted), and AFTER restarting it (docker start).'

cluster_refresh
VICTIM="$(jget pick_usable_worker "$CLUSTER_FILE" "$BIG_ID")"

snapshot 'BEFORE failure' "$BIG_ID"

step "docker stop $VICTIM   <- simulating a worker crash"
( cd "$PROJECT_DIR" && docker compose stop "$VICTIM" >/dev/null )
ok "$VICTIM stopped"

snapshot 'DURING failure (immediately after stop, before heartbeat timeout)' "$BIG_ID"

step 'DURING failure: downloading the object anyway (served from a surviving replica)'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$BIG_ID"
SINCE5A="$(log_watermark)"
DURING_PATH="$EVIDENCE_DIR/stage5-during-failure.bin"
do_download "$BIG_ID" "$DURING_PATH"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS while worker is down"
[ "$(sha "$DURING_PATH")" = "$ORIGINAL_HASH2" ] || fail 'object corrupted/missing while a worker is down!'
ok 'Object still downloads correctly with one worker down'
show_served_from "$BIG_META_BODY" "$SINCE5A" "Which worker served each chunk while $VICTIM is down" "$VICTIM"

wait_until "Controller detects $VICTIM as DEAD (missed heartbeats)" 60 worker_is_dead "$VICTIM"
wait_until 'Replication factor 2 restored onto the remaining live workers' 120 healthy_min "$BIG_ID" 2

snapshot "DURING failure (after detection + repair, $VICTIM still stopped)" "$BIG_ID"

step "docker start $VICTIM   <- bringing the worker back"
( cd "$PROJECT_DIR" && docker compose start "$VICTIM" >/dev/null )
wait_until "$VICTIM re-registered and is ALIVE again" 60 four_alive
wait_until 'Excess copies trimmed back down to exactly 2 per chunk after recovery' 60 healthy_exact "$BIG_ID" 2

snapshot 'AFTER recovery' "$BIG_ID"

AFTER_PATH="$EVIDENCE_DIR/stage5-after-recovery.bin"
step 'AFTER recovery: downloading once more to confirm everything still works'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$BIG_ID"
SINCE5B="$(log_watermark)"
do_download "$BIG_ID" "$AFTER_PATH"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS after recovery"
[ "$(sha "$AFTER_PATH")" = "$ORIGINAL_HASH2" ] || fail 'object mismatch after worker recovery!'
ok 'Object downloads correctly after the worker rejoined'
show_served_from "$BIG_META_BODY" "$SINCE5B" 'Which worker served each chunk after recovery (now that all 4 are back)'
stage_done 'Worker Failure'

# ======================================================================= STAGE 6
stage 'Data Integrity' \
'We directly corrupt one physical replica of a chunk on disk (bypassing the API entirely). The worker must detect the corruption on its own checksum check. The controller must still serve the correct object from the healthy replica, then repair the corrupted copy.'

cluster_refresh
BAD_CHUNK_ID="$(jget pick_chunk_id "$CLUSTER_FILE" "$BIG_ID")"
BAD_WORKER="$(jget pick_usable_worker "$CLUSTER_FILE" "$BIG_ID")"
step "Corrupting chunk $BAD_CHUNK_ID on $BAD_WORKER directly on disk (docker exec, bypassing all APIs)"
( cd "$PROJECT_DIR" && docker compose exec -T "$BAD_WORKER" sh -c 'printf CORRUPTED-BY-DEMO > "$1"' - "/data/chunks/$BAD_CHUNK_ID.chunk" )
ok "Chunk file on $BAD_WORKER overwritten with garbage bytes"

step "Asking $BAD_WORKER directly for that chunk (should fail its own checksum check)"
callhttp GET "$(worker_url "$BAD_WORKER")/internal/chunks/$BAD_CHUNK_ID"
CORRUPT_CODE="$(get_code "$(worker_url "$BAD_WORKER")/internal/chunks/$BAD_CHUNK_ID")"
info "Worker responded with HTTP $CORRUPT_CODE"
if [ "$CORRUPT_CODE" = "409" ]; then
    ok 'Worker detected the corruption itself (409 Conflict)'
elif [ "$CORRUPT_CODE" = "200" ]; then
    info '(200 here would mean background repair already replaced it before this check ran)'
else
    fail "unexpected status for corrupted chunk: $CORRUPT_CODE"
fi

step 'Downloading the full object through the controller anyway (must be served from the healthy copy)'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$BIG_ID"
SINCE6="$(log_watermark)"
CORRUPT_DOWNLOAD_PATH="$EVIDENCE_DIR/stage6-with-corruption.bin"
do_download "$BIG_ID" "$CORRUPT_DOWNLOAD_PATH"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS with a corrupted replica present"
[ "$(sha "$CORRUPT_DOWNLOAD_PATH")" = "$ORIGINAL_HASH2" ] || fail 'download returned corrupted/incorrect data!'
ok 'Object still downloads correctly, served from the valid replica'
show_served_from "$BIG_META_BODY" "$SINCE6" 'Which worker served each chunk, with one replica corrupted'
BAD_CHUNK_SERVED_BY="$(served_from_for_chunk "$SERVED_LOGFILE" "$BAD_CHUNK_ID")"
if [ "$BAD_CHUNK_SERVED_BY" = "$BAD_WORKER" ]; then
    fail "the corrupted chunk was served from $BAD_WORKER - it should have been served from the other, healthy replica"
elif [ -n "$BAD_CHUNK_SERVED_BY" ]; then
    ok "Confirmed: the corrupted chunk (index for $BAD_CHUNK_ID) was served from $BAD_CHUNK_SERVED_BY, not the corrupted $BAD_WORKER"
fi

wait_until 'Controller detects the corrupt replica and repairs it back to 2 healthy copies' 120 \
    chunk_repaired_and_readable "$BIG_ID" "$BAD_WORKER" "$BAD_CHUNK_ID"

ok "$BAD_WORKER now serves a valid copy of chunk $BAD_CHUNK_ID again (HTTP 200)"
stage_done 'Data Integrity'

# ======================================================================= SUMMARY
banner 'ALL 6 REQUIRED DEMONSTRATIONS COMPLETE'
printf '%s  1. Object Storage          - PASS%s\n' "$GREEN" "$NC"
printf '%s  2. Chunking & Distribution - PASS%s\n' "$GREEN" "$NC"
printf '%s  3. Replication             - PASS%s\n' "$GREEN" "$NC"
printf '%s  4. Metadata                - PASS%s\n' "$GREEN" "$NC"
printf '%s  5. Worker Failure          - PASS%s\n' "$GREEN" "$NC"
printf '%s  6. Data Integrity          - PASS%s\n' "$GREEN" "$NC"
echo
echo "Evidence (uploaded/downloaded files) saved to: $EVIDENCE_DIR"

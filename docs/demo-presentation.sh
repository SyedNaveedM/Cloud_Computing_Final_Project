#!/usr/bin/env bash
#
# Live presentation demo for Mini Distributed Cloud Object Store.
#
# Walks through, in order, the six demonstrations required by PDF section 10:
#   1. Object Storage          - upload/download, verify the bytes match
#   2. Chunking & Distribution - upload something >1MB, show chunk layout + worker spread
#   3. Replication             - show each chunk has two copies, on two different workers
#   4. Metadata                - upload custom metadata, read it back, patch it without re-upload
#   5. Worker Failure          - docker stop a holder, show it still serves, show it heals, restart it
#   6. Data Integrity          - corrupt a replica on disk, show detection + correct-copy serving + repair
#
# This is meant to be run live in front of an audience: every HTTP call and its
# result is printed, with a plain-English explanation before each stage. Pass
# --interactive to pause before each stage so you can narrate.
#
# Usage (run from the project root, after `docker compose up --build -d`):
#   ./docs/demo-presentation.sh                 # runs straight through
#   ./docs/demo-presentation.sh --interactive   # pauses before each stage
#
# Requires: a running docker compose stack for this project, curl, python3, docker.

set -uo pipefail

CONTROLLER_URL='http://localhost:8081'
INTERACTIVE=false
for arg in "$@"; do
    case "$arg" in
        --interactive|-i) INTERACTIVE=true ;;
        --controller-url=*) CONTROLLER_URL="${arg#*=}" ;;
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

# ---------------------------------------------------------------- JSON helper (python, no jq needed)

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
        workers = ", ".join(f'{r["workerId"]}({r["state"]})' for r in c["replicas"])
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
else:
    print(f"unknown helper mode: {mode}", file=sys.stderr)
    sys.exit(1)
PYEOF

jget() { python3 "$HELPER" "$@"; }

# ---------------------------------------------------------------- presentation helpers

rule() { printf '%s\n' "$(printf '=%.0s' $(seq 1 78))"; }

banner() {
    echo
    printf '%s%s%s\n' "$CYAN" "$(rule)" "$NC"
    printf '%s  %s%s\n' "$CYAN" "$1" "$NC"
    printf '%s%s%s\n' "$CYAN" "$(rule)" "$NC"
}

stage() {
    STAGE_NUM=$((STAGE_NUM + 1))
    banner "STAGE $STAGE_NUM : $1"
    printf '%s%s%s\n' "$GRAY" "$2" "$NC"
    if $INTERACTIVE; then
        printf '\nPress Enter to run this stage'
        read -r _
    fi
    echo
}

step() { printf '\n%s>> %s%s\n' "$YELLOW" "$1" "$NC"; }
callhttp() { printf '   %s%s %s%s\n' "$CYAN" "$1" "$2" "$NC"; }
ok() { printf '   %sPASS: %s%s\n' "$GREEN" "$1" "$NC"; }
info() { printf '   %s\n' "$1"; }
stage_done() { printf '\n%s--- STAGE %s COMPLETE: %s ---%s\n' "$GREEN" "$STAGE_NUM" "$1" "$NC"; }
fail() { printf 'FAILED: %s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- plumbing helpers

worker_url() {
    case "$1" in
        worker1) echo 'http://localhost:8082' ;;
        worker2) echo 'http://localhost:8083' ;;
        worker3) echo 'http://localhost:8084' ;;
        worker4) echo 'http://localhost:8085' ;;
        *) fail "unknown worker id: $1" ;;
    esac
}

get_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 30 -X "${2:-GET}" "$1"; }

sha() { sha256sum "$1" | awk '{print $1}'; }

cluster_refresh() {
    CLUSTER_FILE="$EVIDENCE_DIR/_cluster.json"
    curl -sf --max-time 10 "$CONTROLLER_URL/internal/status" -o "$CLUSTER_FILE"
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

do_upload() { # file contentType metadataJsonOrEmpty
    local file="$1" ctype="$2" meta="$3"
    UPLOAD_BODY="$EVIDENCE_DIR/upload-$(date +%s%N).json"
    if [ -n "$meta" ]; then
        local metafile="$EVIDENCE_DIR/meta-$(date +%s%N).json"
        printf '%s' "$meta" > "$metafile"
        UPLOAD_STATUS=$(curl -s -o "$UPLOAD_BODY" -w '%{http_code}' --max-time 120 \
            -F "file=@${file};type=${ctype}" \
            -F "metadata=<${metafile};type=application/json" \
            "$CONTROLLER_URL/api/v1/objects")
    else
        UPLOAD_STATUS=$(curl -s -o "$UPLOAD_BODY" -w '%{http_code}' --max-time 120 \
            -F "file=@${file};type=${ctype}" \
            "$CONTROLLER_URL/api/v1/objects")
    fi
}

do_download() { # objectId outFile
    DOWNLOAD_STATUS=$(curl -s -o "$2" -w '%{http_code}' --max-time 120 "$CONTROLLER_URL/api/v1/objects/$1")
}

do_metadata() { # objectId
    META_BODY="$EVIDENCE_DIR/metadata-$(date +%s%N).json"
    META_STATUS=$(curl -s -o "$META_BODY" -w '%{http_code}' --max-time 30 "$CONTROLLER_URL/api/v1/objects/$1/metadata")
}

do_patch() { # objectId jsonBody
    PATCH_BODY="$EVIDENCE_DIR/patch-$(date +%s%N).json"
    PATCH_STATUS=$(curl -s -o "$PATCH_BODY" -w '%{http_code}' --max-time 30 -X PATCH \
        -H 'Content-Type: application/json' -d "$2" "$CONTROLLER_URL/api/v1/objects/$1/metadata")
}

table() { column -t -s "$(printf '\t')"; }

# ======================================================================= START

banner 'MINI DISTRIBUTED CLOUD OBJECT STORE - LIVE DEMONSTRATION'
echo "Controller: $CONTROLLER_URL"
echo "Workers:    worker1, worker2, worker3, worker4"
echo "Evidence saved to: $EVIDENCE_DIR"

step 'Checking the stack is up (1 controller + 4 workers)'
( cd "$PROJECT_DIR" && docker compose ps )
wait_until 'All 4 workers registered and ALIVE' 120 four_alive
cluster_refresh
jget worker_rows "$CLUSTER_FILE" | table

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

DOWNLOAD_PATH1="$EVIDENCE_DIR/stage1-downloaded.txt"
step 'Downloading it back through the controller'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$SMALL_ID"
do_download "$SMALL_ID" "$DOWNLOAD_PATH1"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS, expected 200"
DOWNLOADED_HASH1="$(sha "$DOWNLOAD_PATH1")"
[ "$DOWNLOADED_HASH1" = "$ORIGINAL_HASH1" ] || fail 'downloaded bytes differ from the original!'
ok "SHA-256 matches: $ORIGINAL_HASH1"
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
do_download "$BIG_ID" "$DOWNLOAD_PATH2"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS"
[ "$(sha "$DOWNLOAD_PATH2")" = "$ORIGINAL_HASH2" ] || fail 'reassembled object does not match the original!'
ok 'Reassembled object matches the original byte-for-byte'
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
'We stop one of the Docker containers holding a replica of our large object (docker stop), show the object is STILL retrievable from the surviving copy, show the controller detects the DEAD worker and restores replication factor 2 using the remaining workers, then restart the stopped worker and show the system reconciles back to a clean state.'

cluster_refresh
step 'BEFORE: chunk/replica layout for the large object'
{ printf 'INDEX\tCHUNK_ID\tHEALTHY\tREPLICAS\n'; jget chunk_status_rows "$CLUSTER_FILE" "$BIG_ID"; } | table
VICTIM="$(jget pick_usable_worker "$CLUSTER_FILE" "$BIG_ID")"

step "docker stop $VICTIM   <-- simulating a worker crash"
( cd "$PROJECT_DIR" && docker compose stop "$VICTIM" >/dev/null )
ok "$VICTIM stopped"

step 'DURING failure: downloading the object anyway (served from the surviving replica)'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$BIG_ID"
DURING_PATH="$EVIDENCE_DIR/stage5-during-failure.bin"
do_download "$BIG_ID" "$DURING_PATH"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS while worker is down"
[ "$(sha "$DURING_PATH")" = "$ORIGINAL_HASH2" ] || fail 'object corrupted/missing while a worker is down!'
ok 'Object still downloads correctly with one worker down'

wait_until "Controller detects $VICTIM as DEAD (missed heartbeats)" 60 worker_is_dead "$VICTIM"
wait_until 'Replication factor 2 restored onto the remaining live workers' 120 healthy_min "$BIG_ID" 2

cluster_refresh
{ printf 'INDEX\tCHUNK_ID\tHEALTHY\tREPLICAS\n'; jget chunk_status_rows "$CLUSTER_FILE" "$BIG_ID"; } | table
ok 'Each chunk has 2 healthy copies again, none of them on the dead worker'

step "docker start $VICTIM   <-- bringing the worker back"
( cd "$PROJECT_DIR" && docker compose start "$VICTIM" >/dev/null )
wait_until "$VICTIM re-registered and is ALIVE again" 60 four_alive
wait_until 'Excess copies trimmed back down to exactly 2 per chunk after recovery' 60 healthy_exact "$BIG_ID" 2

AFTER_PATH="$EVIDENCE_DIR/stage5-after-recovery.bin"
step 'AFTER recovery: downloading once more to confirm everything still works'
callhttp GET "$CONTROLLER_URL/api/v1/objects/$BIG_ID"
do_download "$BIG_ID" "$AFTER_PATH"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS after recovery"
[ "$(sha "$AFTER_PATH")" = "$ORIGINAL_HASH2" ] || fail 'object mismatch after worker recovery!'
ok 'Object downloads correctly after the worker rejoined'
stage_done 'Worker Failure'

# ======================================================================= STAGE 6
stage 'Data Integrity' \
'We directly corrupt one physical replica of a chunk on disk (bypassing the API entirely). The worker must detect the corruption on its own checksum check. The controller must still serve the correct object from the healthy replica, then repair the corrupted copy.'

cluster_refresh
BAD_CHUNK_ID="$(jget pick_chunk_id "$CLUSTER_FILE" "$BIG_ID")"
BAD_WORKER="$(jget pick_usable_worker "$CLUSTER_FILE" "$BIG_ID")"
step "Corrupting chunk $BAD_CHUNK_ID on $BAD_WORKER directly on disk (docker exec, bypassing all APIs)"
( cd "$PROJECT_DIR" && docker compose exec -T "$BAD_WORKER" sh -c 'printf CORRUPTED-BY-DEMO > "$1"' -- "/data/chunks/$BAD_CHUNK_ID.chunk" )
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
CORRUPT_DOWNLOAD_PATH="$EVIDENCE_DIR/stage6-with-corruption.bin"
do_download "$BIG_ID" "$CORRUPT_DOWNLOAD_PATH"
[ "$DOWNLOAD_STATUS" = "200" ] || fail "download returned $DOWNLOAD_STATUS with a corrupted replica present"
[ "$(sha "$CORRUPT_DOWNLOAD_PATH")" = "$ORIGINAL_HASH2" ] || fail 'download returned corrupted/incorrect data!'
ok 'Object still downloads correctly, served from the valid replica'

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

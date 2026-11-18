# Project requirements demonstration

Use `docs/demo-requirements.ps1` for a guided, repeatable demonstration against
*Mini Distributed Cloud Object Store*, sections 1-10 of the supplied three-page PDF.
The script exercises the current application; it does not replace any controller or worker code.
The earlier `docs/demo-B.ps1` remains available as the shorter recovery demonstration.

## Start here

Open PowerShell in the project directory:

```powershell
Set-Location 'C:\Users\raipr\OneDrive - iiit-b\Documents\Cloud_Computing_Final_Project'
```

For a fresh build, use the Java 21 runtime already used for this project:

```powershell
$env:JAVA_HOME = 'C:\Program Files\JetBrains\PyCharm 2024.3.1.1\jbr'
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`. The last application test run had 132 passing tests:
3 common, 116 controller and 13 worker. Those tests supplement the real-container demo below.

Start Docker Desktop in Linux-container mode. Build and start the project:

```powershell
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

Expected: `controller`, `worker1`, `worker2`, `worker3`, and `worker4` are running.
The controller is available at `http://localhost:8081`; workers use host ports 8082-8085.
The script uses the project's fixed container names and ports.

For your first run or presentation, pause before each stage:

```powershell
.\docs\demo-requirements.ps1 -Interactive
```

Press Enter to run the displayed stage. Read its results before starting the next stage.
For an uninterrupted rehearsal:

```powershell
.\docs\demo-requirements.ps1
```

Allow several minutes for real heartbeat timeouts, repairs, registrations and deletion retries.
Timeouts are bounded; the script stops at a failed assertion instead of reporting an overall pass.
Run one demo at a time, with other uploads idle, to make the observed placement and recovery easy to explain.

The demo creates its own objects and a unique worker probe. It deliberately damages only one
replica of its own object and deletes only its own test data. It temporarily stops/restarts project
containers and briefly pauses the controller during the corruption observation. Container volumes
are retained. On completion, the test objects are deleted and local evidence files remain.

## Requirement coverage

| PDF requirement | Demonstration stage | Evidence / expected result |
| --- | --- | --- |
| Section 2: 1 controller, 4 workers, 1+ clients, Docker | 1 | Five matching Compose containers; this PowerShell process is the client |
| Sections 2 and 9: persistent storage | 1, 2, 9 | Separate `/data` volumes; identical worker bytes after stop/start; identical object after controller restart |
| Sections 1 and 3: arbitrary binary objects | 3, 6 | Binary and text round trips; matching SHA-256 and Content-Type; optional real file |
| Section 3: unique object ID and required metadata | 3, 6 | Metadata field checks; distinct IDs for two uploads |
| Section 4: chunk size, final chunk, unique IDs, ordering | 4 | Four ordered chunk IDs and exact source-range checksums |
| Section 4: two copies on different workers | 4, 7, 8 | Location tables, inventories, and GET/hash verification of each physical copy |
| Section 4: distribution across workers | 4 | Actual worker-location table; placement depends on available workers and current load |
| Sections 5.1-5.3: POST upload, GET object, GET metadata | 3 | 201, 200, 200; multipart file and JSON custom metadata |
| Section 5.4: PATCH metadata without re-upload | 5 | 200; custom keys change while checksum, size, chunk IDs and download remain unchanged |
| Section 5.5: successful DELETE and missing object | 6 | Normal deletion 204, files absent, subsequent GET/DELETE 404 |
| Section 5.6: list objects | 5, 6 | 200; object ID/name/size/content type present; deleted object absent |
| Section 5.7: meaningful JSON errors | 2, 6, 10 | 400 invalid input, 404 missing data, 409 conflict, 503 pending physical cleanup |
| Section 6: register and heartbeat | 1, 2, 7, 9 | Real workers register/re-register; all heartbeat timestamps advance |
| Section 6: chunk PUT/GET/DELETE and inventory | 2, 4 | Direct isolated probe, idempotent retries, inventories and verified bytes |
| Section 7: stop/start a Docker worker | 7 | Before/during/after snapshots, DEAD detection, matching download, restored RF2 |
| Section 8: intentional corruption | 8 | Deliberate file damage, worker 409, correct object download, verified repaired copies |
| Section 9: build/start/interaction instructions | This guide | Maven, Compose and demo commands |
| Section 10: six required demonstrations | 3-5, 7-8 | Object storage, chunking/distribution, replication, metadata, worker failure, data integrity |
| Additional: durable pending deletion | 10 | Repeated 503; controller restart retains DELETING; automatic physical cleanup after return |

The PDF lists 500 as the classification for unexpected internal errors. A successful demo should
not deliberately manufacture an application bug to produce one. An unexpected 500 fails a normal
success check and its response/logs are retained. This script does not claim to fault-inject every
internal exception or every possible failure interleaving.

## Stage 1: deployment and liveness

**What happens:** inspect the five containers, verify their Compose project label, verify separate
writable `/data` volumes, wait for four ALIVE workers, then wait for all heartbeat timestamps to advance.

**Expected:** a table of container/volume/image names and PASS messages for all four workers.
`deployment-volumes.json` and `initial-cluster.json` are saved.

**Explain:** "The controller owns metadata and coordinates operations. Four workers store chunk
bytes on separate persistent volumes. This PowerShell session acts as the client. Registration
introduces and reconciles a worker; heartbeats continue reporting its availability."

Registration and heartbeat are exercised by the workers' real lifecycle code. The demo does not
manually impersonate their current registration tokens. Worker logs are saved at the end.

Useful inspection commands in a second terminal:

```powershell
docker compose ps
Invoke-RestMethod 'http://localhost:8081/internal/status' | ConvertTo-Json -Depth 12
```

## Stage 2: internal worker APIs and persistence

**What happens:** create a unique 256-byte chunk directly on worker1. PUT returns 201, retrying
the identical PUT returns 204, and GET returns identical bytes. Stop/start worker1, wait for its
HTTP service and fresh registration/heartbeat, then retrieve the same bytes again. Inventory
must include the probe. A different payload under the same chunk ID is rejected with 409.
DELETE returns 204, repeating it returns 204, GET then returns 404, and a delayed PUT to the
retired ID returns 409.

**Explain:** "Acknowledged worker data survives process restart because it is stored in the
volume. Retrying a request must be safe when the original response was lost. A deletion marker
prevents a delayed write from recreating a deleted chunk."

This probe deliberately uses an internal API to test the worker in isolation. Normal client
uploads and downloads in the following stages go through the controller. The probe is removed
before the main upload; only its small deletion marker remains on worker1.

## Stage 3: upload and retrieve the object

**What happens:** create `original.bin` containing 3,145,745 bytes of deterministic binary data.
Upload it using multipart `file` and JSON `metadata`. Save the returned ID. Download through the
controller and compare SHA-256, byte length and Content-Type. Retrieve system and custom metadata.

**Expected:** POST 201, GET object 200 and GET metadata 200. Name, size, content type, timestamps,
checksum, chunk count, chunk details and custom key/value metadata must exist and match.

**Explain:** "The client supplies a file and custom metadata. The controller assigns an object ID.
The client later downloads by that ID without discovering or contacting chunk locations. Equal
SHA-256 values provide the demonstration's integrity check for the reconstructed object."

The evidence file `test-object.json` contains the ID, original file path and checksum for this run.

## Stage 4: chunking and replication

The inherited project constant is **1,048,576 bytes (1 MiB)**. The PDF calls the chunk size
"1 MB"; disclose this interpretation in your presentation. If the course expects decimal
1,000,000-byte chunks, that is a specification decision to resolve, rather than treating the
two units as identical.

For this input, the expected layout is:

| Index | Original byte offsets, inclusive | Payload size | Required healthy copies |
| --- | --- | --- | --- |
| 0 | 0-1,048,575 | 1,048,576 | 2, on distinct workers |
| 1 | 1,048,576-2,097,151 | 1,048,576 | 2, on distinct workers |
| 2 | 2,097,152-3,145,727 | 1,048,576 | 2, on distinct workers |
| 3 | 3,145,728-3,145,744 | 17 | 2, on distinct workers |

**What happens:** check ordered indices, distinct IDs, each size and each checksum against the
corresponding original byte range. Inspect all four worker inventories. Retrieve both copies
of every chunk directly and verify each checksum. Print and save the actual location table.

**Explain:** "Four logical chunks produce eight healthy payload copies. Chunk indices preserve
ordering; unique chunk IDs identify data. Each chunk's two copies are on different workers."

Exact worker pairs can vary because placement uses load and random tie-breaking. It accounts
for committed replica records and reservations for writes in progress, so a rapid upload does
not have to wait for heartbeats to influence its next placement. Heartbeat counts also protect
against ignoring physical data that is not yet represented in replica records.

The location columns `replica1Worker` and `replica2Worker` identify the two actual holders;
they are not names of fixed destination workers. The table shows what this run actually stored.
With four initially empty workers, no competing work, and four chunks, expect two copies per
chunk and two stored chunks per worker. Existing data changes the expected distribution:
if total counts start at 4, 4, 0, 0, placing the next eight copies on workers 3 and 4 can correctly
bring total counts to 4, 4, 4, 4. Do not require every individual object to use every worker.
This policy balances chunk counts, not byte usage or request rate, and does not relocate
already healthy replicas. `.chunk` files contain an internal header; size checks concern payload bytes.

## Stage 5: metadata and listing

**What happens:** PATCH `phase` to `updated` and add `reviewer`. Check that the other custom keys
remain, creation time is unchanged, modification time is valid, and object checksum, size and
chunk IDs are unchanged. Download again. Walk list pages until the object is found and validate
the required list-entry fields.

**Explain:** "Metadata updates do not require re-uploading object bytes. The PATCH contains only
JSON; the same chunks still reconstruct the same object. Listing exposes object summaries."

Expected: PATCH 200, metadata values changed, checksum matches, list GET 200.

## Stage 6: normal deletion and error responses

**What happens:** upload a second small text file without custom metadata. It receives a different
ID and downloads with `text/plain`. Delete it while all workers are online; expect 204. Check
its chunk files are absent from all workers, subsequent GET and DELETE are 404, and listing no
longer includes it. Also test unknown metadata/PATCH targets, malformed upload metadata,
non-object PATCH JSON and a non-integer pagination value.

**Explain:** "204 is successful physical deletion. 404 means that object no longer exists.
Invalid client input receives 400 with a meaningful JSON message."

To also test a real PDF, image, video, audio or archive, supply an additional nonempty file:

```powershell
.\docs\demo-requirements.ps1 -Interactive `
    -AdditionalFile 'C:\Users\raipr\Downloads\Mini_Distributed_Cloud_Object_Store (1).pdf' `
    -AdditionalContentType 'application/pdf'
```

Use the correct Content-Type for your file, for example `image/png`, `video/mp4`, `audio/mpeg`
or `application/zip`. The uploaded copy is downloaded, compared and deleted. The original local
file is read only. Default runs test binary data and text; one sample per format is not an
exhaustive proof of every file type. This application stores opaque bytes rather than interpreting
their document/media format. Empty-file uploads are currently rejected by the inherited API.

## Stage 7: worker failure and recovery

**What happens:** select a worker that actually holds a replica of the main object. Save the
before snapshot, stop that Docker container, and download successfully using available copies.
Wait for heartbeat timeout to mark the worker DEAD. Save a detection timeline and status, then
wait for two usable copies per chunk on the remaining workers. Restart the stopped worker,
wait for registration/reconciliation, trim excess copies and verify all bytes again.

**Explain:** "A stopped worker's copy is unavailable. Other valid replicas allow reads to
continue. The controller detects the missing heartbeat and restores the replication factor
when another healthy worker can receive a copy. A returning worker must reconcile its inventory
before its replicas are trusted."

The 20-second timeout is measured from the last heartbeat, not from when you press Enter.
Detection, repair and polling run asynchronously. A snapshot may already show repair complete;
do not claim that the saved sample necessarily captured the brief under-replicated interval.
`worker-dead.json`, `repaired-while-offline.json` and `after-worker-recovery.json` record observed
states. The direct physical checks after recovery must show exactly two valid copies per chunk.

The chosen worker may be worker1, worker2, worker3 or worker4. The PDF's `docker stop worker2`
is an example; selecting an actual holder makes the test meaningful.

## Stage 8: corruption and integrity

**What happens:** choose one replica of the new object. Briefly pause the controller so repair
cannot remove the corruption evidence before inspection. Overwrite that worker's chunk file
with invalid data and call its GET endpoint; expect a meaningful JSON 409. Unpause immediately,
download the entire object through the controller, and compare its SHA-256. Wait for repair,
then retrieve and verify both physical copies of every chunk.

**Explain:** "A worker validates its stored chunk before returning it. The damaged replica is
rejected. A valid copy lets the controller serve the correct object and restore replication."

The pause is a demonstration control. It makes the 409 observation repeatable; it is not part
of the production recovery algorithm. The controller is running during the object download and
repair checks. If repair finishes before download begins, the hash check demonstrates correct
post-repair retrieval rather than proving which replica that particular request selected.

Evidence includes the 409 response body/headers, downloaded data and
`after-corruption-repair-physical-locations.json`.

## Stage 9: controller restart

**What happens:** restart the controller without removing its volume. Wait for workers to
re-register, download the same object and compare its SHA-256.

**Explain:** "The controller metadata survives a process restart. Workers establish fresh
registrations and the object remains retrievable."

This is additional reliability evidence. It does not demonstrate high availability: with one
controller, client requests are unavailable while that controller is stopped.

## Stage 10: deletion with an offline worker

**What happens:** stop a current holder and wait for DEAD detection. DELETE returns 503 while
physical cleanup is incomplete. Repeating DELETE remains 503. Download returns 404, metadata
shows DELETING, list omits the object, and PATCH returns 409. Restart the controller while the
worker stays offline and verify the same pending state. Return the worker, then poll metadata
using GET only until background cleanup completes. Check every chunk file is gone and the final
metadata GET and repeated DELETE return 404.

**Explain:** "The system does not acknowledge successful physical deletion while a worker may
still hold bytes. Durable cleanup jobs preserve outstanding work. Restarting the controller
does not forget those jobs. When the worker returns, cleanup completes automatically."

Stage 6 demonstrated the PDF's normal DELETE -> 204 contract. Here 503 reports storage that is
temporarily unavailable. A later DELETE can return 204 if that request finishes deletion;
it returns 404 if background cleanup already finished. Worker-level repeated DELETE returns
204, whereas repeating a completed object-level DELETE returns 404.

Retries back off from 5 seconds to at most 300 seconds. This stage allows 420 seconds after the
worker returns. Completion depends on the persistent controller database and the recorded worker
identity, address and storage returning. If a worker never returns, deletion remains pending.
Small `.deleted` marker files are retained intentionally; the object payload `.chunk` files
must be absent. Completely unknown files with no controller metadata/write history are outside
this cleanup guarantee.

## Evidence and interpreting the result

Each run creates `data/requirements-demo-<unique ID>/` inside the project, containing:

| File | Use |
| --- | --- |
| `transcript.txt` | Stage descriptions, actual responses, PASS checks and printed location tables |
| `results.json` | Overall `passed` flag and individual timestamped assertions |
| `http-requests.csv` | Request name, method, URL, actual status, body path and header path |
| `NNN-*.headers.txt`, `NNN-*.body` | Raw HTTP evidence, including downloads and JSON errors |
| `original.bin`, `test-object.json` | Original binary data, object ID and checksum |
| `*-physical-locations.json` | Both observed worker locations per chunk |
| `failure-detection-timeline.json`, other snapshots | Before/during/after worker and controller failure states |
| `controller.log`, `worker1.log` ... `worker4.log` | Last 250 log lines per container at completion/failure |

A complete pass ends with `PASS: all ten demonstration stages completed.` and
`results.json` containing `"passed": true`. The final script also saves evidence and container
logs. Do not treat a partial list of PASS lines as a complete pass.

To locate the most recent evidence:

```powershell
$latest = Get-ChildItem -LiteralPath .\data -Directory -Filter 'requirements-demo-*' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Get-Content -LiteralPath (Join-Path $latest.FullName 'results.json') -Raw
Import-Csv -LiteralPath (Join-Path $latest.FullName 'http-requests.csv') |
    Select-Object name, method, status | Format-Table -AutoSize
```

If a stage fails, keep its evidence. The script attempts to unpause the controller and restart
workers it stopped. A forced terminal/process termination can prevent `finally` from running;
check `docker compose ps`, unpause a paused controller with `docker unpause controller`, and
start a stopped worker with `docker start workerN`. The failed run's objects may remain so the
failure can be investigated. A subsequent run creates fresh IDs.

Useful diagnostic commands:

```powershell
docker compose ps
docker compose logs --tail=100 controller worker1 worker2 worker3 worker4
Invoke-RestMethod 'http://localhost:8081/internal/status' | ConvertTo-Json -Depth 12
```

Persistent volumes must remain in place for restart demonstrations. `docker compose down -v`
deletes those volumes and is not a restart test.

The assignment requires restoration **when possible**. If every valid copy of a chunk is
permanently lost, replication cannot recreate the bytes without an independent backup or
re-upload. That is a different failure from the one-worker and one-corrupt-replica demonstrations.

# Handoff: Controller API (Person A's half) → Person B

Branch: `feat/controller-api` (pushed, PR not yet opened against `main`).
Stack: Java 21, Spring Boot **4.1.2** (not 3.3.x — IntelliJ generated the original scaffold
on 4.1.2, so the whole project stayed on it; starter artifact names differ from the usual
Boot 3.x ones, see "Gotchas" below), Maven multi-module.

## What exists right now

```
Final_Project/
├── pom.xml              # root aggregator (packaging=pom)
├── common/               # plain jar, zero Spring deps — the shared wire contract
├── controller/           # DONE — Person A's module, full REST API
├── worker/               # EMPTY PLACEHOLDER — this is yours
└── docs/
    ├── api.md            # full endpoint reference with curl examples
    └── demo-A.sh          # runnable end-to-end demo script
```

### `common` module (groupId `com.example`, artifactId `common`)
- `Constants.CHUNK_SIZE` (1 MB), `Constants.REPLICATION_FACTOR` (2)
- `ChecksumUtil.sha256(byte[])`
- DTOs for the internal contract: `RegisterRequest(workerId, url, List<ChunkInfo>)`,
  `HeartbeatRequest(workerId, usedBytes, chunkCount)`, `ChunkInfo(chunkId, size, checksum)`,
  `ErrorResponse(timestamp, status, error, message, path)`

### `controller` module — fully implemented
All client-facing endpoints under `/api/v1/objects` (upload, download, metadata GET/PATCH,
list, delete), backed by:
- JPA entities: `StoredObject`, `Chunk`, `Replica`, `Worker` (package `domain`)
- Repositories (package `repository`)
- `PlacementService` (package `service`) — worker selection for replica placement
- `WorkerClient` abstraction (package `client`) — `HttpWorkerClient` (real) +
  `InMemoryWorkerClient`/`FakeWorkerSeeder` (under Spring profile `fake`, for testing without
  your worker module existing)
- Global `@RestControllerAdvice` error handler → `common.dto.ErrorResponse`
- 24 passing tests, `docs/api.md`, `docs/demo-A.sh`

Full endpoint reference with request/response shapes and curl examples: **[docs/api.md](api.md)**.

## What's stable and safe to build on

These are the integration points you'll actually use — I won't rename/change these without
telling you:

- **Entities** (`com.example.controller.domain`): `StoredObject`, `Chunk`, `Replica`, `Worker`
  — fields and enums (`ObjectStatus`, `ReplicaState`, `WorkerStatus`) as-is.
- **Repositories**: `WorkerRepository.findByStatus(WorkerStatus)`,
  `ReplicaRepository.findByChunkId(String)` /
  `findByChunkIdAndWorkerId(String, String)`, `ChunkRepository.findByObjectIdOrderByChunkIndex(String)`.
- **`PlacementService.pickOneNotIn(Set<String> excludedWorkerIds)`** — public, for your
  ReplicationManager to pick a target worker that isn't already holding a copy of a chunk.
- **`DownloadService.fetchVerified(Chunk)`** — public, returns verified bytes from a healthy
  replica (and marks a bad one `CORRUPT`); reuse this instead of writing your own fetch logic
  if your scrubber/repair code needs to read a chunk's current bytes.
- **`common` DTOs** (`RegisterRequest`, `HeartbeatRequest`, `ChunkInfo`) — the wire shape for
  `/internal/workers/register` and `/internal/workers/heartbeat`. I did **not** implement
  those endpoints or any registration/heartbeat/failure-detection logic — that's entirely
  yours, this module only defines the shared request/response shape.

## What's explicitly NOT done (yours to build)

- `worker/` module: ChunkStore, worker REST (`PUT/GET/DELETE /internal/chunks/{id}`), atomic
  writes, scrubber — the `worker/pom.xml` is a bare empty placeholder with no `src/`, waiting
  for you.
- `/internal/workers/register` and `/internal/workers/heartbeat` controller endpoints on the
  Controller side, plus `FailureDetector`, `ReplicationManager`, reconcile-on-register, and any
  admin replication endpoint.
- Docker/Compose, Dockerfiles, README's build/run section.
- When a real worker is registered, `HttpWorkerClient` is what the Controller will use to talk
  to it (PUT/GET/DELETE `/internal/chunks/{chunkId}`, `X-Checksum` header, 2s connect / 10s
  read timeout) — make sure your worker's endpoints match that contract exactly (see the table
  in the original project PDF / `docs/api.md`'s intro).

## How to run it on your machine

```bash
mvn clean install                                    # build + run all tests
mvn -pl controller spring-boot:run -Dspring-boot.run.profiles=fake   # run standalone, no Docker/workers needed
```
Fake profile seeds 4 in-memory fake workers (`worker1`-`worker4`) so you can exercise the full
upload/download/replication flow before your worker module exists. Try `bash docs/demo-A.sh`
once it's running (port **8081** by default — see `controller/src/main/resources/application.yml`).

## Gotchas worth knowing about

- **Spring Boot 4.1.2, not 3.x** — starter artifact IDs differ from what you'd expect:
  `spring-boot-starter-webmvc` (not `-web`), `spring-boot-h2console`,
  `spring-boot-starter-data-jpa-test` / `-validation-test` / `-webmvc-test` for test scopes.
- **Jackson is now Jackson 3**, under package `tools.jackson.*` (not `com.fasterxml.jackson.*`)
  and groupId `tools.jackson.core`. If your worker module does its own JSON handling, use the
  `tools.jackson.databind.ObjectMapper` import, not the old one.
- Tests use an **isolated in-memory H2** (`controller/src/test/resources/application.yml`),
  separate from the dev-run file DB (`./data/objectstore`) — don't let that db file's leftover
  data confuse you when poking at the API manually; it's gitignored and safe to delete anytime.
- Spring Data derived `deleteBy...` repository methods need `@Transactional` on the calling
  service method (learned this the hard way in `UploadService.rollbackUpload` /
  `DeleteService.deleteObject`) — keep that in mind if you add similar bulk-delete logic.

## Questions? Ping Person A (this branch's author) before renaming any entity field, changing
`PlacementService`/`DownloadService`'s public method signatures, or touching the `common` DTOs
— those are the seams our two halves meet at.

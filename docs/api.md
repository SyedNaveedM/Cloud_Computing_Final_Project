# Controller API

Client-facing REST API for the Mini Distributed Cloud Object Store. The Controller is the
only service a client needs to contact (`/api/v1/...`). Metadata includes replica worker
IDs for inspection; clients do not need to contact workers to upload or download objects.

Base URL in local/dev runs: `http://localhost:8081`

All responses are JSON unless otherwise noted. All error responses share this shape:

```json
{
  "timestamp": "2026-01-01T12:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Object <id> not found",
  "path": "/api/v1/objects/<id>"
}
```

## Status code summary

| Status | Meaning | Example cause |
|---|---|---|
| 200 | OK | successful GET/PATCH |
| 201 | Created | upload succeeded |
| 204 | No Content | delete succeeded |
| 400 | Bad Request | empty/missing file, malformed metadata JSON, non-object PATCH body, invalid pagination |
| 404 | Not Found | object/chunk does not exist, or object is not COMPLETE |
| 409 | Conflict | PATCH metadata while the object is DELETING |
| 500 | Internal Server Error | unexpected failure |
| 503 | Service Unavailable | fewer than 2 ALIVE workers, replication/read failed on every replica, physical deletion still pending |

---

## `POST /api/v1/objects` — Upload an object

Multipart form fields:

| Field | Required | Description |
|---|---|---|
| `file` | yes | The file to store. Must be non-empty. |
| `metadata` | no | A JSON **string** of custom key/value pairs, e.g. `{"owner":"alice"}`. |

```bash
curl -i -X POST http://localhost:8081/api/v1/objects \
  -F "file=@/path/to/photo.jpg" \
  -F 'metadata={"owner":"alice","project":"demo"}'
```

**201 Created** — `Location: /api/v1/objects/{id}`

```json
{
  "id": "2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222",
  "name": "photo.jpg",
  "size": 1245184,
  "contentType": "image/jpeg",
  "checksum": "ab12...ef90",
  "chunkCount": 2,
  "status": "COMPLETE",
  "createdAt": "2026-01-01T12:00:00Z",
  "modifiedAt": "2026-01-01T12:00:01Z",
  "customMetadata": {"owner": "alice", "project": "demo"}
}
```

Errors: `400` (missing/empty file, malformed `metadata` JSON), `503` (fewer than 2 ALIVE
workers, or a chunk could not be replicated to 2 workers). Failed uploads are not published.
Recorded worker copies are queued for cleanup; physical deletion may finish later if a
worker is unavailable.

---

## `GET /api/v1/objects/{id}` — Download an object

```bash
curl -i http://localhost:8081/api/v1/objects/2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222 \
  -o photo.jpg
```

**200 OK** — raw bytes, with `Content-Type`, `Content-Length`, and
`Content-Disposition: attachment; filename="..."` set from the stored object.

Errors: `404` (unknown object, or not COMPLETE), `503` (a chunk has no healthy replica on an
ALIVE worker — checked before any bytes are sent).

---

## `GET /api/v1/objects/{id}/metadata` — Inspect an object

```bash
curl -s http://localhost:8081/api/v1/objects/2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222/metadata
```

**200 OK**

```json
{
  "id": "2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222",
  "name": "photo.jpg",
  "size": 1245184,
  "contentType": "image/jpeg",
  "checksum": "ab12...ef90",
  "chunkCount": 2,
  "status": "COMPLETE",
  "createdAt": "2026-01-01T12:00:00Z",
  "modifiedAt": "2026-01-01T12:00:01Z",
  "customMetadata": {"owner": "alice", "project": "demo"},
  "chunks": [
    {
      "index": 0,
      "chunkId": "c1...",
      "size": 1048576,
      "checksum": "11...",
      "replicas": [
        {"workerId": "worker1", "state": "OK"},
        {"workerId": "worker3", "state": "OK"}
      ]
    },
    {
      "index": 1,
      "chunkId": "c2...",
      "size": 196608,
      "checksum": "22...",
      "replicas": [
        {"workerId": "worker2", "state": "OK"},
        {"workerId": "worker4", "state": "OK"}
      ]
    }
  ]
}
```

Errors: `404` (unknown object).

---

## `PATCH /api/v1/objects/{id}/metadata` — Update custom metadata

Body is a flat JSON object merged into `customMetadata`. **A `null` value removes that key**;
any other value sets it. Chunks are never touched and no re-upload is needed.

```bash
curl -i -X PATCH http://localhost:8081/api/v1/objects/2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222/metadata \
  -H "Content-Type: application/json" \
  -d '{"owner": null, "status": "reviewed"}'
```

**200 OK** — the full metadata response (same shape as the GET above) with `owner` removed,
`status` set, and `modifiedAt` updated.

Errors: `400` (body is not a JSON object), `404` (unknown object), `409` (object is DELETING).

---

## `GET /api/v1/objects` — List objects

Only `COMPLETE` objects are listed.

`page` defaults to `0` and must be a nonnegative integer. `size` defaults to `20` and
must be a positive integer. Non-integer values and values outside the supported integer
range return `400 Bad Request` with the same JSON error structure used by other endpoints.

```bash
curl -s "http://localhost:8081/api/v1/objects?page=0&size=20"
```

**200 OK**

```json
{
  "items": [
    {"id": "2f6f2c8e-...", "name": "photo.jpg", "size": 1245184, "contentType": "image/jpeg"}
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

---

## `DELETE /api/v1/objects/{id}` — Delete an object

```bash
curl -i -X DELETE http://localhost:8081/api/v1/objects/2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222
```

**204 No Content** after deletion completes. The object is marked `DELETING`, hidden from
downloads and listing, and cleanup jobs are persisted for recorded replicas and possible
copies tracked by write intents. Metadata is removed after all queued deletions are
acknowledged.

If cleanup cannot finish during the request, return **503 Service Unavailable**. Background
cleanup retries, including when an offline worker returns. Metadata GET exposes the
`DELETING` state while cleanup is pending. A later DELETE can return 204 if it completes
the remaining work, or 404 if background cleanup has already finished.

A 503 response here means deletion has started but is not physically confirmed everywhere.
The object stays unavailable; it does not become downloadable again on a retry or controller
restart. Retrying the same DELETE preserves completed work and the remaining jobs' backoff.
Each request attempts up to 100 due jobs for this object; the background runner also handles
larger deletions. Retries back off from 5 seconds to a maximum of 300 seconds, so completion
after a worker returns may take several minutes. No re-upload or further DELETE request is
needed for background completion. This requires maintenance enabled and the controller's
persistent database retained, as configured in `compose.yaml`.

Errors: `404` (unknown object, or already deleted), `503` (physical deletion pending).

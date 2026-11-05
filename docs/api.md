# Controller API

Client-facing REST API for the Mini Distributed Cloud Object Store. The Controller is the
only thing a client talks to directly (`/api/v1/...`); it never exposes worker locations.

Base URL in local/dev runs: `http://localhost:8080`

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
| 400 | Bad Request | empty/missing file, malformed metadata JSON, non-object PATCH body |
| 404 | Not Found | object/chunk does not exist, or object is not COMPLETE |
| 409 | Conflict | PATCH metadata while the object is DELETING |
| 500 | Internal Server Error | unexpected failure |
| 503 | Service Unavailable | fewer than 2 ALIVE workers, replication/read failed on every replica |

---

## `POST /api/v1/objects` — Upload an object

Multipart form fields:

| Field | Required | Description |
|---|---|---|
| `file` | yes | The file to store. Must be non-empty. |
| `metadata` | no | A JSON **string** of custom key/value pairs, e.g. `{"owner":"alice"}`. |

```bash
curl -i -X POST http://localhost:8080/api/v1/objects \
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
workers, or a chunk could not be replicated to 2 workers — nothing is left behind in either
case).

---

## `GET /api/v1/objects/{id}` — Download an object

```bash
curl -i http://localhost:8080/api/v1/objects/2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222 \
  -o photo.jpg
```

**200 OK** — raw bytes, with `Content-Type`, `Content-Length`, and
`Content-Disposition: attachment; filename="..."` set from the stored object.

Errors: `404` (unknown object, or not COMPLETE), `503` (a chunk has no healthy replica on an
ALIVE worker — checked before any bytes are sent).

---

## `GET /api/v1/objects/{id}/metadata` — Inspect an object

```bash
curl -s http://localhost:8080/api/v1/objects/2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222/metadata
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
curl -i -X PATCH http://localhost:8080/api/v1/objects/2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222/metadata \
  -H "Content-Type: application/json" \
  -d '{"owner": null, "status": "reviewed"}'
```

**200 OK** — the full metadata response (same shape as the GET above) with `owner` removed,
`status` set, and `modifiedAt` updated.

Errors: `400` (body is not a JSON object), `404` (unknown object), `409` (object is DELETING).

---

## `GET /api/v1/objects` — List objects

Only `COMPLETE` objects are listed.

```bash
curl -s "http://localhost:8080/api/v1/objects?page=0&size=20"
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
curl -i -X DELETE http://localhost:8080/api/v1/objects/2f6f2c8e-2222-4b3e-9b2a-1111aaaa2222
```

**204 No Content** on success. The object is marked `DELETING`, its chunks are deleted from
every replica on an **ALIVE** worker (best-effort — a DEAD worker is skipped and its orphaned
chunks are cleaned up later by the registration/reconcile logic), then all rows are removed.

Errors: `404` (unknown object, or already deleted).

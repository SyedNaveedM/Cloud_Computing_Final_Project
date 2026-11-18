# Cloud_Computing_Final_Project - Object storage
## Syed Naveed Mohammed - IMT2023119
## Pragya Rai - IMT2023529

## Running the full system

Use Java 21. Run `./mvnw clean test` (Windows: `.\mvnw.cmd clean test`), then
`docker compose up --build -d`. The controller is at `http://localhost:8081`.
Wait for four ALIVE workers at `/internal/status` before uploading objects.

The complete build, architecture decisions, failure behavior, and demonstration
instructions are in [docs/PERSON-B-RUNBOOK.md](docs/PERSON-B-RUNBOOK.md).
On Windows, run `.\docs\demo-B.ps1` from the project root to exercise binary
round trips, metadata, replication, worker restart, corruption recovery, controller
restart, and pending deletion. This demonstration intentionally damages one copy
of a newly created test object and cleans up that object afterward.

Docker volumes keep the database and worker chunks across container restarts.
`docker compose down` preserves volumes; `docker compose down -v` destroys them.

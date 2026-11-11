---
status: accepted
---

# Async query API: submit with POST, fetch by BigQuery job id

The Calls lookup is asynchronous: `POST /api/v1/query` creates a BigQuery query job and returns `202 Accepted` with a `jobId`, and `GET /api/v1/query/{jobId}` returns the job's status and, once `DONE`, the Calls. This replaces the synchronous `GET /api/v1/query`, a deliberate breaking change. The pattern is chosen to demonstrate async job submission in this template, not to fix a latency problem: the lookup is partition-pruned and capped at 1,000 rows, so it is already fast. Decided in #35.

## Decisions

- **The BigQuery job id is the only state.** There is no Firestore, Redis or table of our own, so any Cloud Run instance can serve either call and nothing is lost when an instance restarts or scales to zero. Results expire when BigQuery expires the job (about 24 hours); an unknown or expired id returns `404`. The BigQuery location is pinned in configuration and set on every job call, rather than encoded in the id.
- **Job ids are server-generated as `calls-query-<uuid>`.** `GET` accepts only that format and returns `404` for anything else without calling BigQuery. Without this, the endpoint would read any job in the project (for example another service's queries) by id.
- **No Cloud Run changes.** The query runs inside BigQuery. `POST` returns once the job is created and each `GET` is a short, independent request, so nothing runs after a response is sent. Default request-only CPU, scale-to-zero and concurrency settings keep working.
- **Spring Boot stays.** The design needs only the BigQuery Java client and two REST endpoints, so it is no reason to reopen #9.

## Considered options

- **Virtual threads only** (`spring.threads.virtual.enabled`): a one-line change with no API change, but it does not demonstrate submit-then-fetch.
- **WebFlux, `DeferredResult` or `CompletableFuture`**: non-blocking request handling, but the client still holds one open request until the query finishes.
- **Push completion (SSE or Pub/Sub)**: needs a channel that survives stateless, scale-to-zero instances; more moving parts than polling.

## Consequences

- Clients must poll `GET /api/v1/query/{jobId}` and handle `PENDING`, `RUNNING`, `DONE` and `FAILED`.
- Cancellation is out of scope for now. An abandoned job finishes or expires in BigQuery. A `DELETE /api/v1/query/{jobId}` backed by `bigQuery.cancel` can be added later without breaking anything (#38).

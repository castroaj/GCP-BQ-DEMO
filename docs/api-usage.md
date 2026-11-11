# API usage

How to call the Calls API. The contract is in [spec.md](spec.md#api-6-8-4); deploying it is in [gcp/api-deployment.md](gcp/api-deployment.md). Terms follow [GLOSSARY.md](../GLOSSARY.md).

## Endpoints

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/query` | Submit a lookup of one number in a time range; returns a job id |
| `GET /api/v1/query/{jobId}` | Status of a lookup and, when done, its Calls |
| `GET /health` | Spring Boot Actuator health, `{"status":"UP"}` |

## Calling a private Cloud Run service

The deployed service is private, so every request carries an identity token:

```sh
PROJECT=$(terraform -chdir=terraform output -raw project_id)
REGION=$(terraform -chdir=terraform output -raw region)
URL=$(gcloud run services describe calls-api --project "$PROJECT" --region "$REGION" --format='value(status.url)')
TOKEN=$(gcloud auth print-identity-token)
```

A lookup is two calls: submit it, then poll for the result with the job id. The job id is a BigQuery job id, so any instance can answer; jobs expire after about 24 hours.

## `POST /api/v1/query`

Send a JSON body. All three fields are required.

| Field | Format |
|---|---|
| `telephoneNumber` | E.164 digits without the leading `+`, e.g. `15551234567`; matches Calls where `calling` or `called` equals it |
| `startDatetime` | ISO 8601 with an explicit UTC offset (`Z` or `+hh:mm`), inclusive |
| `endDatetime` | ISO 8601 with an explicit UTC offset, exclusive, after `startDatetime` |

```sh
curl -s -i -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "$URL/api/v1/query" \
  -d '{"telephoneNumber":"15550100025","startDatetime":"2026-01-15T00:00:00Z","endDatetime":"2026-01-16T00:00:00Z"}'
```

### Response (`202 Accepted`)

`{ "jobId": "calls-query-<uuid>" }` and a `Location: /api/v1/query/{jobId}` header.

## `GET /api/v1/query/{jobId}`

```sh
JOB_ID=calls-query-123e4567-e89b-12d3-a456-426614174000
curl -s -H "Authorization: Bearer $TOKEN" "$URL/api/v1/query/$JOB_ID"
```

### Response (`200`)

Always `{ jobId, status }`, where `status` is one of:

| `status` | Body |
|---|---|
| `PENDING`, `RUNNING` | nothing more; poll again |
| `DONE` | the result below |
| `FAILED` | `error: { status, title, detail, code }`, a generic Problem-Details-shaped error; BigQuery details go to the server log |

A `DONE` response adds `{ telephoneNumber, startDatetime, endDatetime, count, truncated, calls[] }`. Each call has all 20 columns (camelCase, nulls as JSON `null`, timestamps in UTC ISO 8601), sorted by `offhookDatetime` then `callId`. At most 1,000 calls are returned; `truncated` is `true` when more matched. An empty result is `200` with `count: 0`.

```json
{
  "jobId": "calls-query-123e4567-e89b-12d3-a456-426614174000",
  "status": "DONE",
  "telephoneNumber": "15550100001",
  "startDatetime": "2026-01-15T00:00:00Z",
  "endDatetime": "2026-01-16T00:00:00Z",
  "count": 1,
  "truncated": false,
  "calls": [
    {
      "callId": "6a843b16-13ed-41ce-b991-067894138f95",
      "calling": "15594194732",
      "called": "15550100001",
      "offhookDatetime": "2026-01-15T22:23:02.760879Z",
      "durationSeconds": 105,
      "callType": "voip",
      "direction": "outbound",
      "callStatus": "completed",
      "callingCarrier": "T-Mobile",
      "calledCarrier": "Verizon",
      "callingCountryCode": null,
      "calledCountryCode": "US",
      "callingCity": "New York",
      "calledCity": "London",
      "cellTowerId": "TWR-00994",
      "networkType": "3G",
      "codec": "EVS",
      "isRoaming": false,
      "isInternational": false,
      "costUsd": null
    }
  ]
}
```

### Test numbers on a seeded day

The data generator adds fixed numbers a known number of times on every generated day ([spec.md](spec.md#seed-data-7)). Looking up one seeded day gives:

| `telephoneNumber` | `count` | `truncated` |
|---|---|---|
| `15550100000` | 0 | `false` |
| `15550100001` | 1 | `false` |
| `15550100025` | 25 | `false` |
| `15550101500` | 1000 | `true` (1,500 match; the cap is 1,000) |

### Errors

Errors are RFC 9457 Problem Details (`application/problem+json`) with a machine-readable `code` property. Spring's own errors (unknown path, wrong method) use the same shape without a `code`. BigQuery details are never included; they go to the server log.

| Status | `code` | Cause |
|---|---|---|
| `400` | `MISSING_PARAMETER` | a required field is absent |
| `400` | `INVALID_PARAMETER` | a malformed number, or a datetime without an offset |
| `400` | `INVALID_RANGE` | `endDatetime` is not after `startDatetime` |
| `404` | `NOT_FOUND` | on `GET`: the id is not a `calls-query-<uuid>` id, or the job is unknown or expired |
| `502` | `UPSTREAM_ERROR` | BigQuery failed |
| `500` | `INTERNAL_ERROR` | anything unexpected |

```json
{
  "type": "about:blank",
  "title": "Invalid range",
  "status": 400,
  "detail": "endDatetime must be after startDatetime",
  "instance": "/api/v1/query",
  "code": "INVALID_RANGE"
}
```

## `GET /health`

```sh
curl -s -H "Authorization: Bearer $TOKEN" "$URL/health"
# {"status":"UP"}
```

## Without a token

`gcloud run services proxy calls-api --project "$PROJECT" --region "$REGION"` serves the service on `http://localhost:8080` with credentials attached, so the same calls work without the `Authorization` header.

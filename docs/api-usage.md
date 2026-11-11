# API usage

How to call the Calls API. The contract is in [spec.md](spec.md#api-6-8-4); deploying it is in [gcp/api-deployment.md](gcp/api-deployment.md). Terms follow [GLOSSARY.md](../GLOSSARY.md).

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/query` | Calls of one number in a time range |
| `GET /health` | Spring Boot Actuator health, `{"status":"UP"}` |

## Calling a private Cloud Run service

The deployed service is private, so every request carries an identity token:

```sh
PROJECT=$(terraform -chdir=terraform output -raw project_id)
REGION=$(terraform -chdir=terraform output -raw region)
URL=$(gcloud run services describe calls-api --project "$PROJECT" --region "$REGION" --format='value(status.url)')
TOKEN=$(gcloud auth print-identity-token)
```

## `GET /api/v1/query`

All three parameters are required.

| Parameter | Format |
|---|---|
| `telephoneNumber` | E.164 digits without the leading `+`, e.g. `15551234567`; matches Calls where `calling` or `called` equals it |
| `startDatetime` | ISO 8601 with an explicit UTC offset (`Z` or `+hh:mm`), inclusive |
| `endDatetime` | ISO 8601 with an explicit UTC offset, exclusive, after `startDatetime` |

```sh
curl -s -G -H "Authorization: Bearer $TOKEN" "$URL/api/v1/query" \
  --data-urlencode telephoneNumber=15550100025 \
  --data-urlencode startDatetime=2026-01-15T00:00:00Z \
  --data-urlencode endDatetime=2026-01-16T00:00:00Z
```

### Response (`200`)

`{ telephoneNumber, startDatetime, endDatetime, count, truncated, calls[] }`. Each call has all 20 columns (camelCase, nulls as JSON `null`, timestamps in UTC ISO 8601), sorted by `offhookDatetime` then `callId`. At most 1,000 calls are returned; `truncated` is `true` when more matched. An empty result is `200` with `count: 0`.

```json
{
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

The data generator adds fixed numbers a known number of times on every generated day ([spec.md](spec.md#seed-data-7)). Querying one seeded day gives:

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
| `400` | `MISSING_PARAMETER` | a required parameter is absent |
| `400` | `INVALID_PARAMETER` | a malformed number, or a datetime without an offset |
| `400` | `INVALID_RANGE` | `endDatetime` is not after `startDatetime` |
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

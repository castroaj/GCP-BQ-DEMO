# Prototype spec

Summary of the decisions from the first Wayfinder map (planning, closed). It is the current state, not a history; each section links the closed ticket that holds the full reasoning. If this file and a ticket disagree, the ticket wins until this file is fixed. Terms follow [GLOSSARY.md](../GLOSSARY.md).

**Strictly non-production prototype.** Authentication, secrets hardening, networking, HA, monitoring, CI/CD, multiple environments, cost controls, async queries, pagination and raw SQL from callers are out of scope.

## What is built

1. A BigQuery table of phone **Calls**, `telecom_analytics.calls`, created by Flyway and seeded with generated data.
2. A stateless Cloud Run API (Spring Boot, Java 21) that looks up Calls with a parameterized query and answers synchronously.

## Repo layout ([#14](https://github.com/castroaj/GCP-BQ-DEMO/issues/14))

| Path | Contents |
|---|---|
| `terraform/` | Project, APIs, dataset, bucket, service accounts |
| `flyway/` | Own Gradle project (wrapper committed) with `sql/V1__create_calls.sql` |
| `data-generator/` | `generate.py`, `load_range.sh`, `requirements.txt` |
| `api/` | Spring Boot, single-module Gradle |
| `docs/gcp/` | Setup docs (Terraform path and the older manual path) |
| `GLOSSARY.md`, `docs/adr/` | Domain language and decision records |

## GCP project and Terraform ([#2](https://github.com/castroaj/GCP-BQ-DEMO/issues/2), [#13](https://github.com/castroaj/GCP-BQ-DEMO/issues/13))

- Terraform in `terraform/` creates and destroys the whole throwaway project: project id `telecom-demo-<random hex>` (deleted ids are never reusable), billing link, APIs (BigQuery, Cloud Run, Artifact Registry, Cloud Build), the empty `telecom_analytics` dataset (location `US`), the GCS bucket `<project_id>-loads`, and the `calls-api` and `flyway` service accounts with their IAM.
- Cloud Run region `us-east4`.
- State is local and gitignored; secrets come from an untracked `terraform.tfvars`. `terraform destroy` removes everything in one pass.
- Not managed by Terraform: the `calls` table (Flyway) and the Cloud Run service (`gcloud run deploy --source`).
- Scripts take `PROJECT`, `BUCKET` and `DATASET` environment variables, set from `terraform -chdir=terraform output -raw project_id | bucket_name | dataset_id`. No wrapper script.
- Details: [docs/gcp/terraform.md](gcp/terraform.md).

## Table schema ([#5](https://github.com/castroaj/GCP-BQ-DEMO/issues/5))

`telecom_analytics.calls`, 20 camelCase columns.

- Real Call fields, all `NOT NULL`: `callId` STRING (UUID), `calling` STRING, `called` STRING (E.164 digits without the leading `+`, e.g. `15551234567`; the `+` is stripped at ingestion), `offhookDatetime` TIMESTAMP (UTC), `durationSeconds` INT64 (0 when missed).
- 15 nullable filler columns: `callType`, `direction`, `callStatus`, `callingCarrier`, `calledCarrier`, `callingCountryCode`, `calledCountryCode`, `callingCity`, `calledCity`, `cellTowerId`, `networkType`, `codec` (STRING); `isRoaming`, `isInternational` (BOOL); `costUsd` (NUMERIC).
- Partitioned daily on `DATE(offhookDatetime)`, clustered on `calling`, `called`.

## Flyway ([#3](https://github.com/castroaj/GCP-BQ-DEMO/issues/3), [#11](https://github.com/castroaj/GCP-BQ-DEMO/issues/11), [#12](https://github.com/castroaj/GCP-BQ-DEMO/issues/12))

- Flyway owns table creation. `V1__create_calls.sql` creates `calls` from the schema above. There is no manual `bq` table.
- Wiring: Gradle plugin `org.flywaydb.flyway` 13.12.1, with `flyway-gcp-bigquery` and Google's Maven-published `google-cloud-bigquery-jdbc:1.9.0:all` on the buildscript classpath. The driver class must be set explicitly (`com.google.cloud.bigquery.jdbc.BigQueryDriver`) and the JDBC URL needs `EnableSession=1`.
- Auth: Application Default Credentials (`OAuthType=3`). It runs as the human user. Moving to the `flyway` service account is a follow-up ([#17](https://github.com/castroaj/GCP-BQ-DEMO/issues/17)).
- Run by hand after `terraform apply`: `gcloud auth application-default set-quota-project $PROJECT`, `./gradlew --stop`, then `./gradlew flywayMigrate`. The stale quota project and the cached Gradle daemon are the usual failure. Terraform-triggered Flyway is a follow-up ([#18](https://github.com/castroaj/GCP-BQ-DEMO/issues/18)).
- No DDL transactions: a failed migration can leave partial changes. `flyway_schema_history` lives in the dataset.
- Fallback, documented only: adopt a hand-made table with `flywayBaseline` and `baselineVersion=1` (verified in the spike).
- Details: [docs/gcp/flyway-service-account.md](gcp/flyway-service-account.md).

## Seed data ([#7](https://github.com/castroaj/GCP-BQ-DEMO/issues/7))

- `data-generator/generate.py --date YYYY-MM-DD --out ./out [--rows 1000000] [--seed 42]` writes one UTC day: 1M rows as about 10 Snappy Parquet shards (`pyarrow`, `numpy`, plain venv and `requirements.txt`; no Faker).
- One shared pool of 5,000 E.164 numbers (`1` plus 10 digits, no `+`, skewed activity, `calling` != `called`) derived from the base seed, so numbers recur across days. Per-day seed derives from base seed and date, so re-running a date gives byte-identical files.
- Duration is 0 for missed, busy or failed Calls, otherwise skewed with a median of about 90 s. Filler columns come from small fixed lists with 5-20% nulls. `costUsd` derives from duration.
- Fixed test-needle numbers appear a known number of times on every generated day: `15550100000` (0), `15550100001` (1), `15550100025` (25), `15550101500` (1,500, over the API cap, to exercise `truncated`). They sit outside the pool, so the counts are exact.
- Explicit Parquet schema: strings, UTC microsecond timestamp for `offhookDatetime`, int64 `durationSeconds`, bool for the two flags, `decimal(38,9)` for `costUsd`.
- Upload to `gs://<bucket>/seed/calls/date=YYYY-MM-DD/part-NNNN.parquet` with `gcloud storage cp`, then `LOAD DATA INTO telecom_analytics.calls FROM FILES (format='PARQUET', uris=[...])` via `bq query --use_legacy_sql=false`. The bucket is the Terraform one (this replaces #7's `-seed` bucket name).
- Append only, no dedupe: re-loading a date doubles its rows. Manual cleanup: `DELETE FROM telecom_analytics.calls WHERE DATE(offhookDatetime) = '<date>'`. The load statement is a data step, not a migration.
- `data-generator/load_range.sh <start> <end>` loops over dates: generate, upload, load, then delete the local files and the uploaded GCS shards (only after a successful load); stops on the first failure. It fails fast if `PROJECT` or `BUCKET` is unset; `DATASET` defaults to `telecom_analytics`. A month is about 30M rows (estimate).

## API ([#6](https://github.com/castroaj/GCP-BQ-DEMO/issues/6), [#8](https://github.com/castroaj/GCP-BQ-DEMO/issues/8), [#4](https://github.com/castroaj/GCP-BQ-DEMO/issues/4))

- Endpoints: `GET /api/v1/query` and `GET /health`. No write endpoints.
- Request, all required query parameters:
  - `telephoneNumber`: E.164 digits without the leading `+` (e.g. `15551234567`); matches Calls where `calling` or `called` equals it. No normalization.
  - `startDatetime`, `endDatetime`: ISO 8601 with an explicit UTC offset, applied to `offhookDatetime`. Start inclusive, end exclusive, end after start.
- `200` body: `{ telephoneNumber, startDatetime, endDatetime, count, truncated, calls[] }`. Each call has all 20 columns (camelCase, nulls as JSON `null`, timestamps in UTC ISO 8601), sorted by `offhookDatetime` then `callId`. Fixed cap of 1,000 rows; `truncated` is `true` when more matched. No pagination. An empty result is `200` with `count: 0`.
- Errors: `400` for missing or malformed parameters or an invalid range, `502` for a BigQuery failure, `500` otherwise. The body is always an RFC 9457 Problem Details object (`application/problem+json`): `{ "type": "about:blank", "title": "...", "status": 400, "detail": "...", "instance": "<request path>", "code": "<CODE>" }`, where `code` is one of `MISSING_PARAMETER`, `INVALID_PARAMETER`, `INVALID_RANGE`, `UPSTREAM_ERROR`, `INTERNAL_ERROR`. Spring's own errors (unknown path, wrong method) use the same shape without a `code`. This replaces the earlier `{ "error", "message" }` shape. No BigQuery details or stack traces in bodies; log them server-side.
- Queries are parameterized with `QueryJobConfiguration.addNamedParameter` (values only); one reused BigQuery client; bind `0.0.0.0` on `$PORT`.
- Stack: Spring Boot, Java 21, `google-cloud-bigquery`, single-module Gradle in `api/` (`bootJar`, wrapper committed). Alternatives were left to [#9](https://github.com/castroaj/GCP-BQ-DEMO/issues/9).
- Deploy: buildpack `gcloud run deploy --source .` first, confirmed by a hello-world `/health` deploy as the first API task. A maintained Dockerfile is a follow-up ([#10](https://github.com/castroaj/GCP-BQ-DEMO/issues/10)).
- Identity: dedicated `calls-api` service account with `roles/bigquery.jobUser` (project) and `roles/bigquery.dataViewer` (`telecom_analytics` dataset only). Details: [docs/gcp/api-service-account.md](gcp/api-service-account.md).

## Build order

1. `terraform apply`.
2. Flyway: V1, ADC quota project, `./gradlew --stop`, `flywayMigrate`; check the table matches the schema.
3. Generator and `load_range.sh` for one day; check 1M rows and the needle counts.
4. API: hello-world `GET /health` buildpack deploy.
5. API: `GET /api/v1/query`, tested on the seeded day with the needle numbers.
6. Load the full month, then run the end-to-end check.
7. `terraform destroy`.

## Follow-ups outside the prototype

- [#9](https://github.com/castroaj/GCP-BQ-DEMO/issues/9) research alternative Java frameworks, [#10](https://github.com/castroaj/GCP-BQ-DEMO/issues/10) Dockerfile deploy, [#17](https://github.com/castroaj/GCP-BQ-DEMO/issues/17) Flyway on the service account, [#18](https://github.com/castroaj/GCP-BQ-DEMO/issues/18) Terraform runs Flyway.

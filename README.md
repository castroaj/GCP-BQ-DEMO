# GCP-BQ-DEMO

A **strictly non-production prototype** that explores BigQuery and Cloud Run for telecom analytics. See `GLOSSARY.md` for domain terms.

> Work in progress. This README is updated as the project is built.

## Purpose

- A BigQuery table of phone **Calls** (`telecom_analytics.calls`), seeded with generated data and later managed by Flyway.
- A stateless Cloud Run API (Spring Boot, Java 21) that turns structured requests into parameterized BigQuery queries and returns results synchronously.

## Status

Planning. Decisions are tracked in the GitHub issue map (label `wayfinder:map`); the prototype itself is not built yet.

## Planned layout

| Path | Contents |
|---|---|
| `api/` | Spring Boot API (Gradle, single module) |
| `data-generator/` | Python generator for synthetic Calls (Parquet) |
| `terraform/` | Terraform for the throwaway GCP project |
| `docs/gcp/` | GCP project and service account setup |
| `docs/adr/` | Architecture decision records |

## API

`GET /api/v1/query` with `telephoneNumber`, `startDatetime`, `endDatetime`; `GET /health`.

_Usage examples to be added once the API exists._

## Setup

- Terraform (recommended): [docs/gcp/terraform.md](docs/gcp/terraform.md)
- Manual alternative: [GCP project setup](docs/gcp/project-setup.md), [API service account](docs/gcp/api-service-account.md)

## Teardown

- Terraform: `terraform -chdir=terraform destroy`, then the local cleanup in [docs/gcp/terraform.md](docs/gcp/terraform.md#teardown)
- Manual: see the Teardown section of [project-setup.md](docs/gcp/project-setup.md#teardown)

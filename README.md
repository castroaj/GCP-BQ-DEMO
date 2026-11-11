# gcp-cloud-run-big-query-template

A **strictly non-production prototype** that explores BigQuery and Cloud Run for telecom analytics. See `GLOSSARY.md` for domain terms.

> Work in progress. This README is updated as the project is built.

## Purpose

- A BigQuery table of phone **Calls** (`telecom_analytics.calls`), seeded with generated data and later managed by Flyway.
- A stateless Cloud Run API (Spring Boot, Java 21) that turns structured requests into parameterized BigQuery queries and returns results synchronously.

## Architecture

Three diagrams: the running system, how Terraform sets up the project, and what a client request does. They share one legend, so a component keeps its shape and colour in every diagram.

```mermaid
flowchart LR
  L1(["Client / actor"]) ~~~ L2["Local tool"] ~~~ L3("Compute service") ~~~ L4[("Data store")] ~~~ L5{{"GCP resource / config"}}

  classDef client fill:#fde7c8,stroke:#555,color:#1a1a1a
  classDef tool fill:#dbe4f0,stroke:#555,color:#1a1a1a
  classDef compute fill:#d3ecd3,stroke:#555,color:#1a1a1a
  classDef store fill:#f6e3a1,stroke:#555,color:#1a1a1a
  classDef resource fill:#e3d5ee,stroke:#555,color:#1a1a1a
  class L1 client
  class L2 tool
  class L3 compute
  class L4 store
  class L5 resource
```

Solid arrows are data flow; dotted arrows are provisioning, deploy or grants. In the sequence diagram the participants carry the same names as the legend.

### Components

What exists at runtime and how Calls get in and out.

```mermaid
flowchart LR
  client(["API client"])
  api("Calls API<br/>Spring Boot, Cloud Run, us-east4")
  bq[("BigQuery<br/>telecom_analytics.calls")]
  gcs[("GCS bucket<br/>&lt;project_id&gt;-loads")]
  gen["data-generator<br/>load_range.sh"]
  flyway["Flyway<br/>flywayMigrate"]
  deploy["gcloud run deploy<br/>--source"]

  client -->|"GET /api/v1/query<br/>identity token"| api
  api -->|"parameterized query<br/>as calls-api SA"| bq
  gen -->|"upload Parquet shards"| gcs
  gcs -->|"LOAD DATA, then shards deleted"| bq
  flyway -->|"V1 creates the calls table"| bq
  deploy -.->|"builds and deploys"| api

  classDef client fill:#fde7c8,stroke:#555,color:#1a1a1a
  classDef tool fill:#dbe4f0,stroke:#555,color:#1a1a1a
  classDef compute fill:#d3ecd3,stroke:#555,color:#1a1a1a
  classDef store fill:#f6e3a1,stroke:#555,color:#1a1a1a
  class client client
  class gen,flyway,deploy tool
  class api compute
  class bq,gcs store
```

### Provisioning

`terraform apply` creates the throwaway project and everything around it; the `calls` table and the Cloud Run service are not Terraform's. Details: [docs/gcp/terraform.md](docs/gcp/terraform.md).

```mermaid
flowchart TD
  tf["terraform apply"]

  subgraph managed["Managed by Terraform"]
    project{{"GCP project<br/>telecom-demo-&lt;random hex&gt;<br/>billing linked"}}
    apis{{"Enabled APIs<br/>BigQuery, Cloud Run,<br/>Artifact Registry, Cloud Build"}}
    ds{{"Dataset<br/>telecom_analytics (US)"}}
    bucket[("GCS bucket<br/>&lt;project_id&gt;-loads")]
    sa_api{{"calls-api<br/>service account"}}
    sa_fly{{"flyway<br/>service account"}}
  end

  subgraph other["Not managed by Terraform"]
    flyway["Flyway"]
    table[("calls table")]
    deploy["gcloud run deploy<br/>--source"]
    api("Calls API")
  end

  tf -.->|creates| project
  project -.-> apis
  project -.-> ds
  project -.-> bucket
  project -.-> sa_api
  project -.-> sa_fly
  sa_api -.->|"jobUser (project)<br/>dataViewer (dataset)"| ds
  sa_fly -.->|"jobUser (project)<br/>dataEditor (dataset)"| ds
  flyway -.->|creates| table
  ds --- table
  deploy -.->|deploys| api

  classDef tool fill:#dbe4f0,stroke:#555,color:#1a1a1a
  classDef compute fill:#d3ecd3,stroke:#555,color:#1a1a1a
  classDef store fill:#f6e3a1,stroke:#555,color:#1a1a1a
  classDef resource fill:#e3d5ee,stroke:#555,color:#1a1a1a
  class tf,flyway,deploy tool
  class api compute
  class bucket,table store
  class project,apis,ds,sa_api,sa_fly resource
```

### Client request flow

A lookup is one synchronous request. Validation errors never reach BigQuery.

```mermaid
sequenceDiagram
  actor Client as API client
  participant API as Calls API
  participant BQ as BigQuery

  Client->>API: GET /api/v1/query<br/>telephoneNumber, startDatetime, endDatetime<br/>(Bearer identity token)
  alt parameters valid
    API->>BQ: parameterized query on telecom_analytics.calls<br/>(as calls-api service account)
    BQ-->>API: matching Calls
    API-->>Client: 200 JSON { count, truncated, calls[] }<br/>at most 1,000 Calls
  else missing or malformed parameter, or invalid range
    API-->>Client: 400 application/problem+json<br/>{ code: MISSING_PARAMETER | INVALID_PARAMETER | INVALID_RANGE }
  end
```

## Status

Planning is done; the decisions are summarized in [docs/spec.md](docs/spec.md). The build is tracked in a second GitHub issue map (label `wayfinder:map`). Built so far: Terraform, the Flyway V1 migration and the seed data generator.

## Planned layout

| Path | Contents |
|---|---|
| `api/` | Spring Boot API (Gradle, single module) |
| `flyway/` | Flyway Gradle project and `sql/V1__create_calls.sql` |
| `data-generator/` | Python generator and loader for synthetic Calls (Parquet) |
| `terraform/` | Terraform for the throwaway GCP project |
| `docs/gcp/` | GCP project and service account setup |
| `docs/adr/` | Architecture decision records |

## API

`GET /api/v1/query` with `telephoneNumber`, `startDatetime`, `endDatetime`; `GET /health`.

Usage examples, parameters, response and error shapes: [docs/api-usage.md](docs/api-usage.md). Deploy and teardown: [docs/gcp/api-deployment.md](docs/gcp/api-deployment.md).

## Setup

- Terraform (recommended): [docs/gcp/terraform.md](docs/gcp/terraform.md)
- API deploy and teardown: [docs/gcp/api-deployment.md](docs/gcp/api-deployment.md)
- Manual alternative: [GCP project setup](docs/gcp/project-setup.md), [API service account](docs/gcp/api-service-account.md)

## Loading seed data

Generate and load synthetic Calls into BigQuery with `data-generator/load_range.sh`: [docs/data-loading.md](docs/data-loading.md).

## Teardown

- Terraform: `terraform -chdir=terraform destroy`, then the local cleanup in [docs/gcp/terraform.md](docs/gcp/terraform.md#teardown)
- Manual: see the Teardown section of [project-setup.md](docs/gcp/project-setup.md#teardown)

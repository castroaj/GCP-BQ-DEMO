# Terraform: project lifecycle

Creates and destroys the whole throwaway GCP project with Terraform (`terraform/`, single root module). The manual path ([project-setup.md](project-setup.md), [api-service-account.md](api-service-account.md)) still works but hard-codes a project id.

## What it manages

- `google_project` with billing link (`deletion_policy = "DELETE"`), id = `telecom-demo-<random hex>` (deleted project ids are never reusable)
- APIs: BigQuery, Cloud Run, Artifact Registry, Cloud Build
- Empty `telecom_analytics` dataset (location `US`, `delete_contents_on_destroy`)
- GCS bucket `<project_id>-loads` for Parquet loads (`force_destroy`)
- Service accounts `calls-api` (project `jobUser`, dataset `dataViewer`) and `flyway` (project `jobUser`, dataset `dataEditor`)

Not managed: the `calls` table (Flyway V1), the Cloud Run service (`gcloud run deploy --source`), and the Flyway key file.

## Prerequisites

- `brew install hashicorp/tap/terraform` (>= 1.5) and `gcloud` ([project-setup.md](project-setup.md) steps 1-3)
- `gcloud auth application-default login` (tick every permission checkbox)
- An **open** billing account: `gcloud billing accounts list`

## Apply

```sh
cd terraform
cp terraform.tfvars.example terraform.tfvars   # set billing_account_id
terraform init
terraform apply
```

State is local and gitignored (`*.tfstate*`). If you lose it, fall back to `gcloud projects delete <project_id>`.

## Outputs / deploy wiring

```sh
terraform -chdir=terraform output -raw project_id
terraform -chdir=terraform output -raw calls_api_service_account_email
terraform -chdir=terraform output -raw bucket_name
```

Use these in `gcloud run deploy --project ... --service-account ...`; there is no wrapper script.

## Flyway key

Terraform creates the service account and roles but not the key, so the key stays out of state:

```sh
mkdir -p .secrets
gcloud iam service-accounts keys create .secrets/flyway-key.json \
  --iam-account="$(terraform -chdir=terraform output -raw flyway_service_account_email)"
```

`.secrets/` is gitignored.

## Teardown

```sh
cd terraform
terraform destroy
```

One pass: the project, dataset contents and bucket contents are all deleted. The project stays recoverable for about 30 days (`gcloud projects undelete <project_id>`).

Local cleanup afterwards:

```sh
rm -f .secrets/flyway-key.json
gcloud auth application-default revoke
rm -rf terraform/.terraform terraform/terraform.tfstate*
```

## Migrating from the hand-made project

`telecom-analytics-demo-48213` is not imported. Delete it (`gcloud projects delete telecom-analytics-demo-48213`) and let Terraform create a new one.

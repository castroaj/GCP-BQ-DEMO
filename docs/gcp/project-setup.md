# GCP project setup

How the throwaway GCP project for this prototype was provisioned. This is a strict non-production setup: one project, one region, everything disposable.

## Resulting configuration

| Setting | Value |
|---|---|
| Project id | `telecom-analytics-demo-48213` |
| Project number | `<PROJECT_NUMBER>` |
| Account | `<your-google-account>` |
| Billing account | your open billing account (`<BILLING_ACCOUNT_ID>`) |
| Cloud Run region | `us-east4` |
| Enabled APIs | BigQuery, Cloud Run, Artifact Registry, Cloud Build |
| BigQuery dataset location | Not yet decided (`US` multi-region vs `us-east4`) |

## Prerequisites

- macOS with [Homebrew](https://brew.sh).
- A Google account with an **open** billing account.

## Steps

### 1. Install the Google Cloud CLI

```sh
brew install --cask gcloud-cli
```

Installs `gcloud` (version 593.0.0 at the time) and bundles `bq` (the BigQuery CLI) and `gsutil`. Only `gcloud` is linked onto your PATH by Homebrew.

### 2. Put `bq` on your PATH

```sh
export PATH=/opt/homebrew/share/google-cloud-sdk/bin:$PATH
```

Makes `bq` available in the current shell. To keep it, add the line to `~/.zshrc`.

### 3. Log in

```sh
gcloud auth login
```

Opens a browser to sign in with your Google account. It stores credentials for the `gcloud` CLI itself. Check with `gcloud auth list`.

### 4. Create the project

```sh
gcloud projects create telecom-analytics-demo-48213 --name="telecom analytics demo"
```

Creates the project. Notes:

- The project id is immutable and globally unique. It must start with a lowercase letter, use only lowercase letters, digits and hyphens, and be 6–30 characters. `telecom_analytics_demo` is rejected because of the underscores; digits were appended to avoid a collision.
- `--name` is just a display label and can be changed later.
- A new project can take a minute to appear in the web console. In the console's project picker, use the **All** tab, or open `https://console.cloud.google.com/home/dashboard?project=telecom-analytics-demo-48213`. Make sure the console is signed in as the same account.

### 5. Attach billing

```sh
gcloud billing accounts list
gcloud billing projects link telecom-analytics-demo-48213 --billing-account=<BILLING_ACCOUNT_ID>
```

The first command lists billing accounts you can see. Only accounts with `OPEN` = `True` can be linked. The second links the chosen account to the project. Services such as Cloud Run and BigQuery cannot be enabled until billing is attached, and usage is billed to that account.

### 6. Set gcloud defaults

```sh
gcloud config set project telecom-analytics-demo-48213
gcloud config set run/region us-east4
```

Sets the default project and default Cloud Run region so later commands don't need `--project` or `--region`. Check with `gcloud config list`.

### 7. Enable the APIs

```sh
gcloud services enable \
  bigquery.googleapis.com \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com
```

Turns on the services the prototype needs:

- `bigquery.googleapis.com`: BigQuery, for the Call table.
- `run.googleapis.com`: Cloud Run, for the API.
- `artifactregistry.googleapis.com` and `cloudbuild.googleapis.com`: used when deploying from source (`gcloud run deploy --source .`), which builds the container and stores the image.

### 8. Set up Application Default Credentials (ADC)

```sh
gcloud auth application-default login
```

Creates credentials that client libraries and tools running locally (for example the data generator and BigQuery client code) pick up automatically. This is separate from step 3, which only authenticates the `gcloud` CLI.

On the browser consent screen, **tick every permission checkbox**. If the `cloud-platform` scope isn't granted, the command fails with `cloud-platform scope is required but not consented`. Re-run it, or use `gcloud auth application-default login --no-browser` for a manual flow.

On success the credentials are saved to `~/.config/gcloud/application_default_credentials.json`, and the project is set as the quota project.

## Verify

```sh
gcloud config list
gcloud services list --enabled --filter="config.name:(bigquery.googleapis.com OR run.googleapis.com OR artifactregistry.googleapis.com OR cloudbuild.googleapis.com)" --format="value(config.name)"
gcloud billing projects describe telecom-analytics-demo-48213 --format="value(billingEnabled)"
gcloud auth application-default print-access-token > /dev/null && echo "ADC OK"
bq ls --project_id=telecom-analytics-demo-48213
```

Expected: the project and region show in the config, all four APIs are listed, billing prints `True`, ADC prints `ADC OK`, and `bq ls` returns nothing (no datasets yet). `bq` may print a harmless `RequestsDependencyWarning` about urllib3.

## Teardown

Deleting the project removes everything in it and stops all billing:

```sh
gcloud projects delete telecom-analytics-demo-48213
```

Google keeps a deleted project recoverable for about 30 days (`gcloud projects undelete telecom-analytics-demo-48213`) before it is purged permanently.

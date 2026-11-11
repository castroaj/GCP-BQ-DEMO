# Cloud Run API service account

The Cloud Run API runs as a dedicated service account instead of the default compute service account. This is a throwaway, non-production setup. The commands below are the planned setup; run them once the `telecom_analytics` dataset exists.

## Resulting configuration

| Setting | Value |
|---|---|
| Service account | `calls-api@telecom-analytics-demo-48213.iam.gserviceaccount.com` |
| Project role | `roles/bigquery.jobUser` (run query jobs) |
| Dataset role | `roles/bigquery.dataViewer` on `telecom_analytics` only (read `calls`) |
| Auth from the service | Application Default Credentials via the Cloud Run service account, no key file |

The Flyway service account (later ticket) is separate because it needs write access.

## Steps

### 1. Create the service account

```sh
gcloud iam service-accounts create calls-api \
  --project=telecom-analytics-demo-48213 \
  --display-name="Calls API (Cloud Run)"
```

### 2. Grant `jobUser` on the project

```sh
gcloud projects add-iam-policy-binding telecom-analytics-demo-48213 \
  --member="serviceAccount:calls-api@telecom-analytics-demo-48213.iam.gserviceaccount.com" \
  --role="roles/bigquery.jobUser"
```

### 3. Grant `dataViewer` on the dataset only

Requires the dataset to exist. Export, edit and re-apply the dataset's access list:

```sh
bq show --format=prettyjson telecom-analytics-demo-48213:telecom_analytics > /tmp/dataset.json
# add to the "access" array:
#   {"role": "READER", "userByEmail": "calls-api@telecom-analytics-demo-48213.iam.gserviceaccount.com"}
bq update --source /tmp/dataset.json telecom-analytics-demo-48213:telecom_analytics
```

Dataset-level `READER` is the legacy equivalent of `roles/bigquery.dataViewer`. Alternatively, apply `roles/bigquery.dataViewer` directly with `bq add-iam-policy-binding` on the dataset.

### 4. Deploy the service with it

```sh
gcloud run deploy <service> --source . \
  --region=us-east4 \
  --service-account=calls-api@telecom-analytics-demo-48213.iam.gserviceaccount.com
```

### 5. Verify

```sh
gcloud projects get-iam-policy telecom-analytics-demo-48213 \
  --flatten="bindings[].members" \
  --filter="bindings.members:calls-api@" \
  --format="table(bindings.role)"
```

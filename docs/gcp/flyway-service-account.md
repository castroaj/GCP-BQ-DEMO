# Flyway service account

Flyway runs as a dedicated `flyway` service account that can create and alter tables in BigQuery. This is a throwaway, non-production setup. It is the write-capable counterpart of the read-only [`calls-api` account](api-service-account.md).

## Resulting configuration

| Setting | Value |
|---|---|
| Service account | `flyway@<project_id>.iam.gserviceaccount.com` |
| Project role | `roles/bigquery.jobUser` (run the DDL and history-table query jobs) |
| Dataset role | `roles/bigquery.dataEditor` on the target dataset only (create/alter tables, write `flyway_schema_history`) |
| Auth from Flyway | Service-account key file at `.secrets/flyway-key.json` (gitignored), referenced from the JDBC URL |

Terraform creates the account, the `jobUser` binding and the `dataEditor` binding on `telecom_analytics` (see [terraform.md](terraform.md)). It does not create the key, so the secret stays out of state. The steps below cover what Terraform leaves out, and how to grant the same account access to an additional dataset such as a scratch one.

## Steps

Run from the repo root. Set the project id first:

```sh
export P=$(terraform -chdir=terraform output -raw project_id)
```

### 1. Grant `dataEditor` on another dataset (optional)

Needed only for datasets Terraform does not manage, e.g. a scratch dataset for a Flyway experiment:

```sh
bq --project_id=$P --location=US mk -d flyway_spike

bq --project_id=$P query --use_legacy_sql=false \
  "GRANT \`roles/bigquery.dataEditor\` ON SCHEMA flyway_spike TO \"serviceAccount:flyway@$P.iam.gserviceaccount.com\""
```

Alternatively export the dataset with `bq show --format=prettyjson`, add `{"role": "WRITER", "userByEmail": "flyway@<project_id>.iam.gserviceaccount.com"}` to its `access` array and re-apply it with `bq update --source`. `WRITER` is the legacy equivalent of `dataEditor`.

The grant is per dataset on purpose: the account has no write access anywhere else in the project.

### 2. Create the key

```sh
mkdir -p .secrets
gcloud iam service-accounts keys create .secrets/flyway-key.json \
  --iam-account="flyway@$P.iam.gserviceaccount.com"
```

Run this from the **repo root**. `.secrets/` is relative to the current directory, so running it from `terraform/` puts the key in `terraform/.secrets/` instead (still gitignored, but not where the other docs expect it).

### 3. Check the key is gitignored

```sh
git check-ignore .secrets/flyway-key.json
```

It must print the path. If it prints nothing, do not commit anything until `.gitignore` is fixed.

### 4. Point Flyway at the key

The key path goes in the JDBC URL, not in `user`/`password`:

```
jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=<project_id>;OAuthType=0;OAuthPvtKeyPath=<abs path to .secrets/flyway-key.json>;DefaultDataset=<dataset>;EnableSession=1;
```

`OAuthType=0` selects service-account key auth. `EnableSession=1` is required with Google's Maven-published JDBC driver, otherwise Flyway fails with "Session needs to be enabled to use setAutoCommit method". Driver and Gradle wiring are covered by the Flyway spike (#11).

To skip the key file and use Application Default Credentials instead, see [Key file vs ADC](#key-file-vs-adc).

### 5. Verify

```sh
gcloud projects get-iam-policy $P \
  --flatten="bindings[].members" \
  --filter="bindings.members:flyway@" \
  --format="table(bindings.role)"

bq --project_id=$P show --format=prettyjson flyway_spike | grep -B1 -A1 "flyway@"
```

The first shows `roles/bigquery.jobUser`. The second shows the dataset grant from step 1.

## Flyway as a Cloud Run job

`terraform apply` runs the migration inside GCP as the `flyway` account, with no key file and no local Gradle ([#44](https://github.com/castroaj/gcp-cloud-run-big-query-template/issues/44), which folds in #18).

| Piece | How |
|---|---|
| Image | `flyway/Dockerfile`: JDK 21, the Gradle project and `sql/`, entrypoint `./gradlew --no-daemon flywayMigrate`. A pre-warm `./gradlew help` layer caches the Gradle and Flyway downloads. |
| Build | `terraform_data.flyway_build` runs `gcloud builds submit flyway/` (Cloud Build, no local Docker) into the Terraform-managed Artifact Registry repo `flyway`, tagged with a hash of the Dockerfile, Gradle files and `sql/*`. |
| Job | `google_cloud_run_v2_job.flyway`, image pinned by digest, env `PROJECT` and `DATASET`, runs as `flyway` (reader on the repo only). ADC comes from the attached account. |
| Trigger | `terraform_data.flyway_migrate` executes the job after the dataset, the `flyway` IAM bindings and the job exist. Terraform owns the first migration. |

A new migration reaches the job by editing `flyway/sql/` and running `terraform apply`: the hash changes, the image is rebuilt, the new digest changes the job and re-triggers the execution. A second run with nothing pending is a no-op. Re-run by hand with:

```sh
gcloud run jobs execute flyway --wait \
  --project "$(terraform -chdir=terraform output -raw project_id)" \
  --region "$(terraform -chdir=terraform output -raw region)"
```

Destroy needs no ordering: deleting the project removes the job, repo and image. A workflow ([#31](https://github.com/castroaj/gcp-cloud-run-big-query-template/issues/31)) should execute this same job rather than run Gradle itself.

Verify: `bq --project_id=$P query --use_legacy_sql=false 'SELECT version, success FROM telecom_analytics.flyway_schema_history'` lists `1` and `true`.

## Key file vs ADC

Flyway can authenticate two ways. Both were tested in the Flyway spike (#11) against `flyway_spike` with Google's Maven-published JDBC driver.

### What ADC is

Application Default Credentials (ADC) is Google's standard way for a client library to find credentials without being handed a file. It checks, in order, an environment variable pointing at a key file, the file written by `gcloud auth application-default login`, and the service account attached to the runtime on GCP. In the JDBC URL this is `OAuthType=3` with no `OAuthPvtKeyPath`. On a laptop it runs as your own Google user, not as `flyway`.

### Why it was tested

The documented Flyway path is a service-account key file (#3). That is a long-lived secret on disk that has to be created, kept out of git and cleaned up. ADC would remove it, but nothing confirmed that Google's driver, driven through Flyway, honours it. The decision on how Flyway authenticates (#12) needs to know which options actually work.

### What was verified

| | Key file (`OAuthType=0`) | ADC (`OAuthType=3`) |
|---|---|---|
| Connect and read `flyway_schema_history` (`flywayInfo`) | Yes | Yes |
| `flywayMigrate` | Yes | Not run |
| `flywayBaseline` against a hand-made table, then a `V2` migration | Yes | Not run |
| Proves the `flyway` account's own grants are sufficient | Yes | No: ADC runs as you, with broader rights |

The ADC run authenticated as the user, read the history table and printed baseline `V1` and `V2` from the earlier key-based migration, so the driver, credential discovery and connection work end to end. `migrate` and `baseline` share the same JDBC connection, so they should work over ADC, but that was not run. Only the key-file run shows that project `jobUser` plus dataset `dataEditor` is enough for the `flyway` account.

### Which to use

- ADC is simpler for day-to-day local work: no key to create, store or leak.
- The key file is the check that the least-privilege account works, and the only option for a non-interactive runner.
- Suggested for the prototype: ADC locally, key file as the fallback. The decision itself belongs to #12.

### ADC setup

```sh
gcloud auth application-default login
gcloud auth application-default set-quota-project $P
gradle --stop
```

Set the quota project to the current project. ADC remembers the one it had when it was created, and if that project was deleted every call fails with `PERMISSION_DENIED ... Project ... has been deleted`. Stop any running Gradle daemon afterwards: it caches the old quota project, so a retry still fails until it restarts. `GOOGLE_CLOUD_QUOTA_PROJECT` did not override the stored value in testing. `gcloud config get project` may also still point at a deleted project; fix it with `gcloud config set project $P`.

## Cleanup

```sh
gcloud iam service-accounts keys list --iam-account="flyway@$P.iam.gserviceaccount.com"
gcloud iam service-accounts keys delete <KEY_ID> --iam-account="flyway@$P.iam.gserviceaccount.com"
rm -f .secrets/flyway-key.json
bq --project_id=$P rm -r -d flyway_spike   # scratch dataset, if created
```

`terraform destroy` removes the account and the project, which invalidates every key.

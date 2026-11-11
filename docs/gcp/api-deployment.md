# API deployment and teardown (Cloud Run)

Specification for deploying the Spring Boot API in `api/` to Cloud Run and removing it again. Strictly non-production prototype: no authentication hardening, CI/CD or multiple environments. Terms follow [GLOSSARY.md](../../GLOSSARY.md); the API contract is in [spec.md](../spec.md#api-6-8-4).

## Summary

| Item | Value |
|---|---|
| Source | `api/` (Spring Boot 3.5, Java 21, single-module Gradle, wrapper committed) |
| Deploy method | `gcloud run deploy --source .` (Google Cloud buildpacks, no Dockerfile) |
| Service name | `calls-api` |
| Region | `us-east4` (Terraform output `region`) |
| Runs as | service account `calls-api` (Terraform output `calls_api_service_account_email`) |
| Access | Private (`--no-allow-unauthenticated`); callers present an identity token |
| Health endpoint | `GET /health` from Spring Boot Actuator, returns `{"status":"UP"}` |
| Runtime pin | `api/project.toml` sets `GOOGLE_RUNTIME_VERSION=21` |

## What the deploy creates

Terraform does not manage any of these; they appear on the first `gcloud run deploy --source`:

| Resource | Name | Created by |
|---|---|---|
| Cloud Run service | `calls-api` | the deploy |
| Artifact Registry repo (Docker) | `cloud-run-source-deploy` in `us-east4` | the first source deploy |
| Source upload bucket | `run-sources-<project_id>-us-east4` | the first source deploy |
| Cloud Build build per deploy | region `us-east4` | each deploy |

## Prerequisites

1. `terraform apply` is done ([terraform.md](terraform.md)). It enables the Cloud Run, Artifact Registry and Cloud Build APIs and creates the `calls-api` service account with its BigQuery roles.
2. `gcloud` is logged in as a user who can deploy to the project (the human who ran `terraform apply` is the project owner, so this holds).
3. Run the commands from `api/`. No local JDK is needed for the deploy itself; the build runs in Cloud Build. A local JDK 21 is only needed for `./gradlew test` and `bootRun`.
4. The gcloud default project may be a different project, so every command below passes `--project` explicitly.

## Deploy

```sh
cd api
PROJECT=$(terraform -chdir=../terraform output -raw project_id)
REGION=$(terraform -chdir=../terraform output -raw region)
SA=$(terraform -chdir=../terraform output -raw calls_api_service_account_email)

gcloud run deploy calls-api \
  --source . \
  --project "$PROJECT" \
  --region "$REGION" \
  --service-account "$SA" \
  --no-allow-unauthenticated \
  --quiet
```

What happens:

1. gcloud uploads the `api/` directory (respecting `.gitignore`) to the `run-sources-...` bucket.
2. Cloud Build runs the Java buildpack: installs the JDK named by `project.toml` (21), runs `./gradlew clean assemble -x test`, and builds an image with a generated entrypoint that runs the `bootJar`.
3. The image is pushed to `cloud-run-source-deploy` and a new Cloud Run revision takes 100% of traffic.

The first deploy takes several minutes (it creates the repository); later ones are faster. Re-running the same command is the redeploy procedure: every run builds a new revision from the current working tree.

## Configuration notes

- **Port and address:** nothing is configured. Spring's defaults (port 8080, all interfaces) match Cloud Run's default `PORT=8080`. If the container port is ever changed, set `server.port: ${PORT}` in `api/src/main/resources/application.yaml`.
- **Health:** `management.endpoints.web.base-path: /` and `exposure.include: health` in `application.yaml` put Actuator's health at `/health` and expose nothing else.
- **Java version:** the Gradle toolchain (`languageVersion = 21`) picks the compile JDK, and `project.toml` picks the buildpack's runtime JDK. Without `project.toml` the buildpack installs JDK 25, Gradle cannot find a Java 21 toolchain, and the build fails. Keep both.
- **BigQuery access:** the service uses Application Default Credentials from the `calls-api` service account; no key file or secret is deployed.

## Verify

```sh
URL=$(gcloud run services describe calls-api --project "$PROJECT" --region "$REGION" --format='value(status.url)')

# With an identity token: 200 {"status":"UP"}
curl -s -w ' %{http_code}\n' -H "Authorization: Bearer $(gcloud auth print-identity-token)" "$URL/health"

# Without one: 403 (the service is private)
curl -s -o /dev/null -w '%{http_code}\n' "$URL/health"

# Runs as the dedicated service account
gcloud run services describe calls-api --project "$PROJECT" --region "$REGION" \
  --format='value(spec.template.spec.serviceAccountName)'
```

Alternative to tokens: `gcloud run services proxy calls-api --project "$PROJECT" --region "$REGION"` serves the service on `localhost:8080` with credentials attached.

## Run locally

```sh
cd api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test bootRun
curl localhost:8080/health
```

BigQuery calls (later tickets) need ADC with a quota project: `gcloud auth application-default set-quota-project "$PROJECT"`.

## Teardown

Pick the scope. All commands use the `PROJECT` and `REGION` variables from the deploy step.

### A. Remove only the service (keep the project)

```sh
gcloud run services delete calls-api --project "$PROJECT" --region "$REGION" --quiet
```

This stops the service and its URL. It leaves build artifacts behind, which cost a little storage. To remove those as well:

```sh
# Built images
gcloud artifacts repositories delete cloud-run-source-deploy \
  --project "$PROJECT" --location "$REGION" --quiet

# Uploaded sources
gcloud storage rm -r "gs://run-sources-${PROJECT}-${REGION}"
```

A later `gcloud run deploy --source` recreates both.

### B. Remove everything (the normal end of the prototype)

```sh
terraform -chdir=terraform destroy
```

Deleting the project removes the Cloud Run service, Artifact Registry repo, source bucket, build history, dataset and bucket in one pass. The Cloud Run service is not in Terraform state, so destroy needs no prior cleanup. The project stays recoverable for about 30 days (`gcloud projects undelete <project_id>`). Local cleanup after destroy is in [terraform.md](terraform.md#teardown).

If the Terraform state is lost: `gcloud projects delete "$PROJECT"`.

### Check nothing is left

```sh
gcloud run services list --project "$PROJECT" --region "$REGION"
gcloud artifacts repositories list --project "$PROJECT"
```

After scope B the project itself is gone and these commands fail with a not-found or permission error, which is the expected result.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Build fails with `Cannot find a Java installation ... languageVersion=21` | `project.toml` is missing or not in `api/`; the buildpack used JDK 25. Run the deploy from `api/` and confirm `project.toml` is committed. |
| Build logs | `gcloud builds list --project "$PROJECT" --region "$REGION" --limit 1`, then `gcloud builds log <id> --project "$PROJECT" --region "$REGION"`. (`--region global` finds nothing: builds run in the service region.) |
| `403` on `/health` with a token | The caller lacks `roles/run.invoker`, or the token is for another account. The project owner has it implicitly. |
| `403` without a token | Expected: the service is private. |
| Container fails to start | Check `gcloud run services logs read calls-api --project "$PROJECT" --region "$REGION"`; usually a missing or invalid setting in `application.yaml`. |
| Wrong project is deployed to | `--project` was omitted and the gcloud default project was used. Always pass it. |

## Related follow-ups

- [#10](https://github.com/castroaj/gcp-cloud-run-big-query-template/issues/10): replace the buildpack with a maintained Dockerfile (one `FROM eclipse-temurin:21-jre` line pins the runtime and `project.toml` goes away).
- [#9](https://github.com/castroaj/gcp-cloud-run-big-query-template/issues/9): alternative Java frameworks.

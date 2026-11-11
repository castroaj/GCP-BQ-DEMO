# API deployment and teardown (Cloud Run)

Specification for deploying the Spring Boot API in `api/` to Cloud Run and removing it again. Strictly non-production prototype: no authentication hardening, CI/CD or multiple environments. Terms follow [GLOSSARY.md](../../GLOSSARY.md); the API contract is in [spec.md](../spec.md#api-6-8-4).

## Summary

| Item | Value |
|---|---|
| Source | `api/` (Spring Boot 3.5, Java 21, single-module Gradle, wrapper committed) |
| Deploy method | `terraform apply` (recommended): builds `api/Dockerfile` in Cloud Build and creates the service. A manual `gcloud run deploy --source` stays as a quick path |
| Service name | `calls-api` |
| Region | `us-east4` (Terraform output `region`) |
| Runs as | service account `calls-api` (Terraform output `calls_api_service_account_email`) |
| Access | Private (no unauthenticated access); callers present an identity token |
| URL | Terraform output `calls_api_url` |
| Health endpoint | `GET /health` from Spring Boot Actuator, returns `{"status":"UP"}` |
| Runtime | `api/Dockerfile`: `eclipse-temurin:21-jdk` builds the `bootJar`, `eclipse-temurin:21-jre` runs it |

## Deploy with Terraform (recommended)

```sh
cd terraform
terraform apply
```

What Terraform manages for the API:

| Resource | Name |
|---|---|
| Artifact Registry repo (Docker) | `calls-api` in `us-east4` |
| Image | `calls-api:<hash>` built by `gcloud builds submit`, then pinned by digest |
| Cloud Run service | `calls-api`, private, runs as `calls-api`, env `DATASET` and `DATASET_LOCATION` |

- The tag is a hash of the Dockerfile, the Gradle files and everything under `api/src/`. Any change gives a new tag, a rebuild and, through the new digest, a new Cloud Run revision. Local `build/`, `.gradle/` and `bin/` are not hashed.
- The service is created after the Flyway migration, so a fresh apply never serves traffic without the `calls` table.
- The build retries for about 5 minutes while a new project's permissions propagate.
- **Redeploy** is `terraform apply` again. No local JDK is needed; the build runs in Cloud Build. A local JDK 21 is only needed for `./gradlew test` and `bootRun`.
- `gcloud` must be logged in (`gcloud auth login`); the build and digest steps shell out to it.

## Manual deploy (quick path)

For fast iteration without a Terraform run. It is not recommended as the normal path: the revision it creates is unknown to Terraform, so **the next `terraform apply` reverts the service to the image Terraform built**. It uses `api/Dockerfile` automatically and pushes to the `cloud-run-source-deploy` repo that the first source deploy creates (not Terraform's), plus a `run-sources-<project_id>-us-east4` source bucket.

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

The gcloud default project may be a different project, so every command passes `--project` explicitly.

## Configuration notes

- **Port and address:** nothing is configured. Spring's defaults (port 8080, all interfaces) match Cloud Run's default `PORT=8080`. If the container port is ever changed, set `server.port: ${PORT}` in `api/src/main/resources/application.yaml`.
- **Health:** `management.endpoints.web.base-path: /` and `exposure.include: health` in `application.yaml` put Actuator's health at `/health` and expose nothing else.
- **Java version:** the Gradle toolchain (`languageVersion = 21`) picks the compile JDK and the Dockerfile's base images pick the runtime. Keep them in step.
- **BigQuery access:** the service uses Application Default Credentials from the `calls-api` service account; no key file or secret is deployed.

## Verify

```sh
PROJECT=$(terraform -chdir=terraform output -raw project_id)
REGION=$(terraform -chdir=terraform output -raw region)
URL=$(terraform -chdir=terraform output -raw calls_api_url)

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

### A. Remove everything (the normal end of the prototype)

```sh
terraform -chdir=terraform destroy
```

Deleting the project removes the Cloud Run service, the Artifact Registry repos and images, build history, dataset and bucket in one pass; the service is in Terraform state, so no prior cleanup is needed. The project stays recoverable for about 30 days (`gcloud projects undelete <project_id>`). Local cleanup after destroy is in [terraform.md](terraform.md#teardown).

If the Terraform state is lost: `gcloud projects delete "$PROJECT"`.

### B. Remove only a manually deployed leftover

If you used the manual path, its extras are outside Terraform. They go with the project in scope A; to remove them alone:

```sh
gcloud artifacts repositories delete cloud-run-source-deploy \
  --project "$PROJECT" --location "$REGION" --quiet
gcloud storage rm -r "gs://run-sources-${PROJECT}-${REGION}"
```

Do not delete the `calls-api` service by hand while it is in Terraform state; the next apply would recreate it. Use `terraform destroy -target=google_cloud_run_v2_service.calls_api` instead.

### Check nothing is left

```sh
gcloud run services list --project "$PROJECT" --region "$REGION"
gcloud artifacts repositories list --project "$PROJECT"
```

After scope A the project itself is gone and these commands fail with a not-found or permission error, which is the expected result.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Build fails with `Cannot find a Java installation ... languageVersion=21` | The base image is not a Java 21 JDK. Check the `FROM` lines in `api/Dockerfile`. |
| Terraform build step fails after retries | Read the build log below, or check that `gcloud auth login` is current. |
| Build logs | `gcloud builds list --project "$PROJECT" --region "$REGION" --limit 1`, then `gcloud builds log <id> --project "$PROJECT" --region "$REGION"`. (`--region global` finds nothing: builds run in the service region.) |
| `403` on `/health` with a token | The caller lacks `roles/run.invoker`, or the token is for another account. The project owner has it implicitly. |
| `403` without a token | Expected: the service is private. |
| Container fails to start | Check `gcloud run services logs read calls-api --project "$PROJECT" --region "$REGION"`; usually a missing or invalid setting in `application.yaml`. |
| Wrong project is deployed to (manual path) | `--project` was omitted and the gcloud default project was used. Always pass it. |

## Related follow-ups

- [#30](https://github.com/castroaj/gcp-cloud-run-big-query-template/issues/30): deploy from GitHub Actions; revisit now that Terraform owns the service.
- [#9](https://github.com/castroaj/gcp-cloud-run-big-query-template/issues/9): alternative Java frameworks.

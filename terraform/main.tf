# No provider-level `project`: the project is created by this config, so each
# resource sets its own project explicitly.
provider "google" {
  region = var.region
}

# Deleted project ids can never be reused, so a fixed id would block
# destroy-and-recreate. A random suffix gives every apply a fresh id.
resource "random_id" "project_suffix" {
  byte_length = 4
}

locals {
  project_id = "${var.project_id_prefix}-${random_id.project_suffix.hex}"

  # BigQuery for the data, Cloud Run for the API; Artifact Registry and Cloud
  # Build hold and build the Flyway and API images.
  apis = [
    "bigquery.googleapis.com",
    "run.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
  ]

  # Everything that goes into the Flyway image. A change to any of these gives a new
  # tag, rebuilds the image and, through its digest, re-runs the migration job.
  flyway_dir = "${path.module}/../flyway"
  flyway_files = concat(
    ["Dockerfile", "build.gradle", "settings.gradle", "gradlew", "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar"],
    [for f in sort(fileset("${path.module}/../flyway/sql", "*")) : "sql/${f}"],
  )
  flyway_hash  = substr(sha256(join(",", [for f in local.flyway_files : "${f}:${filesha256("${local.flyway_dir}/${f}")}"])), 0, 12)
  flyway_image = "${var.region}-docker.pkg.dev/${local.project_id}/${var.flyway_repository_id}/flyway"

  # Everything that goes into the API image; same idea as the Flyway hash. Local build
  # output (build/, .gradle/, bin/) is not listed, so it never forces a rebuild.
  api_dir = "${path.module}/../api"
  api_files = concat(
    ["Dockerfile", "build.gradle", "settings.gradle", "gradlew", "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar"],
    [for f in sort(fileset("${path.module}/../api/src", "**")) : "src/${f}"],
  )
  api_hash  = substr(sha256(join(",", [for f in local.api_files : "${f}:${filesha256("${local.api_dir}/${f}")}"])), 0, 12)
  api_image = "${var.region}-docker.pkg.dev/${local.project_id}/${var.api_repository_id}/calls-api"
}

resource "google_project" "demo" {
  name            = var.project_name
  project_id      = local.project_id
  billing_account = var.billing_account_id
  # Skip the default VPC; nothing here needs it.
  auto_create_network = false
  # The default (PREVENT) blocks `terraform destroy` from deleting the project.
  deletion_policy = "DELETE"
}

resource "google_project_service" "apis" {
  for_each = toset(local.apis)

  project = google_project.demo.project_id
  service = each.value
  # The whole project is deleted on destroy, so per-service disabling is noise
  # and can fail on dependencies.
  disable_on_destroy = false
}

# Left empty on purpose: the `calls` table is owned by Flyway (V1), so
# Terraform managing it would cause drift.
resource "google_bigquery_dataset" "telecom" {
  project    = google_project.demo.project_id
  dataset_id = var.dataset_id
  location   = var.dataset_location
  # Without this, destroy fails once Flyway has created the calls table.
  delete_contents_on_destroy = true

  # The API must be enabled before the dataset can be created.
  depends_on = [google_project_service.apis]
}

# Staging area for Parquet loads into BigQuery.
resource "google_storage_bucket" "loads" {
  project = google_project.demo.project_id
  # Bucket names are global; the unique project id keeps this one unique.
  name                        = "${google_project.demo.project_id}-loads"
  location                    = var.region
  uniform_bucket_level_access = true
  # Lets destroy remove the bucket even when it still holds uploaded files.
  force_destroy = true

  depends_on = [google_project_service.apis]
}

# Dedicated identity for the Cloud Run API instead of the default compute
# service account, so its access stays minimal.
resource "google_service_account" "calls_api" {
  project      = google_project.demo.project_id
  account_id   = "calls-api"
  display_name = "Calls API (Cloud Run)"
}

# Separate from calls-api because it needs write access. Its key is created
# outside Terraform so the secret stays out of state (see docs/gcp/terraform.md).
resource "google_service_account" "flyway" {
  project      = google_project.demo.project_id
  account_id   = "flyway"
  display_name = "Flyway migrations"
}

# Both accounts need jobUser at project level: BigQuery query jobs run in the
# project, and dataset roles alone don't allow creating them.
resource "google_project_iam_member" "calls_api_job_user" {
  project = google_project.demo.project_id
  role    = "roles/bigquery.jobUser"
  member  = "serviceAccount:${google_service_account.calls_api.email}"
}

resource "google_project_iam_member" "flyway_job_user" {
  project = google_project.demo.project_id
  role    = "roles/bigquery.jobUser"
  member  = "serviceAccount:${google_service_account.flyway.email}"
}

# Read-only and scoped to this dataset, not the project (least privilege).
resource "google_bigquery_dataset_iam_member" "calls_api_viewer" {
  project    = google_project.demo.project_id
  dataset_id = google_bigquery_dataset.telecom.dataset_id
  role       = "roles/bigquery.dataViewer"
  member     = "serviceAccount:${google_service_account.calls_api.email}"
}

# Flyway creates and alters tables, so it needs write access, but only here.
resource "google_bigquery_dataset_iam_member" "flyway_editor" {
  project    = google_project.demo.project_id
  dataset_id = google_bigquery_dataset.telecom.dataset_id
  role       = "roles/bigquery.dataEditor"
  member     = "serviceAccount:${google_service_account.flyway.email}"
}

# Docker repo for the Flyway image. Not the `cloud-run-source-deploy` repo that
# `gcloud run deploy --source` creates for the API: Terraform owns this one.
resource "google_artifact_registry_repository" "flyway" {
  project       = google_project.demo.project_id
  location      = var.region
  repository_id = var.flyway_repository_id
  format        = "DOCKER"

  depends_on = [google_project_service.apis]
}

# The job pulls its image as the flyway account, so it needs read access here only.
resource "google_artifact_registry_repository_iam_member" "flyway_reader" {
  project    = google_project.demo.project_id
  location   = google_artifact_registry_repository.flyway.location
  repository = google_artifact_registry_repository.flyway.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.flyway.email}"
}

# Builds the image in Cloud Build (no local Docker) and tags it with the content hash.
# Destroy needs no cleanup: deleting the project removes the repo and the image.
resource "terraform_data" "flyway_build" {
  triggers_replace = [local.flyway_hash, local.project_id]

  provisioner "local-exec" {
    # A brand-new project's owner grant and API enablement take a few minutes to
    # propagate, so the first attempts can fail with PERMISSION_DENIED. Retry for ~5 min.
    command = <<-EOT
      for i in 1 2 3 4 5 6 7 8 9 10; do
        gcloud builds submit ${local.flyway_dir} --project ${google_project.demo.project_id} --region ${var.region} --tag ${local.flyway_image}:${local.flyway_hash} --quiet && exit 0
        echo "build attempt $i failed, retrying in 30s"; sleep 30
      done
      exit 1
    EOT
  }

  depends_on = [google_artifact_registry_repository.flyway]
}

# Resolves the tag to an immutable digest so the job pins the exact image. Deferred to
# apply when the build step has changes, and a no-op on later plans.
data "external" "flyway_digest" {
  program = [
    "sh", "-c",
    "printf '{\"digest\":\"%s\"}' \"$(gcloud artifacts docker images describe ${local.flyway_image}:${local.flyway_hash} --format='value(image_summary.digest)')\"",
  ]

  depends_on = [terraform_data.flyway_build]
}

resource "google_cloud_run_v2_job" "flyway" {
  project  = google_project.demo.project_id
  location = var.region
  name     = "flyway"
  # Lets destroy and replace work; the project is thrown away anyway.
  deletion_protection = false

  template {
    template {
      service_account = google_service_account.flyway.email
      # A failed migration should be looked at, not retried blindly.
      max_retries = 0

      containers {
        image = "${local.flyway_image}@${data.external.flyway_digest.result.digest}"

        env {
          name  = "PROJECT"
          value = google_project.demo.project_id
        }
        env {
          name  = "DATASET"
          value = google_bigquery_dataset.telecom.dataset_id
        }

        # Gradle plus Flyway on a JVM does not fit the 512Mi default.
        resources {
          limits = {
            cpu    = "2"
            memory = "2Gi"
          }
        }
      }
    }
  }

  depends_on = [
    google_project_service.apis,
    google_artifact_registry_repository_iam_member.flyway_reader,
  ]
}

# Runs the job once the dataset and the flyway grants exist, and again whenever the image
# digest changes, i.e. when a migration file changes. A re-run with nothing pending is a
# no-op, so this owns the first migration and later ones (#31 can call the same job).
resource "terraform_data" "flyway_migrate" {
  triggers_replace = [data.external.flyway_digest.result.digest]

  provisioner "local-exec" {
    command = "gcloud run jobs execute ${google_cloud_run_v2_job.flyway.name} --project ${google_project.demo.project_id} --region ${var.region} --wait"
  }

  depends_on = [
    google_cloud_run_v2_job.flyway,
    google_bigquery_dataset.telecom,
    google_bigquery_dataset_iam_member.flyway_editor,
    google_project_iam_member.flyway_job_user,
  ]
}

# Docker repo for the API image, separate from the Flyway one so each service account
# only reads its own. Not `cloud-run-source-deploy`, which `gcloud run deploy --source`
# creates and Terraform does not own.
resource "google_artifact_registry_repository" "api" {
  project       = google_project.demo.project_id
  location      = var.region
  repository_id = var.api_repository_id
  format        = "DOCKER"

  depends_on = [google_project_service.apis]
}

# The service pulls its image as the calls-api account, so it needs read access here only.
resource "google_artifact_registry_repository_iam_member" "calls_api_reader" {
  project    = google_project.demo.project_id
  location   = google_artifact_registry_repository.api.location
  repository = google_artifact_registry_repository.api.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.calls_api.email}"
}

# Builds the API image in Cloud Build and tags it with the content hash.
resource "terraform_data" "api_build" {
  triggers_replace = [local.api_hash, local.project_id]

  provisioner "local-exec" {
    # Same new-project permission propagation retry as the Flyway build.
    command = <<-EOT
      for i in 1 2 3 4 5 6 7 8 9 10; do
        gcloud builds submit ${local.api_dir} --project ${google_project.demo.project_id} --region ${var.region} --tag ${local.api_image}:${local.api_hash} --quiet && exit 0
        echo "build attempt $i failed, retrying in 30s"; sleep 30
      done
      exit 1
    EOT
  }

  depends_on = [google_artifact_registry_repository.api]
}

# Resolves the tag to an immutable digest so the service pins the exact image.
data "external" "api_digest" {
  program = [
    "sh", "-c",
    "printf '{\"digest\":\"%s\"}' \"$(gcloud artifacts docker images describe ${local.api_image}:${local.api_hash} --format='value(image_summary.digest)')\"",
  ]

  depends_on = [terraform_data.api_build]
}

# Private: no allUsers invoker grant, so callers present an identity token. The project
# owner can invoke it implicitly. Every code change ships through `terraform apply`.
resource "google_cloud_run_v2_service" "calls_api" {
  project  = google_project.demo.project_id
  location = var.region
  name     = "calls-api"
  # Lets destroy and replace work; the project is thrown away anyway.
  deletion_protection = false

  template {
    service_account = google_service_account.calls_api.email

    containers {
      image = "${local.api_image}@${data.external.api_digest.result.digest}"

      env {
        name  = "DATASET"
        value = google_bigquery_dataset.telecom.dataset_id
      }
      env {
        name  = "DATASET_LOCATION"
        value = google_bigquery_dataset.telecom.location
      }
    }
  }

  # After the migration, so a fresh apply never serves traffic without the calls table.
  depends_on = [
    google_project_service.apis,
    google_artifact_registry_repository_iam_member.calls_api_reader,
    terraform_data.flyway_migrate,
  ]
}

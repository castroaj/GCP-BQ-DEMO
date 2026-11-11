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
  # Build are needed by `gcloud run deploy --source`.
  apis = [
    "bigquery.googleapis.com",
    "run.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
  ]
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

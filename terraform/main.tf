resource "random_id" "project_suffix" {
  byte_length = 4
}

locals {
  project_id = "${var.project_id_prefix}-${random_id.project_suffix.hex}"
  apis = [
    "bigquery.googleapis.com",
    "run.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
  ]
}

resource "google_project" "demo" {
  name                = var.project_name
  project_id          = local.project_id
  billing_account     = var.billing_account_id
  auto_create_network = false
  # Lets `terraform destroy` delete the project (default PREVENT blocks it).
  deletion_policy = "DELETE"
}

resource "google_project_service" "apis" {
  for_each = toset(local.apis)

  project = google_project.demo.project_id
  service = each.value
  # Don't disable services separately on destroy; the project deletion handles it.
  disable_on_destroy = false
}

resource "google_bigquery_dataset" "telecom" {
  project    = google_project.demo.project_id
  dataset_id = var.dataset_id
  location   = var.dataset_location
  # Lets destroy drop the dataset even when Flyway has created the calls table.
  delete_contents_on_destroy = true

  depends_on = [google_project_service.apis]
}

resource "google_storage_bucket" "loads" {
  project                     = google_project.demo.project_id
  name                        = "${google_project.demo.project_id}-loads"
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = true

  depends_on = [google_project_service.apis]
}

resource "google_service_account" "calls_api" {
  project      = google_project.demo.project_id
  account_id   = "calls-api"
  display_name = "Calls API (Cloud Run)"
}

resource "google_service_account" "flyway" {
  project      = google_project.demo.project_id
  account_id   = "flyway"
  display_name = "Flyway migrations"
}

# Run query jobs.
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

# calls-api reads the dataset only.
resource "google_bigquery_dataset_iam_member" "calls_api_viewer" {
  project    = google_project.demo.project_id
  dataset_id = google_bigquery_dataset.telecom.dataset_id
  role       = "roles/bigquery.dataViewer"
  member     = "serviceAccount:${google_service_account.calls_api.email}"
}

# Flyway owns schema changes on the dataset.
resource "google_bigquery_dataset_iam_member" "flyway_editor" {
  project    = google_project.demo.project_id
  dataset_id = google_bigquery_dataset.telecom.dataset_id
  role       = "roles/bigquery.dataEditor"
  member     = "serviceAccount:${google_service_account.flyway.email}"
}

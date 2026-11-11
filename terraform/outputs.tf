# Outputs exist so deploy commands can read values with
# `terraform -chdir=terraform output -raw <name>` instead of a wrapper script.

# The id is generated, so it can't be hard-coded in gcloud commands.
output "project_id" {
  description = "Generated project id."
  value       = google_project.demo.project_id
}

output "project_number" {
  value = google_project.demo.number
}

output "region" {
  value = var.region
}

output "dataset_id" {
  value = google_bigquery_dataset.telecom.dataset_id
}

output "bucket_name" {
  description = "GCS bucket for Parquet loads."
  value       = google_storage_bucket.loads.name
}

# Passed to `gcloud run deploy --service-account`.
output "calls_api_service_account_email" {
  value = google_service_account.calls_api.email
}

# Used to create the Flyway key with gcloud.
output "flyway_service_account_email" {
  value = google_service_account.flyway.email
}

# For `gcloud run jobs execute` after adding a migration by hand.
output "flyway_job_name" {
  value = google_cloud_run_v2_job.flyway.name
}

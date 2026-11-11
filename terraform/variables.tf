# Required, and a variable (not hard-coded) so the account id stays out of git;
# supply it via the gitignored terraform.tfvars.
variable "billing_account_id" {
  description = "Open billing account id to link to the project (gcloud billing accounts list)."
  type        = string
}

variable "project_id_prefix" {
  description = "Prefix for the project id. A random suffix is appended because deleted project ids are never reusable. Max 21 chars so prefix + '-' + 8 hex chars fits in 30."
  type        = string
  default     = "telecom-demo"

  # Fail at plan time instead of at the GCP API with a less clear error.
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{3,20}$", var.project_id_prefix))
    error_message = "Must start with a lowercase letter, use only lowercase letters, digits and hyphens, and be 4-21 characters."
  }
}

variable "project_name" {
  description = "Project display name."
  type        = string
  default     = "telecom analytics demo"
}

# Matches the Cloud Run region used by the manual setup docs.
variable "region" {
  description = "Region for Cloud Run and the GCS bucket."
  type        = string
  default     = "us-east4"
}

variable "dataset_id" {
  description = "BigQuery dataset id."
  type        = string
  default     = "telecom_analytics"
}

# US multi-region: the dataset location is immutable, so it is a variable.
variable "dataset_location" {
  description = "BigQuery dataset location."
  type        = string
  default     = "US"
}

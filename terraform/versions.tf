terraform {
  # 1.5+ for the variable validation and `can()` usage in variables.tf.
  required_version = ">= 1.5"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    # Only used to generate the project id suffix.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

# No provider-level `project`: the project is created by this config, so each
# resource sets its own project explicitly.
provider "google" {
  region = var.region
}

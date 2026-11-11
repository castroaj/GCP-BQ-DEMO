terraform {
  # 1.5+ for the variable validation and `can()` usage in variables.tf.
  required_version = ">= 1.5"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    # Reads the Flyway image digest after the build step.
    external = {
      source  = "hashicorp/external"
      version = "~> 2.3"
    }
    # Only used to generate the project id suffix.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

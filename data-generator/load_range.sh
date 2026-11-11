#!/usr/bin/env bash
#
# load_range.sh - generate, upload and load seed Calls for a range of UTC days.
#
# Usage:
#   PROJECT=<id> BUCKET=<bucket> ./load_range.sh <start YYYY-MM-DD> <end YYYY-MM-DD>
#
# For each date in [start, end] (both inclusive) it:
#   1. generates the day's Parquet shards locally (generate.py),
#   2. uploads them to gs://$BUCKET/seed/calls/date=<date>/,
#   3. loads them into $DATASET.$TABLE with a BigQuery LOAD DATA statement,
#   4. deletes the uploaded shards and the local files.
#
# Environment:
#   PROJECT  (required) GCP project id,
#            e.g. $(terraform -chdir=terraform output -raw project_id)
#   BUCKET   (required) GCS bucket for the shards,
#            e.g. $(terraform -chdir=terraform output -raw bucket_name)
#   DATASET  (optional) BigQuery dataset; default: telecom_analytics
#   TABLE    (optional) BigQuery table loaded into; default: calls (recommended)
#   PYTHON   (optional) Python interpreter; default: python3
#            (activate the venv from requirements.txt first)
#
# Behaviour:
#   - Append only, no dedupe: re-loading a date doubles its rows. To redo a date,
#     first run:
#       DELETE FROM telecom_analytics.calls WHERE DATE(offhookDatetime) = '<date>'
#   - Stops on the first failure. A failed day leaves its uploaded shards in the
#     bucket for inspection; local temp files are always removed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# Temp directory of the day currently being processed; removed by cleanup_local.
WORK_DIR=""

# Print an error message to stderr and exit with the given status (default 1).
#   $1 message, $2 exit status
die() {
  echo "error: $1" >&2
  exit "${2:-1}"
}

# Print usage to stderr and exit 2.
usage() {
  echo "usage: PROJECT=<id> BUCKET=<bucket> $0 <start YYYY-MM-DD> <end YYYY-MM-DD>" >&2
  exit 2
}

# Fail fast unless the required environment variables are set, and apply defaults.
check_env() {
  : "${PROJECT:?set PROJECT (terraform -chdir=terraform output -raw project_id)}"
  : "${BUCKET:?set BUCKET (terraform -chdir=terraform output -raw bucket_name)}"
  DATASET="${DATASET:-telecom_analytics}"
  TABLE="${TABLE:-calls}"
  PYTHON="${PYTHON:-python3}"
}

# Fail unless $DATASET.$TABLE already exists. LOAD DATA would otherwise silently
# create a missing table with an inferred schema instead of the Flyway one.
check_table() {
  bq show --project_id="$PROJECT" --format=none "$DATASET.$TABLE" >/dev/null 2>&1 \
    || die "table $DATASET.$TABLE not found in $PROJECT (run the Flyway migration first)"
}

# Validate the command-line dates: both look like YYYY-MM-DD and start <= end.
#   $1 start date, $2 end date
validate_dates() {
  local d
  for d in "$1" "$2"; do
    [[ "$d" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "bad date: $d" 2
  done
  [[ ! "$1" > "$2" ]] || die "start ($1) is after end ($2)" 2
}

# Print the date after the given one (YYYY-MM-DD). Uses Python so it works the
# same on macOS and Linux, and rejects impossible dates such as 2026-02-30.
#   $1 date
next_day() {
  "$PYTHON" -c 'import datetime as d, sys; print(d.date.fromisoformat(sys.argv[1]) + d.timedelta(days=1))' "$1"
}

# Print the GCS folder that holds one day's shards.
#   $1 date
gcs_uri() {
  echo "gs://$BUCKET/seed/calls/date=$1"
}

# Remove the local temp directory, if one exists. Safe to call repeatedly.
cleanup_local() {
  if [[ -n "$WORK_DIR" ]]; then
    rm -rf "$WORK_DIR"
    WORK_DIR=""
  fi
}

# Generate one day's Parquet shards into WORK_DIR.
#   $1 date
generate_day() {
  "$PYTHON" "$SCRIPT_DIR/generate.py" --date "$1" --out "$WORK_DIR"
}

# Upload the shards in WORK_DIR to the day's GCS folder.
#   $1 date
upload_day() {
  gcloud storage cp --project "$PROJECT" "$WORK_DIR"/part-*.parquet "$(gcs_uri "$1")/"
}

# Load the day's uploaded shards into $DATASET.$TABLE (append).
#   $1 date
load_day() {
  local uri
  uri="$(gcs_uri "$1")"
  bq query --project_id="$PROJECT" --use_legacy_sql=false \
    "LOAD DATA INTO $DATASET.$TABLE FROM FILES (format='PARQUET', uris=['$uri/part-*.parquet'])"
}

# Delete the day's uploaded shards from GCS. Only called after a successful load.
#   $1 date
delete_uploaded() {
  gcloud storage rm --project "$PROJECT" --recursive "$(gcs_uri "$1")"
}

# Run the full pipeline for one date. Any failing step aborts the script
# (set -e) before the later steps, so shards are deleted only after a load.
#   $1 date
process_day() {
  echo "== $1"
  WORK_DIR="$(mktemp -d)"
  generate_day "$1"
  upload_day "$1"
  load_day "$1"
  delete_uploaded "$1"
  cleanup_local
}

main() {
  [[ $# -eq 2 ]] || usage
  check_env
  validate_dates "$1" "$2"
  check_table

  trap cleanup_local EXIT

  local date="$1" end="$2"
  while [[ ! "$date" > "$end" ]]; do
    process_day "$date"
    date="$(next_day "$date")"
  done
}

main "$@"

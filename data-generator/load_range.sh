#!/usr/bin/env bash
# Generate, upload and load each UTC day in [start, end] into telecom_analytics.calls.
#
#   PROJECT=... BUCKET=... ./load_range.sh 2026-01-15 2026-01-15
#
# Append only: re-loading a date doubles its rows. To redo a date first run
#   DELETE FROM telecom_analytics.calls WHERE DATE(offhookDatetime) = '<date>'
# Deletes the uploaded shards from GCS after each successful load. Stops on the first failure. Uses $PYTHON (default python3); activate the venv first.
set -euo pipefail

: "${PROJECT:?set PROJECT (terraform -chdir=terraform output -raw project_id)}"
: "${BUCKET:?set BUCKET (terraform -chdir=terraform output -raw bucket_name)}"
DATASET="${DATASET:-telecom_analytics}"
PYTHON="${PYTHON:-python3}"

[[ $# -eq 2 ]] || { echo "usage: $0 <start YYYY-MM-DD> <end YYYY-MM-DD>" >&2; exit 2; }
start="$1"; end="$2"
here="$(cd "$(dirname "$0")" && pwd)"

next_day() { "$PYTHON" -c 'import datetime as d,sys; print(d.date.fromisoformat(sys.argv[1])+d.timedelta(days=1))' "$1"; }

for d in "$start" "$end"; do
  [[ "$d" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "bad date: $d" >&2; exit 2; }
done
[[ ! "$start" > "$end" ]] || { echo "start is after end" >&2; exit 2; }

date="$start"
while [[ ! "$date" > "$end" ]]; do
  out="$(mktemp -d)"
  trap 'rm -rf "$out"' EXIT
  uri="gs://$BUCKET/seed/calls/date=$date"
  echo "== $date"
  "$PYTHON" "$here/generate.py" --date "$date" --out "$out"
  gcloud storage cp --project "$PROJECT" "$out"/part-*.parquet "$uri/"
  bq query --project_id="$PROJECT" --use_legacy_sql=false \
    "LOAD DATA INTO $DATASET.calls FROM FILES (format='PARQUET', uris=['$uri/part-*.parquet'])"
  # Only reached when the load succeeded (set -e); a failed load leaves the shards for inspection.
  gcloud storage rm --project "$PROJECT" --recursive "$uri"
  rm -rf "$out"; trap - EXIT
  date="$(next_day "$date")"
done

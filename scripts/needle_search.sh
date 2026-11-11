#!/usr/bin/env bash
# Submits a needle lookup to the private Calls API, polls until it finishes and prints
# the summary. Usage: scripts/needle_search.sh [telephoneNumber] [YYYY-MM-DD]
# Defaults: the 25-call needle on 2026-01-15. The day must already be loaded
# (data-generator/load_range.sh). Needles: 15550100000 (0), 15550100001 (1),
# 15550100025 (25), 15550101500 (1000, truncated). Needs gcloud, terraform, curl, jq.
set -euo pipefail

NUMBER=${1:-15550100025}
DAY=${2:-2026-01-15}

if date -v+1d >/dev/null 2>&1; then
  NEXT=$(date -j -v+1d -f %Y-%m-%d "$DAY" +%Y-%m-%d)   # macOS
else
  NEXT=$(date -d "$DAY +1 day" +%F)                    # GNU
fi

ROOT=$(cd "$(dirname "$0")/.." && pwd)
URL=$(terraform -chdir="$ROOT/terraform" output -raw calls_api_url)
TOKEN=$(gcloud auth print-identity-token)

JOB_ID=$(curl -sf -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  "$URL/api/v1/query" \
  -d "{\"telephoneNumber\":\"$NUMBER\",\"startDatetime\":\"${DAY}T00:00:00Z\",\"endDatetime\":\"${NEXT}T00:00:00Z\"}" \
  | jq -r .jobId)
echo "submitted $JOB_ID for $NUMBER on $DAY" >&2

while :; do
  RESULT=$(curl -sf -H "Authorization: Bearer $TOKEN" "$URL/api/v1/query/$JOB_ID")
  STATUS=$(jq -r .status <<<"$RESULT")
  case $STATUS in
    DONE) jq '{status, count, truncated}' <<<"$RESULT"; exit 0 ;;
    FAILED) jq . <<<"$RESULT" >&2; exit 1 ;;
  esac
  sleep 2
done

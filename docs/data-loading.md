# Loading seed data

`data-generator/load_range.sh <start> <end>` generates one UTC day of Calls at a time (1M rows each), uploads the Parquet shards to the Terraform bucket, loads them into `telecom_analytics.calls`, then deletes the local files and the uploaded shards. It stops on the first failure, leaving that day's shards in the bucket.

Prerequisites: `terraform apply` and the Flyway migration are done (the `calls` table exists), and `gcloud` and `bq` are authenticated.

```bash
# one-time: Python environment
python3 -m venv data-generator/.venv
. data-generator/.venv/bin/activate
pip install -r data-generator/requirements.txt

# point the script at the Terraform project
export PROJECT=$(terraform -chdir=terraform output -raw project_id)
export BUCKET=$(terraform -chdir=terraform output -raw bucket_name)

# load one day, or a range (dates are UTC, both ends inclusive)
./data-generator/load_range.sh 2026-01-15 2026-01-15
./data-generator/load_range.sh 2026-01-01 2026-01-31
```

- `DATASET` defaults to `telecom_analytics`; `PYTHON` defaults to `python3`.
- Loads are append only. To redo a date, delete it first: `DELETE FROM telecom_analytics.calls WHERE DATE(offhookDatetime) = '2026-01-15'`.
- Phone numbers are digits only, with no leading `+` (for example `15551234567`).
- Test-needle numbers appear a fixed number of times on every day: `15550100000` (0), `15550100001` (1), `15550100025` (25), `15550101500` (1,500).
- Generate files without loading: `python data-generator/generate.py --date 2026-01-15 --out ./out`. The same date and `--seed` give byte-identical files.
- Details: [docs/spec.md](spec.md#seed-data-7).

#!/usr/bin/env python3
"""Generate one UTC day of seed Calls as Snappy Parquet shards.

    generate.py --date 2026-01-15 --out ./out [--rows 1000000] [--seed 42]

Deterministic: the number pool depends only on --seed, the per-day stream on
--seed and --date, so re-running a date gives byte-identical files.
"""
import argparse
import datetime as dt
import hashlib
import sys
from decimal import Decimal
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

POOL_SIZE = 5_000
SHARDS = 10

# Fixed test needles: number -> times it appears (as calling or called) on
# every generated day. They sit in the fictional 555-01xx range, which the
# pool never uses, so the counts are exact. 1500 exceeds the API's 1,000-row
# cap on purpose, to exercise `truncated`.
NEEDLES = {
    "15550100000": 0,
    "15550100001": 1,
    "15550100025": 25,
    "15550101500": 1500,
}

CALL_TYPES = ["voice", "video", "voip"]
DIRECTIONS = ["outbound", "inbound"]
STATUSES = ["completed", "missed", "busy", "failed"]
STATUS_P = [0.80, 0.10, 0.06, 0.04]
CARRIERS = ["Verizon", "AT&T", "T-Mobile", "Sprint", "Vodafone", "Orange"]
COUNTRIES = ["US", "CA", "GB", "DE", "FR", "MX"]
COUNTRY_P = [0.80, 0.06, 0.04, 0.04, 0.03, 0.03]
CITIES = ["New York", "Los Angeles", "Chicago", "Houston", "Toronto",
          "London", "Berlin", "Paris", "Mexico City", "Seattle"]
NETWORKS = ["4G", "5G", "3G", "WiFi"]
CODECS = ["AMR", "AMR-WB", "EVS", "G.711", "Opus"]
NULL_RATES = {  # 5-20% nulls on each filler column
    "callType": 0.05, "direction": 0.08, "callStatus": 0.10,
    "callingCarrier": 0.12, "calledCarrier": 0.15,
    "callingCountryCode": 0.05, "calledCountryCode": 0.07,
    "callingCity": 0.18, "calledCity": 0.20, "cellTowerId": 0.10,
    "networkType": 0.06, "codec": 0.14, "isRoaming": 0.09,
    "isInternational": 0.11, "costUsd": 0.05,
}

SCHEMA = pa.schema([
    pa.field("callId", pa.string(), nullable=False),
    pa.field("calling", pa.string(), nullable=False),
    pa.field("called", pa.string(), nullable=False),
    pa.field("offhookDatetime", pa.timestamp("us", tz="UTC"), nullable=False),
    pa.field("durationSeconds", pa.int64(), nullable=False),
    pa.field("callType", pa.string()),
    pa.field("direction", pa.string()),
    pa.field("callStatus", pa.string()),
    pa.field("callingCarrier", pa.string()),
    pa.field("calledCarrier", pa.string()),
    pa.field("callingCountryCode", pa.string()),
    pa.field("calledCountryCode", pa.string()),
    pa.field("callingCity", pa.string()),
    pa.field("calledCity", pa.string()),
    pa.field("cellTowerId", pa.string()),
    pa.field("networkType", pa.string()),
    pa.field("codec", pa.string()),
    pa.field("isRoaming", pa.bool_()),
    pa.field("isInternational", pa.bool_()),
    pa.field("costUsd", pa.decimal128(38, 9)),
])


def make_pool(seed: int) -> np.ndarray:
    """5,000 distinct E.164 numbers without the leading + (1 and 10 digits), needles excluded."""
    rng = np.random.default_rng([seed, 0])
    pool: set[str] = set()
    while len(pool) < POOL_SIZE:
        area = rng.integers(200, 1000)
        exch = rng.integers(200, 1000)
        line = rng.integers(0, 10_000)
        number = f"1{area}{exch}{line:04d}"
        if number not in NEEDLES:
            pool.add(number)
    return np.array(sorted(pool))


def day_seed(seed: int, date: dt.date) -> int:
    digest = hashlib.sha256(f"{seed}:{date.isoformat()}".encode()).digest()
    return int.from_bytes(digest[:8], "big")


def pick(rng, values, n, p=None):
    return np.asarray(values, dtype=object)[rng.choice(len(values), size=n, p=p)]


def nullify(rng, values, column):
    out = np.asarray(values, dtype=object).copy()
    out[rng.random(len(out)) < NULL_RATES[column]] = None
    return out


def generate_day(date: dt.date, rows: int, seed: int) -> pa.Table:
    pool = make_pool(seed)
    rng = np.random.default_rng(day_seed(seed, date))
    n = rows

    # Skewed activity: a few numbers place and receive most Calls.
    weights = 1.0 / np.arange(1, POOL_SIZE + 1) ** 0.8
    weights /= weights.sum()
    calling_idx = rng.choice(POOL_SIZE, size=n, p=weights)
    called_idx = rng.choice(POOL_SIZE, size=n, p=weights)
    called_idx = np.where(called_idx == calling_idx, (called_idx + 1) % POOL_SIZE, called_idx)
    calling = pool[calling_idx].astype(object)
    called = pool[called_idx].astype(object)

    # Plant the needles on distinct rows, one side each.
    needle_rows = rng.permutation(n)
    cursor = 0
    for number, count in NEEDLES.items():
        for i in needle_rows[cursor:cursor + count]:
            (calling if rng.random() < 0.5 else called)[i] = number
        cursor += count

    day_start = int(dt.datetime(date.year, date.month, date.day, tzinfo=dt.timezone.utc).timestamp()) * 1_000_000
    offhook = np.sort(day_start + rng.integers(0, 86_400 * 1_000_000, size=n))

    raw_status = pick(rng, STATUSES, n, STATUS_P)
    connected = raw_status == "completed"
    duration = np.clip(np.rint(np.exp(np.log(90) + 1.0 * rng.standard_normal(n))), 1, 14_400).astype(np.int64)
    duration = np.where(connected, duration, 0)

    # costUsd from the real duration: per-minute rate, 6 decimal places.
    rate = rng.uniform(0.01, 0.05, size=n)
    cost_units = np.rint(duration / 60.0 * rate * 1e6).astype(np.int64) * 1000  # scale 9
    cost = np.array([Decimal(int(u)).scaleb(-9) for u in cost_units], dtype=object)

    hi = rng.integers(0, 2**63, size=n, dtype=np.uint64)
    lo = rng.integers(0, 2**63, size=n, dtype=np.uint64)
    call_ids = []
    for h, l in zip(hi.tolist(), lo.tolist()):
        v = ((h << 64) | l) & ~(0xF000 << 64) | (0x4000 << 64)  # version 4
        v = (v & ~(0xC << 60)) | (0x8 << 60)                     # RFC 4122 variant
        s = f"{v:032x}"
        call_ids.append(f"{s[:8]}-{s[8:12]}-{s[12:16]}-{s[16:20]}-{s[20:]}")

    country_calling = pick(rng, COUNTRIES, n, COUNTRY_P)
    country_called = pick(rng, COUNTRIES, n, COUNTRY_P)
    flags = rng.random(n)
    columns = {
        "callId": call_ids,
        "calling": calling,
        "called": called,
        "offhookDatetime": pa.array(offhook, type=pa.timestamp("us", tz="UTC")),
        "durationSeconds": duration,
        "callType": nullify(rng, pick(rng, CALL_TYPES, n), "callType"),
        "direction": nullify(rng, pick(rng, DIRECTIONS, n), "direction"),
        "callStatus": nullify(rng, raw_status, "callStatus"),
        "callingCarrier": nullify(rng, pick(rng, CARRIERS, n), "callingCarrier"),
        "calledCarrier": nullify(rng, pick(rng, CARRIERS, n), "calledCarrier"),
        "callingCountryCode": nullify(rng, country_calling, "callingCountryCode"),
        "calledCountryCode": nullify(rng, country_called, "calledCountryCode"),
        "callingCity": nullify(rng, pick(rng, CITIES, n), "callingCity"),
        "calledCity": nullify(rng, pick(rng, CITIES, n), "calledCity"),
        "cellTowerId": nullify(rng, [f"TWR-{x:05d}" for x in rng.integers(0, 2000, size=n)], "cellTowerId"),
        "networkType": nullify(rng, pick(rng, NETWORKS, n), "networkType"),
        "codec": nullify(rng, pick(rng, CODECS, n), "codec"),
        "isRoaming": nullify(rng, flags < 0.08, "isRoaming"),
        "isInternational": nullify(rng, country_calling != country_called, "isInternational"),
        "costUsd": nullify(rng, cost, "costUsd"),
    }
    arrays = [pa.array(columns[f.name], type=f.type) for f in SCHEMA]
    return pa.Table.from_arrays(arrays, schema=SCHEMA)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--date", required=True, type=dt.date.fromisoformat, help="UTC day, YYYY-MM-DD")
    ap.add_argument("--out", required=True, type=Path, help="output directory")
    ap.add_argument("--rows", type=int, default=1_000_000)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    if args.rows < sum(NEEDLES.values()):
        ap.error(f"--rows must be at least {sum(NEEDLES.values())} to plant the needles")

    table = generate_day(args.date, args.rows, args.seed)
    args.out.mkdir(parents=True, exist_ok=True)
    per_shard = -(-table.num_rows // SHARDS)
    for i in range(SHARDS):
        shard = table.slice(i * per_shard, per_shard)
        if shard.num_rows:
            pq.write_table(shard, args.out / f"part-{i:04d}.parquet", compression="snappy")
    print(f"{args.date}: wrote {table.num_rows} rows to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

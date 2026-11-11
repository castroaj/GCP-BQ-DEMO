package com.example.calls.api.query.enums;

/** Where a query job is in its life, as reported by {@code GET /api/v1/query/{jobId}}. */
public enum QueryStatus {
  /** BigQuery has accepted the job but not started it. */
  PENDING,
  /** BigQuery is running the job. */
  RUNNING,
  /** The job finished and its Calls are in the response. */
  DONE,
  /** The job finished with an error, described in the response's {@code error}. */
  FAILED
}

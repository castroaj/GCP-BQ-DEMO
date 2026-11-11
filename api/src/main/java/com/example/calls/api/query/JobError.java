package com.example.calls.api.query;

/**
 * Why a job {@link QueryStatus#FAILED}, shaped like the API's problem details. BigQuery's own
 * message is logged server-side and never exposed.
 *
 * @param status the HTTP status the failure corresponds to
 * @param title short, human-readable title
 * @param detail caller-safe explanation
 * @param code machine-readable error code
 */
public record JobError(int status, String title, String detail, String code) {}

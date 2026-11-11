package com.example.calls.api.query.dto;

/**
 * The {@code 202} body of {@code POST /api/v1/query}.
 *
 * @param jobId the id to poll with {@code GET /api/v1/query/{jobId}}
 */
public record SubmitResponse(String jobId) {}

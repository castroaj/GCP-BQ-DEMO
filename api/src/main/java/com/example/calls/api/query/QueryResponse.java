package com.example.calls.api.query;

import java.util.List;

/**
 * The {@code 200} body of {@code GET /api/v1/query}.
 *
 * @param telephoneNumber the number that was looked up, as requested
 * @param startDatetime start of the range (inclusive), UTC ISO 8601
 * @param endDatetime end of the range (exclusive), UTC ISO 8601
 * @param count number of Calls in {@code calls}
 * @param truncated {@code true} when more Calls matched than the 1,000-row cap returned
 * @param calls the matching Calls, sorted by {@code offhookDatetime} then {@code callId}
 * @see CallsController#query(String, String, String)
 */
public record QueryResponse(
    String telephoneNumber,
    String startDatetime,
    String endDatetime,
    int count,
    boolean truncated,
    List<Call> calls) {}

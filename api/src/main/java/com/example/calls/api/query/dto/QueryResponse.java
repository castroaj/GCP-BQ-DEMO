package com.example.calls.api.query.dto;

import com.example.calls.api.query.model.Call;
import java.util.List;

/**
 * The Calls found by a query job: the result part of {@link JobResponse} when the job is done.
 *
 * @param telephoneNumber the number that was looked up, as requested
 * @param startDatetime start of the range (inclusive), UTC ISO 8601
 * @param endDatetime end of the range (exclusive), UTC ISO 8601
 * @param count number of Calls in {@code calls}
 * @param truncated {@code true} when more Calls matched than the 1,000-row cap returned
 * @param calls the matching Calls, sorted by {@code offhookDatetime} then {@code callId}
 * @see com.example.calls.api.query.CallsQueryService#find(String)
 */
public record QueryResponse(
    String telephoneNumber,
    String startDatetime,
    String endDatetime,
    int count,
    boolean truncated,
    List<Call> calls) {}

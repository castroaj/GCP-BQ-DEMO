package com.example.calls;

import java.util.List;

/** 200 body of GET /api/v1/query. Datetimes are UTC ISO 8601. */
public record QueryResponse(
    String telephoneNumber,
    String startDatetime,
    String endDatetime,
    int count,
    boolean truncated,
    List<Call> calls) {}

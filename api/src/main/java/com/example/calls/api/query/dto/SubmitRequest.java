package com.example.calls.api.query.dto;

import com.example.calls.api.query.model.CallQuery;

/**
 * The JSON body of {@code POST /api/v1/query}. The values are raw strings so that {@link
 * CallQuery#parse} owns validation and the error codes; all three are required.
 *
 * @param telephoneNumber E.164 digits without the leading {@code +}
 * @param startDatetime ISO 8601 with an explicit UTC offset, inclusive start
 * @param endDatetime ISO 8601 with an explicit UTC offset, exclusive end
 */
public record SubmitRequest(String telephoneNumber, String startDatetime, String endDatetime) {}

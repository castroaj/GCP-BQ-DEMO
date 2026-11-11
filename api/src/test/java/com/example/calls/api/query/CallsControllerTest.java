package com.example.calls.api.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.calls.api.ApiException;
import com.google.cloud.bigquery.BigQuery;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Web-layer tests for {@link CallsController}: parameter handling and the HTTP shape of results
 * and errors. The service and the BigQuery client are mocked.
 */
@SpringBootTest(properties = "calls.dataset=telecom_analytics")
@AutoConfigureMockMvc
class CallsControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean CallsQueryService service;
  // Keeps the real client (and ADC) out of the test context.
  @MockitoBean BigQuery bigQuery;

  /**
   * Calls {@code GET /api/v1/query}, leaving out any parameter passed as {@code null}.
   *
   * @param number the {@code telephoneNumber} parameter, or {@code null} to omit it
   * @param start the {@code startDatetime} parameter, or {@code null} to omit it
   * @param end the {@code endDatetime} parameter, or {@code null} to omit it
   * @return the result, for further assertions
   * @throws Exception if the request cannot be performed
   */
  private ResultActions query(String number, String start, String end) throws Exception {
    var req = get("/api/v1/query");
    if (number != null) req.param("telephoneNumber", number);
    if (start != null) req.param("startDatetime", start);
    if (end != null) req.param("endDatetime", end);
    return mvc.perform(req);
  }

  private static final String START = "2026-01-15T00:00:00Z";
  private static final String END = "2026-01-16T00:00:00Z";

  /**
   * Verifies that a valid request returns the service result and passes UTC instants on, even
   * when the request used a {@code +05:00} offset.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void validRequestReturnsServiceResultWithInstantsConvertedToUtc() throws Exception {
    var s = Instant.parse("2026-01-15T00:00:00Z");
    var e = Instant.parse("2026-01-16T00:00:00Z");
    var call = new Call("id-1", "15550100001", "15551234567", "2026-01-15T10:00:00Z", 0,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    when(service.query(new CallQuery("15550100001", s, e)))
        .thenReturn(new QueryResponse("15550100001", "2026-01-15T00:00:00Z", "2026-01-16T00:00:00Z", 1, false, List.of(call)));

    // +05:00 offsets are accepted and normalized.
    query("15550100001", "2026-01-15T05:00:00+05:00", "2026-01-16T05:00:00+05:00")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.calls[0].callId").value("id-1"))
        .andExpect(jsonPath("$.calls[0].callType").value((Object) null));
    verify(service).query(new CallQuery("15550100001", s, e));
  }

  /**
   * Verifies that missing parameters give {@code 400 MISSING_PARAMETER} without calling the service.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void missingParametersAre400() throws Exception {
    query(null, START, END).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    query("15550100001", null, END).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    query("15550100001", START, null).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    verifyNoInteractions(service);
  }

  /**
   * Verifies that malformed numbers and datetimes give {@code 400 INVALID_PARAMETER} without
   * calling the service.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void malformedParametersAre400() throws Exception {
    for (var number : List.of("+15550100001", "abc", "1", "1555010000112345678", "")) {
      query(number, START, END).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
    // No offset, date only, and garbage are all rejected.
    for (var bad : List.of("2026-01-15T00:00:00", "2026-01-15", "yesterday")) {
      query("15550100001", bad, END).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
    verifyNoInteractions(service);
  }

  /**
   * Verifies that a reversed or empty range gives {@code 400 INVALID_RANGE}.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void endNotAfterStartIsInvalidRange() throws Exception {
    query("15550100001", END, START).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    query("15550100001", START, START).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
  }

  /**
   * Verifies that a BigQuery failure gives a {@code 502} problem whose body hides the cause.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void upstreamFailureIs502WithoutDetails() throws Exception {
    when(service.query(any()))
        .thenThrow(new ApiException(ApiException.Code.UPSTREAM_ERROR, "Query failed", new RuntimeException("secret detail")));
    query("15550100001", START, END).andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"))
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(502))
        .andExpect(jsonPath("$.title").value("Upstream error"))
        .andExpect(jsonPath("$.detail").value("Query failed"));
  }

  /**
   * Verifies that an unexpected exception gives a generic {@code 500} problem.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void unexpectedFailureIs500WithoutDetails() throws Exception {
    when(service.query(any())).thenThrow(new IllegalStateException("boom"));
    query("15550100001", START, END).andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.title").value("Internal error"))
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
  }

  /**
   * Verifies that a validation error is an RFC 9457 problem with title, detail and {@code code}.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void validationErrorIsAProblemDetailsBody() throws Exception {
    query(null, START, END).andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Missing parameter"))
        .andExpect(jsonPath("$.detail").value("Missing required parameter: telephoneNumber"))
        .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
  }

  /**
   * Verifies that Spring's own 404 uses the same problem shape.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void unknownPathIsAlsoAProblemDetailsBody() throws Exception {
    mvc.perform(get("/api/v1/nope")).andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(404));
  }
}

package com.example.calls.api.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.MediaType;
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
   * Calls {@code POST /api/v1/query} with a JSON body, leaving out any field passed as {@code
   * null}.
   *
   * @param number the {@code telephoneNumber} field, or {@code null} to omit it
   * @param start the {@code startDatetime} field, or {@code null} to omit it
   * @param end the {@code endDatetime} field, or {@code null} to omit it
   * @return the result, for further assertions
   * @throws Exception if the request cannot be performed
   */
  private ResultActions submit(String number, String start, String end) throws Exception {
    var fields = new java.util.ArrayList<String>();
    if (number != null) fields.add("\"telephoneNumber\":\"" + number + "\"");
    if (start != null) fields.add("\"startDatetime\":\"" + start + "\"");
    if (end != null) fields.add("\"endDatetime\":\"" + end + "\"");
    return mvc.perform(
        post("/api/v1/query")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{" + String.join(",", fields) + "}"));
  }

  private static final String JOB_ID = "calls-query-123e4567-e89b-12d3-a456-426614174000";
  private static final String START = "2026-01-15T00:00:00Z";
  private static final String END = "2026-01-16T00:00:00Z";

  /**
   * Verifies that a valid submission returns {@code 202} with the job id and a {@code Location}
   * header, and passes UTC instants on to the service even when the request used a {@code +05:00}
   * offset.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void validSubmissionIsAcceptedWithJobIdAndInstantsConvertedToUtc() throws Exception {
    var s = Instant.parse("2026-01-15T00:00:00Z");
    var e = Instant.parse("2026-01-16T00:00:00Z");
    when(service.submit(new CallQuery("15550100001", s, e))).thenReturn(JOB_ID);

    // +05:00 offsets are accepted and normalized.
    submit("15550100001", "2026-01-15T05:00:00+05:00", "2026-01-16T05:00:00+05:00")
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/api/v1/query/" + JOB_ID))
        .andExpect(jsonPath("$.jobId").value(JOB_ID));
    verify(service).submit(new CallQuery("15550100001", s, e));
  }

  /**
   * Verifies that a finished job returns {@code DONE} with the Calls and no {@code error}.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void doneJobReturnsTheCalls() throws Exception {
    var call = new Call("id-1", "15550100001", "15551234567", "2026-01-15T10:00:00Z", 0,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    var result = new QueryResponse("15550100001", START, END, 1, false, List.of(call));
    when(service.find(JOB_ID)).thenReturn(JobResponse.done(JOB_ID, result));

    mvc.perform(get("/api/v1/query/" + JOB_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").value(JOB_ID))
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.calls[0].callId").value("id-1"))
        .andExpect(jsonPath("$.calls[0].callType").value((Object) null))
        .andExpect(jsonPath("$.error").doesNotExist());
  }

  /**
   * Verifies that an unfinished job returns only its id and status.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void runningJobReturnsOnlyStatus() throws Exception {
    when(service.find(JOB_ID)).thenReturn(JobResponse.inProgress(JOB_ID, QueryStatus.RUNNING));

    mvc.perform(get("/api/v1/query/" + JOB_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.calls").doesNotExist())
        .andExpect(jsonPath("$.error").doesNotExist());
  }

  /**
   * Verifies that a failed job is {@code 200} with {@code FAILED} and a problem-shaped {@code
   * error}.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void failedJobReturnsErrorBody() throws Exception {
    var error = new JobError(502, "Upstream error", "Query failed", "UPSTREAM_ERROR");
    when(service.find(JOB_ID)).thenReturn(JobResponse.failed(JOB_ID, error));

    mvc.perform(get("/api/v1/query/" + JOB_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.error.code").value("UPSTREAM_ERROR"))
        .andExpect(jsonPath("$.error.detail").value("Query failed"))
        .andExpect(jsonPath("$.calls").doesNotExist());
  }

  /**
   * Verifies that an unknown or malformed job id gives a {@code 404 NOT_FOUND} problem.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void unknownJobIs404() throws Exception {
    when(service.find(any())).thenThrow(new ApiException(ApiException.Code.NOT_FOUND, "No such query job"));

    mvc.perform(get("/api/v1/query/anything"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.detail").value("No such query job"));
  }

  /**
   * Verifies that the old synchronous {@code GET /api/v1/query} no longer exists.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void synchronousGetIsGone() throws Exception {
    mvc.perform(get("/api/v1/query").param("telephoneNumber", "15550100001"))
        .andExpect(status().isMethodNotAllowed());
  }

  /**
   * Verifies that missing fields give {@code 400 MISSING_PARAMETER} without calling the service.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void missingParametersAre400() throws Exception {
    submit(null, START, END).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    submit("15550100001", null, END).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
    submit("15550100001", START, null).andExpect(status().isBadRequest())
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
      submit(number, START, END).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
    // No offset, date only, and garbage are all rejected.
    for (var bad : List.of("2026-01-15T00:00:00", "2026-01-15", "yesterday")) {
      submit("15550100001", bad, END).andExpect(status().isBadRequest())
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
    submit("15550100001", END, START).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    submit("15550100001", START, START).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
  }

  /**
   * Verifies that a BigQuery failure gives a {@code 502} problem whose body hides the cause.
   *
   * @throws Exception if the request cannot be performed
   */
  @Test
  void upstreamFailureIs502WithoutDetails() throws Exception {
    when(service.submit(any()))
        .thenThrow(new ApiException(ApiException.Code.UPSTREAM_ERROR, "Query failed", new RuntimeException("secret detail")));
    submit("15550100001", START, END).andExpect(status().isBadGateway())
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
    when(service.submit(any())).thenThrow(new IllegalStateException("boom"));
    submit("15550100001", START, END).andExpect(status().isInternalServerError())
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
    submit(null, START, END).andExpect(status().isBadRequest())
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

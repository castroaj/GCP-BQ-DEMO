package com.example.calls;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest(properties = "calls.dataset=telecom_analytics")
@AutoConfigureMockMvc
class CallsControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean CallsQueryService service;
  // Keeps the real client (and ADC) out of the test context.
  @MockitoBean BigQuery bigQuery;

  private ResultActions query(String number, String start, String end) throws Exception {
    var req = get("/api/v1/query");
    if (number != null) req.param("telephoneNumber", number);
    if (start != null) req.param("startDatetime", start);
    if (end != null) req.param("endDatetime", end);
    return mvc.perform(req);
  }

  private static final String START = "2026-01-15T00:00:00Z";
  private static final String END = "2026-01-16T00:00:00Z";

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

  @Test
  void missingParametersAre400() throws Exception {
    query(null, START, END).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("MISSING_PARAMETER"));
    query("15550100001", null, END).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("MISSING_PARAMETER"));
    query("15550100001", START, null).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("MISSING_PARAMETER"));
    verifyNoInteractions(service);
  }

  @Test
  void malformedParametersAre400() throws Exception {
    for (var number : List.of("+15550100001", "abc", "1", "1555010000112345678", "")) {
      query(number, START, END).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }
    // No offset, date only, and garbage are all rejected.
    for (var bad : List.of("2026-01-15T00:00:00", "2026-01-15", "yesterday")) {
      query("15550100001", bad, END).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void endNotAfterStartIsInvalidRange() throws Exception {
    query("15550100001", END, START).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_RANGE"));
    query("15550100001", START, START).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("INVALID_RANGE"));
  }

  @Test
  void upstreamFailureIs502WithoutDetails() throws Exception {
    when(service.query(any()))
        .thenThrow(new ApiException(ApiException.Code.UPSTREAM_ERROR, "Query failed", new RuntimeException("secret detail")));
    query("15550100001", START, END).andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("UPSTREAM_ERROR"))
        .andExpect(jsonPath("$.message").value("Query failed"));
  }

  @Test
  void unexpectedFailureIs500WithoutDetails() throws Exception {
    when(service.query(any())).thenThrow(new IllegalStateException("boom"));
    query("15550100001", START, END).andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("Internal error"));
  }
}

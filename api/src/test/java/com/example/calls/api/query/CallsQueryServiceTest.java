package com.example.calls.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.calls.api.ApiException.Code;
import com.example.calls.api.ApiException;
import com.example.calls.api.query.enums.QueryStatus;
import com.example.calls.api.query.model.CallQuery;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatus;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.TableResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link CallsQueryService} against a mocked BigQuery client: job creation and
 * parameterization, job id checking, status mapping, truncation at the cap, column mapping and
 * error translation.
 */
class CallsQueryServiceTest {
  private static final List<String> COLUMNS =
      List.of("callId", "calling", "called", "offhookDatetime", "durationSeconds", "callType",
          "direction", "callStatus", "callingCarrier", "calledCarrier", "callingCountryCode",
          "calledCountryCode", "callingCity", "calledCity", "cellTowerId", "networkType", "codec",
          "isRoaming", "isInternational", "costUsd");
  private static final FieldList SCHEMA =
      FieldList.of(COLUMNS.stream().map(c -> Field.of(c, StandardSQLTypeName.STRING)).toList());

  private static final CallQuery QUERY =
      new CallQuery(
          "15550100025", Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-16T00:00:00Z"));

  private BigQuery bigQuery;
  private CallsQueryService service;

  @BeforeEach
  void setUp() {
    bigQuery = mock(BigQuery.class);
    var options = mock(BigQueryOptions.class);
    when(options.getProjectId()).thenReturn("demo-project");
    when(bigQuery.getOptions()).thenReturn(options);
    service = new CallsQueryService(bigQuery, "telecom_analytics", "US");
  }

  /** A row with all 20 columns; filler columns are null unless overridden. */
  /**
   * Builds a row with all 20 columns; filler columns are {@code NULL} unless overridden.
   *
   * @param callId the {@code callId} value
   * @param overrides column values that replace the defaults, by column name
   * @return the row, addressable by column name
   */
  private static FieldValueList row(String callId, Map<String, String> overrides) {
    var values = new ArrayList<FieldValue>();
    for (var column : COLUMNS) {
      String value =
          switch (column) {
            case "callId" -> callId;
            case "calling" -> "15550100025";
            case "called" -> "15551234567";
            case "offhookDatetime" -> "1768435200.123456";
            case "durationSeconds" -> "90";
            default -> null;
          };
      values.add(FieldValue.of(FieldValue.Attribute.PRIMITIVE, overrides.getOrDefault(column, value)));
    }
    return FieldValueList.of(values, SCHEMA);
  }

  private static final String JOB_ID = "calls-query-123e4567-e89b-12d3-a456-426614174000";

  /**
   * Builds a mocked BigQuery job in a state, carrying the query {@link #QUERY} produces.
   *
   * @param state the job's state
   * @param error the job's error, or {@code null} for none
   * @return the mock
   */
  private Job job(JobStatus.State state, BigQueryError error) {
    var config = service.queryConfig(QUERY);
    var job = mock(Job.class);
    var status = mock(JobStatus.class);
    when(status.getState()).thenReturn(state);
    when(status.getError()).thenReturn(error);
    when(job.getStatus()).thenReturn(status);
    when(job.getConfiguration()).thenReturn(config);
    when(bigQuery.getJob(any(JobId.class))).thenReturn(job);
    return job;
  }

  /**
   * Builds a finished job that returns the given number of rows.
   *
   * @param count how many rows the job returns
   * @return the mock
   */
  private Job doneJobWithRows(int count) throws Exception {
    var job = job(JobStatus.State.DONE, null);
    var rows = IntStream.range(0, count).mapToObj(i -> row("id-" + i, Map.of())).toList();
    var result = mock(TableResult.class);
    when(result.iterateAll()).thenReturn(rows);
    when(job.getQueryResults()).thenReturn(result);
    return job;
  }

  /**
   * Verifies that submitting creates a job with a {@code calls-query-<uuid>} id in the configured
   * location, and returns that id.
   */
  @Test
  void submitCreatesAJobWithOurIdInThePinnedLocation() {
    var id = service.submit(QUERY);

    var captor = ArgumentCaptor.forClass(JobInfo.class);
    verify(bigQuery).create(captor.capture());
    var info = captor.getValue();
    assertThat(id).matches("calls-query-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    assertThat(info.getJobId().getJob()).isEqualTo(id);
    assertThat(info.getJobId().getLocation()).isEqualTo("US");
  }

  /**
   * Verifies that request values travel as named parameters and never appear in the SQL, and that
   * one row more than the cap is requested.
   */
  @Test
  void requestValuesAreQueryParametersNotSql() {
    service.submit(QUERY);

    var captor = ArgumentCaptor.forClass(JobInfo.class);
    verify(bigQuery).create(captor.capture());
    var config = (QueryJobConfiguration) captor.getValue().getConfiguration();
    assertThat(config.getQuery())
        .contains("`demo-project.telecom_analytics.calls`")
        .contains("@telephoneNumber", "@start", "@end", "@limit")
        .doesNotContain("15550100025");
    assertThat(config.getNamedParameters())
        .containsOnlyKeys("telephoneNumber", "start", "end", "startDate", "endDate", "limit");
    assertThat(config.getNamedParameters().get("telephoneNumber").getValue()).isEqualTo("15550100025");
    assertThat(config.getNamedParameters().get("start").getValue()).isEqualTo("2026-01-15 00:00:00.000000+00:00");
    assertThat(config.getNamedParameters().get("startDate").getValue()).isEqualTo("2026-01-15");
    assertThat(config.getNamedParameters().get("endDate").getValue()).isEqualTo("2026-01-16");
    // Asks for one row more than the cap so truncation can be detected.
    assertThat(config.getNamedParameters().get("limit").getValue()).isEqualTo("1001");
  }

  /** Verifies that a {@link BigQueryException} on submit becomes {@code UPSTREAM_ERROR}. */
  @Test
  void submitFailureIsUpstreamError() {
    when(bigQuery.create(any(JobInfo.class))).thenThrow(new BigQueryException(500, "backend detail"));

    assertThatThrownBy(() -> service.submit(QUERY))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(Code.UPSTREAM_ERROR);
          assertThat(e.getMessage()).doesNotContain("backend detail");
        });
  }

  /**
   * Verifies that ids not generated by this service are {@code 404} without calling BigQuery.
   */
  @Test
  void foreignOrMalformedJobIdsAreNotFoundWithoutCallingBigQuery() {
    for (var id : List.of("flyway-job-1", "calls-query-", "calls-query-../x", "calls-query-123",
        JOB_ID + "x", "x" + JOB_ID, "")) {
      assertThatThrownBy(() -> service.find(id))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(Code.NOT_FOUND));
    }
    verifyNoMoreInteractions(bigQuery);
  }

  /** Verifies that a job BigQuery does not know (never created or expired) is {@code 404}. */
  @Test
  void unknownJobIsNotFound() {
    when(bigQuery.getJob(any(JobId.class))).thenReturn(null);

    assertThatThrownBy(() -> service.find(JOB_ID))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(Code.NOT_FOUND));
  }

  /** Verifies that the lookup uses the pinned location and the given id. */
  @Test
  void findLooksUpTheJobInThePinnedLocation() {
    when(bigQuery.getJob(any(JobId.class))).thenReturn(null);
    try {
      service.find(JOB_ID);
    } catch (ApiException expected) {
      // 404 is covered elsewhere.
    }

    var captor = ArgumentCaptor.forClass(JobId.class);
    verify(bigQuery).getJob(captor.capture());
    assertThat(captor.getValue().getJob()).isEqualTo(JOB_ID);
    assertThat(captor.getValue().getLocation()).isEqualTo("US");
  }

  /** Verifies that unfinished jobs map to {@code PENDING} and {@code RUNNING} without a result. */
  @Test
  void unfinishedJobsReportTheirStatus() {
    job(JobStatus.State.PENDING, null);
    assertThat(service.find(JOB_ID).status()).isEqualTo(QueryStatus.PENDING);

    job(JobStatus.State.RUNNING, null);
    var running = service.find(JOB_ID);
    assertThat(running.status()).isEqualTo(QueryStatus.RUNNING);
    assertThat(running.calls()).isNull();
  }

  /** Verifies that a failed job is {@code FAILED} with a generic error that hides BigQuery's. */
  @Test
  void failedJobReportsAGenericError() {
    job(JobStatus.State.DONE, new BigQueryError("invalidQuery", "loc", "secret backend detail"));

    var response = service.find(JOB_ID);

    assertThat(response.status()).isEqualTo(QueryStatus.FAILED);
    assertThat(response.calls()).isNull();
    assertThat(response.error().code()).isEqualTo("UPSTREAM_ERROR");
    assertThat(response.error().detail()).isEqualTo("Query failed");
  }

  /** Verifies that no rows give {@code count} 0, {@code truncated} false and the echoed range. */
  @Test
  void emptyResultIsCountZero() throws Exception {
    doneJobWithRows(0);

    var response = service.find(JOB_ID);

    assertThat(response.status()).isEqualTo(QueryStatus.DONE);
    assertThat(response.count()).isZero();
    assertThat(response.truncated()).isFalse();
    assertThat(response.calls()).isEmpty();
    assertThat(response.telephoneNumber()).isEqualTo("15550100025");
    assertThat(response.startDatetime()).isEqualTo("2026-01-15T00:00:00Z");
    assertThat(response.endDatetime()).isEqualTo("2026-01-16T00:00:00Z");
  }

  /** Verifies that exactly {@value CallsQueryService#MAX_ROWS} rows are not reported as truncated. */
  @Test
  void exactlyTheCapIsNotTruncated() throws Exception {
    doneJobWithRows(CallsQueryService.MAX_ROWS);

    var response = service.find(JOB_ID);

    assertThat(response.count()).isEqualTo(1000);
    assertThat(response.truncated()).isFalse();
  }

  /** Verifies that one row over the cap is dropped and {@code truncated} is set. */
  @Test
  void moreThanTheCapIsTruncatedToTheCap() throws Exception {
    doneJobWithRows(CallsQueryService.MAX_ROWS + 1);

    var response = service.find(JOB_ID);

    assertThat(response.count()).isEqualTo(1000);
    assertThat(response.truncated()).isTrue();
    assertThat(response.calls().getLast().callId()).isEqualTo("id-999");
  }

  /**
   * Verifies column mapping, the UTC timestamp format and that {@code NULL} columns stay {@code
   * null}.
   */
  @Test
  void mapsColumnsAndKeepsNullsAsNull() throws Exception {
    var job = job(JobStatus.State.DONE, null);
    var result = mock(TableResult.class);
    var full =
        row("id-1", Map.of("callType", "voip", "isRoaming", "true", "costUsd", "1.250000000"));
    when(result.iterateAll()).thenReturn(List.of(full));
    when(job.getQueryResults()).thenReturn(result);

    var call = service.find(JOB_ID).calls().getFirst();

    assertThat(call.callId()).isEqualTo("id-1");
    assertThat(call.offhookDatetime()).isEqualTo("2026-01-15T00:00:00.123456Z");
    assertThat(call.durationSeconds()).isEqualTo(90);
    assertThat(call.callType()).isEqualTo("voip");
    assertThat(call.isRoaming()).isTrue();
    assertThat(call.isInternational()).isNull();
    assertThat(call.costUsd()).isEqualByComparingTo(new BigDecimal("1.25"));
    assertThat(call.callingCity()).isNull();
  }

  /** Verifies that a {@link BigQueryException} while reading is {@code UPSTREAM_ERROR}. */
  @Test
  void bigQueryFailureWhileFetchingIsUpstreamError() {
    when(bigQuery.getJob(any(JobId.class))).thenThrow(new BigQueryException(500, "backend detail"));

    assertThatThrownBy(() -> service.find(JOB_ID))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(Code.UPSTREAM_ERROR);
          assertThat(e.getMessage()).doesNotContain("backend detail");
        });
  }
}

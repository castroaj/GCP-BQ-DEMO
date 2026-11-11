package com.example.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.calls.ApiException.Code;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
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
    service = new CallsQueryService(bigQuery, "telecom_analytics");
  }

  /** A row with all 20 columns; filler columns are null unless overridden. */
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

  private void returnRows(int count) {
    var rows = IntStream.range(0, count).mapToObj(i -> row("id-" + i, Map.of())).toList();
    var result = mock(TableResult.class);
    when(result.iterateAll()).thenReturn(rows);
    try {
      when(bigQuery.query(any(QueryJobConfiguration.class))).thenReturn(result);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void requestValuesAreQueryParametersNotSql() throws Exception {
    returnRows(0);

    service.query(QUERY);

    var captor = ArgumentCaptor.forClass(QueryJobConfiguration.class);
    org.mockito.Mockito.verify(bigQuery).query(captor.capture());
    var config = captor.getValue();
    assertThat(config.getQuery())
        .contains("`demo-project.telecom_analytics.calls`")
        .contains("@telephoneNumber", "@start", "@end", "@limit")
        .doesNotContain("15550100025");
    assertThat(config.getNamedParameters())
        .containsOnlyKeys("telephoneNumber", "start", "end", "startDate", "endDate", "limit");
    assertThat(config.getNamedParameters().get("telephoneNumber").getValue()).isEqualTo("15550100025");
    assertThat(config.getNamedParameters().get("startDate").getValue()).isEqualTo("2026-01-15");
    assertThat(config.getNamedParameters().get("endDate").getValue()).isEqualTo("2026-01-16");
    // Asks for one row more than the cap so truncation can be detected.
    assertThat(config.getNamedParameters().get("limit").getValue()).isEqualTo("1001");
  }

  @Test
  void emptyResultIsCountZero() {
    returnRows(0);

    var response = service.query(QUERY);

    assertThat(response.count()).isZero();
    assertThat(response.truncated()).isFalse();
    assertThat(response.calls()).isEmpty();
    assertThat(response.startDatetime()).isEqualTo("2026-01-15T00:00:00Z");
  }

  @Test
  void exactlyTheCapIsNotTruncated() {
    returnRows(CallsQueryService.MAX_ROWS);

    var response = service.query(QUERY);

    assertThat(response.count()).isEqualTo(1000);
    assertThat(response.truncated()).isFalse();
  }

  @Test
  void moreThanTheCapIsTruncatedToTheCap() {
    returnRows(CallsQueryService.MAX_ROWS + 1);

    var response = service.query(QUERY);

    assertThat(response.count()).isEqualTo(1000);
    assertThat(response.truncated()).isTrue();
    assertThat(response.calls().getLast().callId()).isEqualTo("id-999");
  }

  @Test
  void mapsColumnsAndKeepsNullsAsNull() throws Exception {
    var result = mock(TableResult.class);
    var full =
        row("id-1", Map.of("callType", "voip", "isRoaming", "true", "costUsd", "1.250000000"));
    when(result.iterateAll()).thenReturn(List.of(full));
    when(bigQuery.query(any(QueryJobConfiguration.class))).thenReturn(result);

    var call = service.query(QUERY).calls().getFirst();

    assertThat(call.callId()).isEqualTo("id-1");
    assertThat(call.offhookDatetime()).isEqualTo("2026-01-15T00:00:00.123456Z");
    assertThat(call.durationSeconds()).isEqualTo(90);
    assertThat(call.callType()).isEqualTo("voip");
    assertThat(call.isRoaming()).isTrue();
    assertThat(call.isInternational()).isNull();
    assertThat(call.costUsd()).isEqualByComparingTo(new BigDecimal("1.25"));
    assertThat(call.callingCity()).isNull();
  }

  @Test
  void bigQueryFailureIsUpstreamError() throws Exception {
    when(bigQuery.query(any(QueryJobConfiguration.class)))
        .thenThrow(new BigQueryException(500, "backend detail"));

    assertThatThrownBy(() -> service.query(QUERY))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(Code.UPSTREAM_ERROR);
          assertThat(e.getMessage()).doesNotContain("backend detail");
        });
  }
}

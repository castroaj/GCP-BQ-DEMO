package com.example.calls.api.query;

import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** Maps BigQuery rows of {@code telecom_analytics.calls} to {@link Call} records. */
final class CallRowMapper {
  private CallRowMapper() {}

  /**
   * Maps one result row to a {@link Call}. Nullable columns that are {@code NULL} in BigQuery
   * become {@code null}.
   *
   * @param row a row selected with all 20 columns of {@code calls}, addressable by column name
   * @return the Call, with {@code offhookDatetime} as UTC ISO 8601
   * @throws IllegalArgumentException if the row lacks one of the columns
   */
  static Call toCall(FieldValueList row) {
    return new Call(
        row.get("callId").getStringValue(),
        row.get("calling").getStringValue(),
        row.get("called").getStringValue(),
        iso(Instant.ofEpochSecond(0, row.get("offhookDatetime").getTimestampValue() * 1000)),
        row.get("durationSeconds").getLongValue(),
        string(row, "callType"),
        string(row, "direction"),
        string(row, "callStatus"),
        string(row, "callingCarrier"),
        string(row, "calledCarrier"),
        string(row, "callingCountryCode"),
        string(row, "calledCountryCode"),
        string(row, "callingCity"),
        string(row, "calledCity"),
        string(row, "cellTowerId"),
        string(row, "networkType"),
        string(row, "codec"),
        bool(row, "isRoaming"),
        bool(row, "isInternational"),
        row.get("costUsd").isNull() ? null : row.get("costUsd").getNumericValue());
  }

  /**
   * Formats an instant the way every datetime in the API is written.
   *
   * @param instant the moment to format
   * @return UTC ISO 8601, e.g. {@code 2026-01-15T00:00:00Z}, with fractional seconds when present
   */
  static String iso(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant);
  }

  /**
   * Reads a nullable string column.
   *
   * @param row the result row
   * @param column the column name
   * @return the value, or {@code null} when the column is {@code NULL}
   */
  private static String string(FieldValueList row, String column) {
    FieldValue value = row.get(column);
    return value.isNull() ? null : value.getStringValue();
  }

  /**
   * Reads a nullable boolean column.
   *
   * @param row the result row
   * @param column the column name
   * @return the value, or {@code null} when the column is {@code NULL}
   */
  private static Boolean bool(FieldValueList row, String column) {
    FieldValue value = row.get(column);
    return value.isNull() ? null : value.getBooleanValue();
  }
}

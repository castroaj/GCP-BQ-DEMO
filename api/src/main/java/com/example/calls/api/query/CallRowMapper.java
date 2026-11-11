package com.example.calls.api.query;

import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** Maps a BigQuery row of telecom_analytics.calls to a {@link Call}. */
final class CallRowMapper {
  private CallRowMapper() {}

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

  /** UTC ISO 8601, as used for every datetime in the API. */
  static String iso(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant);
  }

  private static String string(FieldValueList row, String column) {
    FieldValue value = row.get(column);
    return value.isNull() ? null : value.getStringValue();
  }

  private static Boolean bool(FieldValueList row, String column) {
    FieldValue value = row.get(column);
    return value.isNull() ? null : value.getBooleanValue();
  }
}

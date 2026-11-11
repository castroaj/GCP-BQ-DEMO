package com.example.calls;

import com.example.calls.ApiException.Code;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CallsQueryService {
  static final int MAX_ROWS = 1000;

  private final BigQuery bigQuery;
  private final String dataset;

  public CallsQueryService(BigQuery bigQuery, @Value("${calls.dataset}") String dataset) {
    this.bigQuery = bigQuery;
    this.dataset = dataset;
  }

  public QueryResponse query(String telephoneNumber, Instant start, Instant end) {
    var table = "`" + bigQuery.getOptions().getProjectId() + "." + dataset + ".calls`";
    var config =
        QueryJobConfiguration.newBuilder(sql(table))
            .addNamedParameter("telephoneNumber", QueryParameterValue.string(telephoneNumber))
            .addNamedParameter("start", QueryParameterValue.timestamp(micros(start)))
            .addNamedParameter("end", QueryParameterValue.timestamp(micros(end)))
            .addNamedParameter(
                "startDate", QueryParameterValue.date(start.atZone(ZoneOffset.UTC).toLocalDate().toString()))
            .addNamedParameter(
                "endDate", QueryParameterValue.date(end.atZone(ZoneOffset.UTC).toLocalDate().toString()))
            // One extra row tells us whether the result was truncated.
            .addNamedParameter("limit", QueryParameterValue.int64(MAX_ROWS + 1))
            .build();

    TableResult result;
    try {
      result = bigQuery.query(config);
    } catch (BigQueryException e) {
      throw new ApiException(Code.UPSTREAM_ERROR, "Query failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(Code.UPSTREAM_ERROR, "Query interrupted", e);
    }

    List<Call> calls = new ArrayList<>();
    boolean truncated = false;
    for (FieldValueList row : result.iterateAll()) {
      if (calls.size() == MAX_ROWS) {
        truncated = true;
        break;
      }
      calls.add(toCall(row));
    }
    return new QueryResponse(telephoneNumber, iso(start), iso(end), calls.size(), truncated, calls);
  }

  /** Values only are parameters; the table name is fixed server-side config. */
  static String sql(String table) {
    return """
        SELECT callId, calling, called, offhookDatetime, durationSeconds, callType, direction,
               callStatus, callingCarrier, calledCarrier, callingCountryCode, calledCountryCode,
               callingCity, calledCity, cellTowerId, networkType, codec, isRoaming,
               isInternational, costUsd
        FROM %s
        WHERE DATE(offhookDatetime) BETWEEN @startDate AND @endDate
          AND offhookDatetime >= @start AND offhookDatetime < @end
          AND (calling = @telephoneNumber OR called = @telephoneNumber)
        ORDER BY offhookDatetime, callId
        LIMIT @limit
        """
        .formatted(table);
  }

  private static Call toCall(FieldValueList r) {
    return new Call(
        r.get("callId").getStringValue(),
        r.get("calling").getStringValue(),
        r.get("called").getStringValue(),
        iso(Instant.ofEpochSecond(0, r.get("offhookDatetime").getTimestampValue() * 1000)),
        r.get("durationSeconds").getLongValue(),
        str(r, "callType"),
        str(r, "direction"),
        str(r, "callStatus"),
        str(r, "callingCarrier"),
        str(r, "calledCarrier"),
        str(r, "callingCountryCode"),
        str(r, "calledCountryCode"),
        str(r, "callingCity"),
        str(r, "calledCity"),
        str(r, "cellTowerId"),
        str(r, "networkType"),
        str(r, "codec"),
        bool(r, "isRoaming"),
        bool(r, "isInternational"),
        r.get("costUsd").isNull() ? null : r.get("costUsd").getNumericValue());
  }

  private static String str(FieldValueList r, String name) {
    FieldValue v = r.get(name);
    return v.isNull() ? null : v.getStringValue();
  }

  private static Boolean bool(FieldValueList r, String name) {
    FieldValue v = r.get(name);
    return v.isNull() ? null : v.getBooleanValue();
  }

  private static long micros(Instant i) {
    return i.getEpochSecond() * 1_000_000 + i.getNano() / 1000;
  }

  private static String iso(Instant i) {
    return DateTimeFormatter.ISO_INSTANT.format(i);
  }
}

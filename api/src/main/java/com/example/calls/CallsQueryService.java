package com.example.calls;

import com.example.calls.ApiException.Code;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CallsQueryService {
  static final int MAX_ROWS = 1000;
  private static final long MICROS_PER_SECOND = 1_000_000;

  private final BigQuery bigQuery;
  private final String dataset;

  public CallsQueryService(BigQuery bigQuery, @Value("${calls.dataset}") String dataset) {
    this.bigQuery = bigQuery;
    this.dataset = dataset;
  }

  public QueryResponse query(CallQuery query) {
    TableResult result = run(queryConfig(query));

    List<Call> calls = new ArrayList<>();
    boolean truncated = false;
    for (FieldValueList row : result.iterateAll()) {
      if (calls.size() == MAX_ROWS) {
        truncated = true;
        break;
      }
      calls.add(CallRowMapper.toCall(row));
    }
    return new QueryResponse(
        query.telephoneNumber(),
        CallRowMapper.iso(query.start()),
        CallRowMapper.iso(query.end()),
        calls.size(),
        truncated,
        calls);
  }

  /** Every request value is a named parameter; only the server-side table name is formatted in. */
  QueryJobConfiguration queryConfig(CallQuery query) {
    String table = "`" + bigQuery.getOptions().getProjectId() + "." + dataset + ".calls`";
    return QueryJobConfiguration.newBuilder(sql(table))
        .addNamedParameter("telephoneNumber", QueryParameterValue.string(query.telephoneNumber()))
        .addNamedParameter("start", timestamp(query.start()))
        .addNamedParameter("end", timestamp(query.end()))
        .addNamedParameter("startDate", utcDate(query.start()))
        .addNamedParameter("endDate", utcDate(query.end()))
        // One extra row tells us whether the result was truncated.
        .addNamedParameter("limit", QueryParameterValue.int64(MAX_ROWS + 1))
        .build();
  }

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

  private TableResult run(QueryJobConfiguration config) {
    try {
      return bigQuery.query(config);
    } catch (BigQueryException e) {
      throw new ApiException(Code.UPSTREAM_ERROR, "Query failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(Code.UPSTREAM_ERROR, "Query interrupted", e);
    }
  }

  private static QueryParameterValue timestamp(Instant instant) {
    long micros = instant.getEpochSecond() * MICROS_PER_SECOND + instant.getNano() / 1000;
    return QueryParameterValue.timestamp(micros);
  }

  /** The UTC calendar day, used to prune the table's daily partitions. */
  private static QueryParameterValue utcDate(Instant instant) {
    return QueryParameterValue.date(instant.atZone(ZoneOffset.UTC).toLocalDate().toString());
  }
}

package com.example.calls.api.query;

import com.example.calls.api.ApiException;
import com.example.calls.api.ApiException.Code;
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

/**
 * Looks up Calls in the BigQuery table {@code telecom_analytics.calls}.
 *
 * <p>Every request value is passed as a named query parameter; only the server-side table name is
 * formatted into the SQL. Rows are mapped by {@link CallRowMapper}.
 *
 * @see CallsController
 */
@Service
public class CallsQueryService {
  /** The most Calls a response carries; one more row is fetched to detect truncation. */
  static final int MAX_ROWS = 1000;

  private static final long MICROS_PER_SECOND = 1_000_000;

  private final BigQuery bigQuery;
  private final String dataset;

  /**
   * Creates the service.
   *
   * @param bigQuery the shared BigQuery client; its project holds the dataset
   * @param dataset id of the dataset that contains the {@code calls} table, from {@code
   *     calls.dataset}
   */
  public CallsQueryService(BigQuery bigQuery, @Value("${calls.dataset}") String dataset) {
    this.bigQuery = bigQuery;
    this.dataset = dataset;
  }

  /**
   * Runs a lookup and returns at most {@value #MAX_ROWS} Calls.
   *
   * @param query the validated lookup
   * @return the matching Calls sorted by {@code offhookDatetime} then {@code callId}, with {@code
   *     truncated} set when more than {@value #MAX_ROWS} matched
   * @throws ApiException with {@link Code#UPSTREAM_ERROR} if BigQuery fails or the call is
   *     interrupted; the cause is kept for the log and not exposed to the caller
   */
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

  /**
   * Builds the BigQuery job for a lookup, binding every request value as a named parameter.
   *
   * @param query the validated lookup
   * @return the query with its parameters; a partition-date filter lets BigQuery read only the
   *     requested days
   */
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

  /**
   * Returns the lookup SQL for a table.
   *
   * @param table the fully qualified, back-quoted table name; server-side configuration, never
   *     request input
   * @return SQL with the named parameters {@code @telephoneNumber}, {@code @start}, {@code @end},
   *     {@code @startDate}, {@code @endDate} and {@code @limit}
   */
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

  /**
   * Runs a query and waits for the result.
   *
   * @param config the query to run
   * @return the result rows
   * @throws ApiException with {@link Code#UPSTREAM_ERROR} if BigQuery fails or the thread is
   *     interrupted (the interrupt flag is restored)
   */
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

  /**
   * Converts an instant to a BigQuery {@code TIMESTAMP} parameter.
   *
   * @param instant the moment to bind
   * @return the parameter, in microseconds since the epoch
   */
  private static QueryParameterValue timestamp(Instant instant) {
    long micros = instant.getEpochSecond() * MICROS_PER_SECOND + instant.getNano() / 1000;
    return QueryParameterValue.timestamp(micros);
  }

  /**
   * Converts an instant to a BigQuery {@code DATE} parameter for partition pruning.
   *
   * @param instant the moment whose UTC calendar day to bind
   * @return the parameter for that day
   */
  private static QueryParameterValue utcDate(Instant instant) {
    return QueryParameterValue.date(instant.atZone(ZoneOffset.UTC).toLocalDate().toString());
  }
}

package com.example.calls.api.query;

import com.example.calls.api.ApiException.Code;
import com.example.calls.api.ApiException;
import com.example.calls.api.query.dto.JobError;
import com.example.calls.api.query.dto.JobResponse;
import com.example.calls.api.query.dto.QueryResponse;
import com.example.calls.api.query.enums.QueryStatus;
import com.example.calls.api.query.model.Call;
import com.example.calls.api.query.model.CallQuery;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatus;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Looks up Calls in the BigQuery table {@code telecom_analytics.calls}.
 *
 * <p>Every request value is passed as a named query parameter; only the server-side table name is
 * formatted into the SQL. A lookup is a BigQuery job: {@link #submit} starts it and {@link #find}
 * reads it back by id, so no state lives in this service. Rows are mapped by {@link
 * CallRowMapper}.
 *
 * @see CallsController
 */
@Service
public class CallsQueryService {
  /** The most Calls a response carries; one more row is fetched to detect truncation. */
  static final int MAX_ROWS = 1000;

  /** Every job id this service creates starts with this; anything else is not ours to read. */
  private static final String JOB_PREFIX = "calls-query-";

  private static final Pattern JOB_ID =
      Pattern.compile(JOB_PREFIX + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private static final long MICROS_PER_SECOND = 1_000_000;

  /** How the BigQuery client writes a {@code TIMESTAMP} parameter's value. */
  private static final DateTimeFormatter TIMESTAMP_PARAMETER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]XXX");

  private static final Logger log = LoggerFactory.getLogger(CallsQueryService.class);

  private final BigQuery bigQuery;
  private final String dataset;
  private final String location;

  /**
   * Creates the service.
   *
   * @param bigQuery the shared BigQuery client; its project holds the dataset
   * @param dataset id of the dataset that contains the {@code calls} table, from {@code
   *     calls.dataset}
   * @param location BigQuery location of that dataset, where jobs run and are looked up, from
   *     {@code calls.location}
   */
  public CallsQueryService(
      BigQuery bigQuery,
      @Value("${calls.dataset}") String dataset,
      @Value("${calls.location}") String location) {
    this.bigQuery = bigQuery;
    this.dataset = dataset;
    this.location = location;
  }

  /**
   * Starts a lookup as a BigQuery job and returns without waiting for it.
   *
   * @param query the validated lookup
   * @return the new job's id, {@code calls-query-<uuid>}; BigQuery keeps the job and its result
   *     for about 24 hours
   * @throws ApiException with {@link Code#UPSTREAM_ERROR} if BigQuery rejects the job
   */
  public String submit(CallQuery query) {
    JobId jobId =
        JobId.newBuilder().setJob(JOB_PREFIX + UUID.randomUUID()).setLocation(location).build();
    try {
      bigQuery.create(JobInfo.of(jobId, queryConfig(query)));
    } catch (BigQueryException e) {
      throw new ApiException(Code.UPSTREAM_ERROR, "Query failed", e);
    }
    return jobId.getJob();
  }

  /**
   * Looks up a submitted job and, if it has finished, its Calls.
   *
   * <p>Only ids this service generated are looked up, so the endpoint cannot be used to read other
   * jobs in the BigQuery project.
   *
   * @param jobId the id returned by {@link #submit(CallQuery)}, as sent by the client
   * @return the job's status; with at most {@value #MAX_ROWS} Calls and {@code truncated} set when
   *     more matched once {@link QueryStatus#DONE}, or with an error once {@link QueryStatus#FAILED}
   * @throws ApiException with {@link Code#NOT_FOUND} if the id does not have this service's format
   *     or BigQuery has no such job (never created or expired), or {@link Code#UPSTREAM_ERROR} if
   *     BigQuery fails or the call is interrupted
   */
  public JobResponse find(String jobId) {
    if (!JOB_ID.matcher(jobId).matches()) {
      throw notFound();
    }
    Job job;
    try {
      job = bigQuery.getJob(JobId.newBuilder().setJob(jobId).setLocation(location).build());
    } catch (BigQueryException e) {
      throw new ApiException(Code.UPSTREAM_ERROR, "Query failed", e);
    }
    if (job == null) {
      throw notFound();
    }
    JobStatus.State state = job.getStatus().getState();
    if (state != JobStatus.State.DONE) {
      return JobResponse.inProgress(
          jobId,
          state == JobStatus.State.RUNNING
              ? QueryStatus.RUNNING
              : QueryStatus.PENDING);
    }
    BigQueryError error = job.getStatus().getError();
    if (error != null) {
      log.error("Query job {} failed: {}", jobId, error);
      return JobResponse.failed(
          jobId,
          new JobError(
              Code.UPSTREAM_ERROR.status().value(),
              Code.UPSTREAM_ERROR.title(),
              "Query failed",
              Code.UPSTREAM_ERROR.name()));
    }
    return JobResponse.done(jobId, toResponse(job));
  }

  /**
   * Reads a finished job's rows and echoes the lookup it ran, recovered from the job's own query
   * parameters so that no state is kept outside BigQuery.
   *
   * @param job a job in state {@code DONE} without an error
   * @return the Calls, capped at {@value #MAX_ROWS}, and the range that was looked up
   * @throws ApiException with {@link Code#UPSTREAM_ERROR} if BigQuery fails or the thread is
   *     interrupted (the interrupt flag is restored)
   */
  private QueryResponse toResponse(Job job) {
    TableResult result;
    try {
      result = job.getQueryResults();
    } catch (BigQueryException e) {
      throw new ApiException(Code.UPSTREAM_ERROR, "Query failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(Code.UPSTREAM_ERROR, "Query interrupted", e);
    }
    var parameters = ((QueryJobConfiguration) job.getConfiguration()).getNamedParameters();

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
        parameters.get("telephoneNumber").getValue(),
        CallRowMapper.iso(instant(parameters.get("start"))),
        CallRowMapper.iso(instant(parameters.get("end"))),
        calls.size(),
        truncated,
        calls);
  }

  /**
   * Builds the not-found error shared by every way a job id can be unusable.
   *
   * @return the exception to throw
   */
  private static ApiException notFound() {
    return new ApiException(Code.NOT_FOUND, "No such query job");
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
   * Reads back a {@code TIMESTAMP} parameter written by {@link #timestamp(Instant)}.
   *
   * @param parameter the job's named parameter, valued like {@code 2026-01-15
   *     00:00:00.123456+00:00}
   * @return the same moment
   */
  private static Instant instant(QueryParameterValue parameter) {
    return OffsetDateTime.parse(parameter.getValue(), TIMESTAMP_PARAMETER).toInstant();
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

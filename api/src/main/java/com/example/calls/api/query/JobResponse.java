package com.example.calls.api.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The {@code 200} body of {@code GET /api/v1/query/{jobId}}. Fields that do not apply to the
 * {@code status} are omitted: the result fields appear only when {@link QueryStatus#DONE}, {@code
 * error} only when {@link QueryStatus#FAILED}.
 *
 * @param jobId the job this describes
 * @param status where the job is
 * @param telephoneNumber the number that was looked up (DONE only)
 * @param startDatetime start of the range (inclusive), UTC ISO 8601 (DONE only)
 * @param endDatetime end of the range (exclusive), UTC ISO 8601 (DONE only)
 * @param count number of Calls in {@code calls} (DONE only)
 * @param truncated {@code true} when more Calls matched than the 1,000-row cap returned (DONE
 *     only)
 * @param calls the matching Calls, sorted by {@code offhookDatetime} then {@code callId} (DONE
 *     only)
 * @param error why the job failed (FAILED only)
 * @see CallsController#result(String)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobResponse(
    String jobId,
    QueryStatus status,
    String telephoneNumber,
    String startDatetime,
    String endDatetime,
    Integer count,
    Boolean truncated,
    List<Call> calls,
    JobError error) {

  /**
   * Describes a job that has not finished.
   *
   * @param jobId the job
   * @param status {@link QueryStatus#PENDING} or {@link QueryStatus#RUNNING}
   * @return a response with no result and no error
   */
  static JobResponse inProgress(String jobId, QueryStatus status) {
    return new JobResponse(jobId, status, null, null, null, null, null, null, null);
  }

  /**
   * Describes a finished job and its Calls.
   *
   * @param jobId the job
   * @param result the Calls and the range that was looked up
   * @return a {@link QueryStatus#DONE} response carrying {@code result}
   */
  static JobResponse done(String jobId, QueryResponse result) {
    return new JobResponse(
        jobId,
        QueryStatus.DONE,
        result.telephoneNumber(),
        result.startDatetime(),
        result.endDatetime(),
        result.count(),
        result.truncated(),
        result.calls(),
        null);
  }

  /**
   * Describes a job that finished with an error.
   *
   * @param jobId the job
   * @param error why it failed
   * @return a {@link QueryStatus#FAILED} response carrying {@code error}
   */
  static JobResponse failed(String jobId, JobError error) {
    return new JobResponse(jobId, QueryStatus.FAILED, null, null, null, null, null, null, error);
  }
}

package com.example.calls.api.query;

import com.example.calls.api.ApiException;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for looking up Calls asynchronously: submit a lookup, then fetch it by job id. It only reads the request and delegates: validation lives
 * in {@link CallQuery} and the BigQuery work in {@link CallsQueryService}.
 */
@RestController
@RequestMapping("/api/v1")
public class CallsController {
  private final CallsQueryService service;

  /**
   * Creates the controller.
   *
   * @param service runs the validated lookups against BigQuery
   */
  public CallsController(CallsQueryService service) {
    this.service = service;
  }

  /**
   * Submits a lookup of one number in a time range, {@code POST /api/v1/query}.
   *
   * <p>The values are validated as {@link CallQuery#parse} defines, then run as a BigQuery job.
   * The call returns as soon as the job exists; fetch the Calls with {@link #result(String)}.
   *
   * @param request the lookup; all three fields are required
   * @return {@code 202 Accepted} with the job id and a {@code Location} header pointing at the
   *     result
   * @throws ApiException with status 400 if a field is missing, malformed or the range is invalid,
   *     or 502 if BigQuery rejects the job; both are rendered as problem details by {@link
   *     com.example.calls.api.ApiExceptionHandler}
   */
  @PostMapping("/query")
  public ResponseEntity<SubmitResponse> submit(@RequestBody SubmitRequest request) {
    String jobId =
        service.submit(
            CallQuery.parse(
                request.telephoneNumber(), request.startDatetime(), request.endDatetime()));
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/query/" + jobId))
        .body(new SubmitResponse(jobId));
  }

  /**
   * Returns the state of a submitted lookup and, once done, its Calls, {@code GET
   * /api/v1/query/{jobId}}.
   *
   * @param jobId the id returned by {@link #submit(SubmitRequest)}
   * @return the job's status; up to 1,000 matching Calls with {@code truncated} when {@code DONE},
   *     or an {@code error} when {@code FAILED}
   * @throws ApiException with status 404 if the id is not one of this API's job ids or the job
   *     does not exist or has expired, or 502 if BigQuery fails
   */
  @GetMapping("/query/{jobId}")
  public JobResponse result(@PathVariable("jobId") String jobId) {
    return service.find(jobId);
  }
}

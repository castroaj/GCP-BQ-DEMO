package com.example.calls.api.query;

import com.example.calls.api.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for looking up Calls. It only reads the request and delegates: validation lives
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
   * Returns the Calls of one number in a time range, {@code GET /api/v1/query}.
   *
   * <p>The parameters are read as raw strings so that {@link CallQuery#parse} owns validation and
   * the error codes. All three are required. The names are given explicitly so binding does not
   * depend on the compiler's {@code -parameters} flag, which IDE builds such as VS Code's Eclipse
   * compiler do not set.
   *
   * @param telephoneNumber E.164 digits without the leading {@code +}; matches {@code calling} or
   *     {@code called}
   * @param startDatetime ISO 8601 with an explicit UTC offset, inclusive start
   * @param endDatetime ISO 8601 with an explicit UTC offset, exclusive end
   * @return up to 1,000 matching Calls, with {@code truncated} set when more matched
   * @throws ApiException with status 400 if a parameter is missing, malformed or the range is
   *     invalid, or 502 if BigQuery fails; both are rendered as problem details by {@link
   *     com.example.calls.api.ApiExceptionHandler}
   */
  @GetMapping("/query")
  public QueryResponse query(
      @RequestParam(name = "telephoneNumber", required = false) String telephoneNumber,
      @RequestParam(name = "startDatetime", required = false) String startDatetime,
      @RequestParam(name = "endDatetime", required = false) String endDatetime) {
    return service.query(CallQuery.parse(telephoneNumber, startDatetime, endDatetime));
  }
}

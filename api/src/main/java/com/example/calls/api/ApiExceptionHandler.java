package com.example.calls.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 problem ({@code application/problem+json}) with a {@code
 * code} property.
 *
 * <p>Spring's own errors (unknown path, wrong method) come from the {@link
 * ResponseEntityExceptionHandler} base class in the same shape, without a {@code code}. BigQuery
 * details and stack traces go to the log only, never into the response.
 *
 * @see ApiException
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /**
   * Handles an error raised on purpose by the API.
   *
   * @param e the error; its cause, if any, is logged and not exposed
   * @return the problem to send, with the status and {@code code} of {@link ApiException#code()}
   */
  @ExceptionHandler(ApiException.class)
  ProblemDetail handleApi(ApiException e) {
    if (e.getCause() != null) {
      log.error("{}: {}", e.code(), e.getMessage(), e.getCause());
    }
    return problem(e.code(), e.getMessage());
  }

  /**
   * Last-resort handler for anything not handled elsewhere.
   *
   * @param e the unexpected failure; logged in full, never exposed
   * @return a {@link ApiException.Code#INTERNAL_ERROR} problem with a generic detail
   */
  @ExceptionHandler(Exception.class)
  ProblemDetail handleOther(Exception e) {
    log.error("Unhandled error", e);
    return problem(ApiException.Code.INTERNAL_ERROR, "An unexpected error occurred");
  }

  /**
   * Builds the problem body for a code.
   *
   * @param code the error code, which supplies the status, title and {@code code} property
   * @param detail caller-safe explanation
   * @return the problem to send
   */
  private static ProblemDetail problem(ApiException.Code code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
    problem.setTitle(code.title());
    problem.setProperty("code", code.name());
    return problem;
  }
}

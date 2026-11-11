package com.example.calls.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 9457 problem (application/problem+json) with a {@code code} property.
 * Spring's own errors (unknown path, wrong method) come from the base class in the same shape;
 * BigQuery details and stack traces go to the log only.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ProblemDetail handleApi(ApiException e) {
    if (e.getCause() != null) {
      log.error("{}: {}", e.code(), e.getMessage(), e.getCause());
    }
    return problem(e.code(), e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail handleOther(Exception e) {
    log.error("Unhandled error", e);
    return problem(ApiException.Code.INTERNAL_ERROR, "An unexpected error occurred");
  }

  private static ProblemDetail problem(ApiException.Code code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
    problem.setTitle(code.title());
    problem.setProperty("code", code.name());
    return problem;
  }
}

package com.example.calls;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every error body is {"error": CODE, "message": "..."}; details go to the log only. */
@RestControllerAdvice
class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, String>> handleApi(ApiException e) {
    if (e.getCause() != null) {
      log.error("{}: {}", e.code(), e.getMessage(), e.getCause());
    }
    return body(e.code(), e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> handleOther(Exception e) throws Exception {
    // Spring's own 404/405 and similar keep their default handling.
    if (e instanceof ErrorResponse) {
      throw e;
    }
    log.error("Unhandled error", e);
    return body(ApiException.Code.INTERNAL_ERROR, "Internal error");
  }

  private static ResponseEntity<Map<String, String>> body(ApiException.Code code, String message) {
    return ResponseEntity.status(code.status()).body(Map.of("error", code.name(), "message", message));
  }
}

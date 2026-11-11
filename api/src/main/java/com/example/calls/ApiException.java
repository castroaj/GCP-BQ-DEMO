package com.example.calls;

import org.springframework.http.HttpStatus;

/** An error that maps to the spec's {"error": CODE, "message": ...} body. */
public class ApiException extends RuntimeException {
  public enum Code {
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST),
    INVALID_RANGE(HttpStatus.BAD_REQUEST),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    Code(HttpStatus status) {
      this.status = status;
    }

    HttpStatus status() {
      return status;
    }
  }

  private final Code code;

  public ApiException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public ApiException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public Code code() {
    return code;
  }
}

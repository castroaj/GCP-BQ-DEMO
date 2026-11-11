package com.example.calls.api;

import org.springframework.http.HttpStatus;

/** An error that is reported to the caller as an RFC 9457 problem with a machine-readable code. */
public class ApiException extends RuntimeException {
  public enum Code {
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "Missing parameter"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid parameter"),
    INVALID_RANGE(HttpStatus.BAD_REQUEST, "Invalid range"),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "Upstream error"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    Code(HttpStatus status, String title) {
      this.status = status;
      this.title = title;
    }

    HttpStatus status() {
      return status;
    }

    String title() {
      return title;
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

package com.example.calls.api;

import org.springframework.http.HttpStatus;

/**
 * An error reported to the caller as an RFC 9457 problem with a machine-readable {@link Code}.
 *
 * <p>Throw it from any layer; {@link ApiExceptionHandler} turns it into the HTTP response. The
 * message becomes the problem's {@code detail}, so it must be safe to show to callers: no BigQuery
 * details and no stack traces. Put the underlying failure in the {@code cause}, which is logged
 * server-side only.
 *
 * @see ApiExceptionHandler
 */
public class ApiException extends RuntimeException {

  /** The error codes of the API, each with the HTTP status and problem title it produces. */
  public enum Code {
    /** A required request parameter is absent. */
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "Missing parameter"),
    /** A request parameter is present but malformed. */
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid parameter"),
    /** The requested time range is empty or reversed. */
    INVALID_RANGE(HttpStatus.BAD_REQUEST, "Invalid range"),
    /** BigQuery failed or did not answer. */
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "Upstream error"),
    /** Anything unexpected. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    Code(HttpStatus status, String title) {
      this.status = status;
      this.title = title;
    }

    /**
     * Returns the HTTP status sent for this code.
     *
     * @return the response status
     */
    HttpStatus status() {
      return status;
    }

    /**
     * Returns the short, human-readable problem title for this code.
     *
     * @return the problem {@code title}
     */
    String title() {
      return title;
    }
  }

  private final Code code;

  /**
   * Creates an error with no underlying cause.
   *
   * @param code the error code, which fixes the HTTP status
   * @param message caller-safe description, used as the problem {@code detail}
   */
  public ApiException(Code code, String message) {
    super(message);
    this.code = code;
  }

  /**
   * Creates an error that wraps a lower-level failure.
   *
   * @param code the error code, which fixes the HTTP status
   * @param message caller-safe description, used as the problem {@code detail}
   * @param cause the underlying failure; logged server-side and never sent to the caller
   */
  public ApiException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /**
   * Returns the error code.
   *
   * @return the code that decides the HTTP status and the problem's {@code code} property
   */
  public Code code() {
    return code;
  }
}

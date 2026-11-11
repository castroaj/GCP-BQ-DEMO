package com.example.calls.api.query.model;

import com.example.calls.api.ApiException;
import com.example.calls.api.ApiException.Code;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * A validated lookup: the Calls of one number in the half-open range {@code [start, end)}.
 *
 * <p>Build it with {@link #parse(String, String, String)}, which holds all request validation.
 *
 * @param telephoneNumber E.164 digits without the leading {@code +}; matched against both {@code
 *     calling} and {@code called}
 * @param start start of the range, inclusive
 * @param end end of the range, exclusive and after {@code start}
 * @see com.example.calls.api.query.CallsQueryService#submit(CallQuery)
 */
public record CallQuery(String telephoneNumber, Instant start, Instant end) {
  /** E.164 digits without the leading {@code +}, at most 15 digits. */
  private static final Pattern E164 = Pattern.compile("[1-9][0-9]{1,14}");

  /**
   * Validates the raw request parameters and builds the query.
   *
   * @param telephoneNumber E.164 digits without the leading {@code +}, e.g. {@code 15551234567}
   * @param startDatetime ISO 8601 with an explicit UTC offset, inclusive start
   * @param endDatetime ISO 8601 with an explicit UTC offset, exclusive end
   * @return the validated query, with both datetimes normalized to UTC instants
   * @throws ApiException with {@link Code#MISSING_PARAMETER} if a parameter is {@code null}, with
   *     {@link Code#INVALID_PARAMETER} if the number or a datetime is malformed (a datetime with
   *     no offset is malformed), or with {@link Code#INVALID_RANGE} if the end is not after the
   *     start
   */
  public static CallQuery parse(String telephoneNumber, String startDatetime, String endDatetime) {
    require("telephoneNumber", telephoneNumber);
    require("startDatetime", startDatetime);
    require("endDatetime", endDatetime);

    if (!E164.matcher(telephoneNumber).matches()) {
      throw new ApiException(
          Code.INVALID_PARAMETER,
          "telephoneNumber must be E.164 digits without the leading '+', e.g. 15551234567");
    }
    Instant start = parseDatetime("startDatetime", startDatetime);
    Instant end = parseDatetime("endDatetime", endDatetime);
    if (!end.isAfter(start)) {
      throw new ApiException(Code.INVALID_RANGE, "endDatetime must be after startDatetime");
    }
    return new CallQuery(telephoneNumber, start, end);
  }

  /**
   * Rejects an absent parameter.
   *
   * @param name the parameter name, used in the message
   * @param value the raw value, {@code null} when the parameter was not sent
   * @throws ApiException with {@link Code#MISSING_PARAMETER} if {@code value} is {@code null}
   */
  private static void require(String name, String value) {
    if (value == null) {
      throw new ApiException(Code.MISSING_PARAMETER, "Missing required parameter: " + name);
    }
  }

  /**
   * Parses an ISO 8601 datetime that carries an explicit offset ({@code Z} or {@code +hh:mm}).
   *
   * @param name the parameter name, used in the message
   * @param value the raw value
   * @return the same moment as a UTC instant
   * @throws ApiException with {@link Code#INVALID_PARAMETER} if {@code value} is not ISO 8601 or
   *     has no offset
   */
  private static Instant parseDatetime(String name, String value) {
    try {
      return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    } catch (DateTimeParseException e) {
      throw new ApiException(
          Code.INVALID_PARAMETER,
          name + " must be ISO 8601 with an explicit UTC offset, e.g. 2026-01-15T00:00:00Z");
    }
  }
}

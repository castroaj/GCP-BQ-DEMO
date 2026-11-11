package com.example.calls.api.query;

import com.example.calls.api.ApiException;
import com.example.calls.api.ApiException.Code;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** A validated lookup: Calls for one number in [start, end). Build it with {@link #parse}. */
public record CallQuery(String telephoneNumber, Instant start, Instant end) {
  // E.164 digits without the leading '+', at most 15 digits.
  private static final Pattern E164 = Pattern.compile("[1-9][0-9]{1,14}");

  /** Validates the raw request parameters; throws {@link ApiException} (400) when they are bad. */
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

  private static void require(String name, String value) {
    if (value == null) {
      throw new ApiException(Code.MISSING_PARAMETER, "Missing required parameter: " + name);
    }
  }

  /** ISO 8601 with an explicit offset ('Z' or +hh:mm); a bare local time is rejected. */
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

package com.example.calls;

import com.example.calls.ApiException.Code;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CallsController {
  // E.164 digits without the leading '+', at most 15 digits.
  private static final Pattern E164 = Pattern.compile("[1-9][0-9]{1,14}");

  private final CallsQueryService service;

  public CallsController(CallsQueryService service) {
    this.service = service;
  }

  @GetMapping("/query")
  public QueryResponse query(
      @RequestParam(required = false) String telephoneNumber,
      @RequestParam(required = false) String startDatetime,
      @RequestParam(required = false) String endDatetime) {
    require("telephoneNumber", telephoneNumber);
    require("startDatetime", startDatetime);
    require("endDatetime", endDatetime);

    if (!E164.matcher(telephoneNumber).matches()) {
      throw new ApiException(
          Code.INVALID_PARAMETER,
          "telephoneNumber must be E.164 digits without the leading '+', e.g. 15551234567");
    }
    var start = parseDatetime("startDatetime", startDatetime);
    var end = parseDatetime("endDatetime", endDatetime);
    if (!end.isAfter(start)) {
      throw new ApiException(Code.INVALID_RANGE, "endDatetime must be after startDatetime");
    }
    return service.query(telephoneNumber, start.toInstant(), end.toInstant());
  }

  private static void require(String name, String value) {
    if (value == null) {
      throw new ApiException(Code.MISSING_PARAMETER, "Missing required parameter: " + name);
    }
  }

  /** ISO 8601 with an explicit offset ('Z' or +hh:mm); a bare local time is rejected. */
  private static OffsetDateTime parseDatetime(String name, String value) {
    try {
      return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    } catch (DateTimeParseException e) {
      throw new ApiException(
          Code.INVALID_PARAMETER,
          name + " must be ISO 8601 with an explicit UTC offset, e.g. 2026-01-15T00:00:00Z");
    }
  }
}

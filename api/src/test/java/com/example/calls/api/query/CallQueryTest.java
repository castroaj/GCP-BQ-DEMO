package com.example.calls.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.calls.api.ApiException;
import com.example.calls.api.ApiException.Code;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CallQuery#parse(String, String, String)}: which parameters are accepted
 * and which {@link ApiException.Code} each bad input produces.
 */
class CallQueryTest {
  private static final String START = "2026-01-15T00:00:00Z";
  private static final String END = "2026-01-16T00:00:00Z";

  /**
   * Asserts that parsing fails with the given code.
   *
   * @param number the telephone number parameter
   * @param start the start datetime parameter
   * @param end the end datetime parameter
   * @param code the expected error code
   */
  private static void assertRejected(String number, String start, String end, Code code) {
    assertThatThrownBy(() -> CallQuery.parse(number, start, end))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  /**
   * Verifies that a valid request parses and that a non-UTC offset is normalized to UTC.
   */
  @Test
  void parsesValidParametersAndNormalizesOffsetsToUtc() {
    var query = CallQuery.parse("15550100001", "2026-01-15T05:00:00+05:00", END);

    assertThat(query)
        .isEqualTo(new CallQuery("15550100001", Instant.parse(START), Instant.parse(END)));
  }

  /**
   * Verifies that each absent parameter gives {@link ApiException.Code#MISSING_PARAMETER}.
   */
  @Test
  void missingParameterIsMissingParameter() {
    assertRejected(null, START, END, Code.MISSING_PARAMETER);
    assertRejected("15550100001", null, END, Code.MISSING_PARAMETER);
    assertRejected("15550100001", START, null, Code.MISSING_PARAMETER);
  }

  /**
   * Verifies that numbers with a {@code +}, letters, a leading zero, a bad length or no digits
   * give {@link ApiException.Code#INVALID_PARAMETER}.
   */
  @Test
  void malformedNumberIsInvalidParameter() {
    for (var number : List.of("+15550100001", "abc", "1", "0555010000", "1555010000112345678", "")) {
      assertRejected(number, START, END, Code.INVALID_PARAMETER);
    }
  }

  /**
   * Verifies that a datetime with no offset, a bare date or free text gives
   * {@link ApiException.Code#INVALID_PARAMETER}, for both start and end.
   */
  @Test
  void datetimeWithoutExplicitOffsetIsInvalidParameter() {
    for (var bad : List.of("2026-01-15T00:00:00", "2026-01-15", "yesterday", "")) {
      assertRejected("15550100001", bad, END, Code.INVALID_PARAMETER);
      assertRejected("15550100001", START, bad, Code.INVALID_PARAMETER);
    }
  }

  /**
   * Verifies that a reversed or empty range gives {@link ApiException.Code#INVALID_RANGE}.
   */
  @Test
  void endMustBeAfterStart() {
    assertRejected("15550100001", END, START, Code.INVALID_RANGE);
    assertRejected("15550100001", START, START, Code.INVALID_RANGE);
  }
}

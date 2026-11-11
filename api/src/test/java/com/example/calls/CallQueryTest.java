package com.example.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.calls.ApiException.Code;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallQueryTest {
  private static final String START = "2026-01-15T00:00:00Z";
  private static final String END = "2026-01-16T00:00:00Z";

  private static void assertRejected(String number, String start, String end, Code code) {
    assertThatThrownBy(() -> CallQuery.parse(number, start, end))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  @Test
  void parsesValidParametersAndNormalizesOffsetsToUtc() {
    var query = CallQuery.parse("15550100001", "2026-01-15T05:00:00+05:00", END);

    assertThat(query)
        .isEqualTo(new CallQuery("15550100001", Instant.parse(START), Instant.parse(END)));
  }

  @Test
  void missingParameterIsMissingParameter() {
    assertRejected(null, START, END, Code.MISSING_PARAMETER);
    assertRejected("15550100001", null, END, Code.MISSING_PARAMETER);
    assertRejected("15550100001", START, null, Code.MISSING_PARAMETER);
  }

  @Test
  void malformedNumberIsInvalidParameter() {
    for (var number : List.of("+15550100001", "abc", "1", "0555010000", "1555010000112345678", "")) {
      assertRejected(number, START, END, Code.INVALID_PARAMETER);
    }
  }

  @Test
  void datetimeWithoutExplicitOffsetIsInvalidParameter() {
    for (var bad : List.of("2026-01-15T00:00:00", "2026-01-15", "yesterday", "")) {
      assertRejected("15550100001", bad, END, Code.INVALID_PARAMETER);
      assertRejected("15550100001", START, bad, Code.INVALID_PARAMETER);
    }
  }

  @Test
  void endMustBeAfterStart() {
    assertRejected("15550100001", END, START, Code.INVALID_RANGE);
    assertRejected("15550100001", START, START, Code.INVALID_RANGE);
  }
}

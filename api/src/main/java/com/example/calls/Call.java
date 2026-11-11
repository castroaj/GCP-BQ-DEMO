package com.example.calls;

import java.math.BigDecimal;

/** One row of telecom_analytics.calls: all 20 columns, nulls kept as JSON null. */
public record Call(
    String callId,
    String calling,
    String called,
    String offhookDatetime,
    long durationSeconds,
    String callType,
    String direction,
    String callStatus,
    String callingCarrier,
    String calledCarrier,
    String callingCountryCode,
    String calledCountryCode,
    String callingCity,
    String calledCity,
    String cellTowerId,
    String networkType,
    String codec,
    Boolean isRoaming,
    Boolean isInternational,
    BigDecimal costUsd) {}

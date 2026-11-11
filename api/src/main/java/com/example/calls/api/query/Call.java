package com.example.calls.api.query;

import java.math.BigDecimal;

/**
 * One row of {@code telecom_analytics.calls}: a phone Call with all 20 columns. Nullable columns
 * are kept as JSON {@code null}.
 *
 * @param callId unique id of the Call (UUID)
 * @param calling E.164 digits of the number that placed the Call, without the leading {@code +}
 * @param called E.164 digits of the number that was called, without the leading {@code +}
 * @param offhookDatetime when the Call started, UTC ISO 8601
 * @param durationSeconds length of the Call in seconds; 0 when it was missed
 * @param callType type of Call, or {@code null}
 * @param direction direction of the Call, or {@code null}
 * @param callStatus outcome of the Call, or {@code null}
 * @param callingCarrier carrier of the calling number, or {@code null}
 * @param calledCarrier carrier of the called number, or {@code null}
 * @param callingCountryCode country code of the calling number, or {@code null}
 * @param calledCountryCode country code of the called number, or {@code null}
 * @param callingCity city of the calling number, or {@code null}
 * @param calledCity city of the called number, or {@code null}
 * @param cellTowerId cell tower that handled the Call, or {@code null}
 * @param networkType network technology, or {@code null}
 * @param codec audio codec, or {@code null}
 * @param isRoaming whether the caller was roaming, or {@code null}
 * @param isInternational whether the Call was international, or {@code null}
 * @param costUsd cost in US dollars, or {@code null}
 * @see CallRowMapper
 */
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

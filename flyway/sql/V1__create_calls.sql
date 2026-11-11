-- The calls table (decision #5). Dataset-qualified names come from the
-- Flyway `schemas` setting, so the dataset is not hard-coded here.
CREATE TABLE calls (
  callId             STRING    NOT NULL,
  calling            STRING    NOT NULL,
  called             STRING    NOT NULL,
  offhookDatetime    TIMESTAMP NOT NULL,
  durationSeconds    INT64     NOT NULL,
  callType           STRING,
  direction          STRING,
  callStatus         STRING,
  callingCarrier     STRING,
  calledCarrier      STRING,
  callingCountryCode STRING,
  calledCountryCode  STRING,
  callingCity        STRING,
  calledCity         STRING,
  cellTowerId        STRING,
  networkType        STRING,
  codec              STRING,
  isRoaming          BOOL,
  isInternational    BOOL,
  costUsd            NUMERIC
)
PARTITION BY DATE(offhookDatetime)
CLUSTER BY calling, called;

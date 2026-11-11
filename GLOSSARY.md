# Telecom Analytics

A prototype domain of phone-call records: which phone number called which, and when.

## Language

**Call**:
A record that one phone number called another phone number at a given time.
_Avoid_: Event, record, CDR

**calling**:
The phone number that placed the Call.
_Avoid_: caller, source, from

**called**:
The phone number that received the Call.
_Avoid_: callee, destination, to

**offhookDatetime**:
The date and time at which the Call was placed (the calling phone went off-hook).
_Avoid_: timestamp, startTime

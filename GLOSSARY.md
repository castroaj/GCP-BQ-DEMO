# Telecom Analytics

A prototype domain of phone-call records: which phone number called which, and when.

## Language

**Call**:
A record that one phone number called another phone number at a given time, for a measured duration.
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

**callId**:
The unique identifier of a Call.
_Avoid_: id, recordId

**durationSeconds**:
How long the Call lasted, in whole seconds; zero when it was never connected.
_Avoid_: length, talkTime

**telephoneNumber**:
The phone number being looked up; a Call matches when it is either the calling or the called number.
_Avoid_: phoneNumber, msisdn, party

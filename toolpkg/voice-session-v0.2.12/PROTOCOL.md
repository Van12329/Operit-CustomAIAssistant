# Protocol v2.12

SharedPreferences file: `custom_ai_voice_session_v2`

## State
`IDLE | GREETING_PENDING | GREETING_SPEAKING | POST_TTS_GUARD | LISTENING | PROCESSING | SPEAKING | FAILED`

Audio owner: `NONE | TTS | STT`.

## Transaction fields
- session_epoch
- turn_epoch
- tts_seq
- tts_state: NONE | REQUESTED | SPEAKING | COMPLETED | FAILED | START_TIMEOUT
- tts_requested_at_ms
- tts_started_at_ms
- tts_terminal_at_ms
- tts_preview
- audio_owner
- semantic_state
- last_reason

## Rules
1. Install/status/read operations MUST NOT increment tts_seq or create REQUESTED.
2. Only interception of a real VoiceService.speak call may create REQUESTED and take TTS ownership.
3. SPEAKING requires delegate isSpeaking=true / speakingStateFlow=true.
4. COMPLETED requires a previously observed SPEAKING transaction to become idle.
5. A failed delegate call -> FAILED.
6. REQUESTED that never reaches SPEAKING within bounded startup timeout -> START_TIMEOUT.
7. Speech wake-initial start may wait for a real REQUESTED transaction, but MUST NOT synthesize one.
8. While REQUESTED/SPEAKING, STT start is deferred.
9. COMPLETED -> 120 ms guard -> STT may acquire ownership.
10. FAILED/START_TIMEOUT on a verified wake-initial start is fail-closed. It MUST NOT silently grant STT.
11. Continuation restarts within an already-open user turn do not repeat greeting discovery.
12. Status functions are read-only.

## Recovery
Coordinator recovery may clear an abandoned transaction only after the session is no longer wake-active or a newer transaction/session supersedes it. Recovery does not manufacture LISTENING and does not count as success until an actual owner transition is observed.

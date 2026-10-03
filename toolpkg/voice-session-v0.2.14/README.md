# Voice Session v0.2.14

Structural session-lifecycle repair. No Operit APK changes.

Invariants:
- Coordinator owns session_epoch/session_active/session_state.
- Panel/service disappearance closes the active epoch and revokes audio ownership.
- Speech never accepts TTS state from another epoch.
- cancelRecognition invalidates the Speech generation, removes queued continuation/gate callbacks, and destroys the session SpeechRecognizer.
- Voice tags every TTS event with the active session epoch and distinguishes GREETING from ASSISTANT_RESPONSE.
- No synthetic pre-wake TTS lease.
- START_TIMEOUT/FAILED fail closed; they do not grant STT.
- ToolPkg status/state is persistent diagnostics only; it is not proof of a current session without matching epoch.

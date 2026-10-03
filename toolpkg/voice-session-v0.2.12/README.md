# Voice Session ToolPkg v0.2.12 — source-first recovery

This directory is intentionally independent of the Operit APK. It rebuilds the three voice ToolPkg artifacts from source.

## Target invariants
- No synthetic pre-wake TTS ownership.
- Coordinator is IDLE/NONE until a real VoiceService.speak transaction or a verified wake-initial STT request exists.
- Voice publishes REQUESTED before delegate speak, SPEAKING only from actual delegate state, and COMPLETED/FAILED/START_TIMEOUT terminal states.
- SpeechRecognizer never opens while a greeting transaction is REQUESTED/SPEAKING.
- Wake-initial discovery timeout is fail-closed: a missing greeting does not silently become LISTENING.
- COMPLETED -> 120 ms acoustic tail -> LISTENING.
- START_TIMEOUT/FAILED never auto-transition to LISTENING.
- Preserve RU/ES multilingual TTS routing, AUTO ru-RU/es-US STT, formatting, and lexical continuation confirmation.

## Why this tree exists
The prior v0.2.11 source archive is preserved in ChatGPT project storage, but the current container runtime is unavailable. This tree is the recovery path: GitHub Actions is used as the build computer so binary generation does not depend on the broken local container.

No files under app/ are modified by this recovery branch.

# Prompt for Gemini — current state of on-device live transcription on Android (July 2026)

> Paste everything below into Gemini. We need the CURRENT (mid-2026) answer, with sources.

---

You are an expert Android audio/ML platform engineer. Answer with the **current state as of mid-2026**, citing official Android/Google documentation or release notes wherever possible. Where behavior is device- or OEM-dependent, say so explicitly. Do not answer from pre-2025 knowledge without checking whether it changed.

## Context
I'm building a voice-notes Android app (Kotlin/Compose, minSdk 24, primary test device: Xiaomi Redmi Note 12 Pro+ 5G, China ROM with Google services installed). The app **must** (a) record full-quality audio to a file via its own `AudioRecord` in a microphone foreground service, and (b) show a **live streaming transcript while the user speaks** (like Pixel Recorder), and (c) later support **fully offline transcription**. Cloud refinement already exists; this is about the on-device layer.

## Questions

1. **Intended architecture.** What is Google's *intended/recommended* way in 2026 for an app to record audio to a file AND produce a live on-device transcript simultaneously? Is the blessed pattern still "capture once with your own AudioRecord and feed the PCM stream to a speech model," or is there now a public API designed for this exact use case?

2. **Mic coexistence / attribution.** If an app holds an active `AudioRecord` (MIC source, foreground service, type `microphone`) and then starts `SpeechRecognizer` (`createOnDeviceSpeechRecognizer` or the default), what happens on Android 13–16?
   - Does the recognition service's capture get attributed to the calling app (attribution chain), making concurrent capture permissible, or do the two contend under the concurrent-capture policy (one gets silence)?
   - Does the answer differ between the on-device recognizer and the network one, and across OEMs (Pixel vs Xiaomi/HyperOS)?

3. **Feeding my own audio.** Is there ANY public API in 2026 that accepts an app-supplied PCM stream (not mic ownership) for on-device recognition? Specifically check:
   - `SpeechRecognizer` / `RecognitionService` — any audio-injection/source-override option added in API 34–36?
   - **ML Kit GenAI Speech Recognition** (Gemini Nano / AICore) — does it accept audio streams or files from the app, or does it insist on owning the mic? Current device support list?
   - Any new AICore / Android AI APIs for speech-to-text with streaming input?

4. **Long-form behavior.** `SpeechRecognizer` historically auto-stopped after each utterance. Is there now a supported continuous/long-form mode (e.g. `EXTRA_SEGMENTED_SESSION`, `EXTRA_AUDIO_SOURCE`-style flags, or a long-form recognition intent), and does it work on non-Pixel devices?

5. **If the answer is "bring your own model":** what is the 2026 best-practice stack for streaming on-device ASR fed from an app's own AudioRecord on mid-range hardware (Snapdragon/Dimensity, 6–12 GB RAM)? Compare concretely (latency, RAM, model size, language coverage, licensing, Kotlin integration effort): Vosk, whisper.cpp / whisper-based streaming variants, Google's LiteRT-based ASR options, Moonshine/other 2025-26 streaming models, and anything newer worth knowing. Which would you ship for English-first with strong non-US-accent tolerance?

6. **Xiaomi/China-ROM caveats.** Anything specific about HyperOS/MIUI (with Play services sideloaded ecosystem) that affects `SpeechRecognizer` availability, on-device recognition, AICore availability, or mic arbitration that a Pixel-centric answer would miss?

## Output format
- Direct answers per question, each with "confidence: high/medium/low" and the source (doc page / release note / issue tracker).
- Finish with a concrete recommendation: the architecture you would build for the requirements above, as a short decision tree ("if device supports X → use X; else → Y").

# Claude Design — patch request for the Yap Android kit

Paste this into the same Claude Design session that produced "Yap — Android Designs". These are four targeted fixes to the existing kit — keep everything else (brand, Material 3 system, indigo #5B5BD6 / coral #FF6B6B, all current screens and states) exactly as-is. Match the existing visual style, 320×694 phone frames, light + dark.

## Fix 1 — Share-in must support audio/video files + add a file-import path (highest priority)
The current "External surfaces" share-sheet frame only shows **text** being shared in. The core use case is sharing an **audio (or video) file** from another app (e.g. the phone's voice recorder, WhatsApp voice notes, a video). Please:
- **Replace/extend the share-sheet frame** so the shared item is an **audio file** (show a file chip: e.g. "voice 0421.m4a · 2:13 · shared from Recorder"), with Yap listed as a target and a primary action "**Transcribe in Yap**".
- Add a **post-share landing state**: tapping Yap from the share sheet drops the file straight into the **processing/transcribing** state (reuse the existing Capture · processing visual, captioned "Imported audio · transcribing on device → polishing").
- Add a **file-import entry inside the app**: add an "**Import audio file**" item to the **app-shortcuts** menu (alongside New recording) AND a small secondary "import" affordance on the **Capture idle** screen (e.g. a text/file icon near the capture button). Keep it subtle so the live mic stays the hero.

## Fix 2 — BYOK is multi-provider (Gemini active now, others "coming soon")
The BYOK key-validation frame currently says "connected to OpenAI" with an `sk-…` key. Yap's model is **Gemini**, and BYOK should be **multi-provider** but with only **Gemini active for now**. Please update the BYOK / API-key screen to:
- Show a **provider selector** (a row of provider options): **Gemini — selectable/active**; **OpenAI, Anthropic (and a generic "More")** shown as **disabled with a "Coming soon" tag**.
- Default to **Gemini**: key field placeholder `AIza…` (Gemini key format), validated state label "**Valid key · connected to Gemini**", and the "Using your key" banner unchanged.
- Keep the three validation states (empty / validating / invalid) but with the Gemini key format.

## Fix 3 — Show the Power-User unlock gesture
The "Advanced unlocked" section + warning dialog exist, but the trigger isn't shown. On the **About** screen, depict the **Developer-Options-style unlock**: the version/build row with a small hint or a transient toast like "**Tap 4 more times to unlock Advanced**" → and the resulting "**Advanced unlocked**" snackbar. One extra small frame (or annotation on the existing About frame) is enough.

## Fix 4 — Cosmetic
On the Settings · main frame, the account email renders as a placeholder obfuscation ("[email protected]"). Replace with a plain sample email (e.g. `sam@example.com`).

That's all — four fixes, same style, light + dark where the screen has a dark variant.

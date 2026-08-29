import { strict as assert } from "node:assert";
import { test } from "node:test";

import { costOf, cycleOf, PRICES, TokenCounts, tokensFrom } from "./costMeter";

/**
 * The meter exists to be trusted against an invoice, so the failures worth pinning here are
 * the ones that would leave it confidently wrong: a modality split that silently prices audio
 * at the text rate, thinking tokens that never get counted, a provider response shaped
 * slightly differently from the one assumed. Every one of those reads as "cheaper than it is"
 * — the meter's errors are not symmetric, and that is the whole reason to test it.
 */

const empty: TokenCounts = { calls: 0, textIn: 0, audioIn: 0, out: 0 };

// ------------------------------------------------------------------- tokensFrom

test("splits audio from text using the modality breakdown", () => {
  const t = tokensFrom({
    promptTokenCount: 2040,
    promptTokensDetails: [
      { modality: "AUDIO", tokenCount: 1920 },
      { modality: "TEXT", tokenCount: 120 },
    ],
    candidatesTokenCount: 200,
  });
  assert.equal(t.audioIn, 1920);
  assert.equal(t.textIn, 120);
  assert.equal(t.out, 200);
  assert.equal(t.calls, 1);
});

test("counts thinking tokens as output", () => {
  // Thoughts are billed at the output rate and are reported outside the candidates count.
  // Missing them under-reads every formalize call we make.
  const t = tokensFrom({
    promptTokenCount: 700,
    candidatesTokenCount: 450,
    thoughtsTokenCount: 300,
  });
  assert.equal(t.out, 750);
});

test("a prompt with no modality breakdown is charged as text", () => {
  // Our text-only calls come back this way. Text is the correct rate for them, and it is
  // also the honest floor: we never invent audio tokens the provider did not report.
  const t = tokensFrom({ promptTokenCount: 512, candidatesTokenCount: 64 });
  assert.equal(t.textIn, 512);
  assert.equal(t.audioIn, 0);
});

test("audio beyond the reported prompt total cannot push text negative", () => {
  // A malformed or double-counted details array must never subtract from the bill.
  const t = tokensFrom({
    promptTokenCount: 100,
    promptTokensDetails: [{ modality: "AUDIO", tokenCount: 999 }],
  });
  assert.equal(t.textIn, 0);
  assert.equal(t.audioIn, 999);
});

test("a missing or malformed usage block yields zeroes, not a throw", () => {
  // This runs on the transcription path. It may read nothing; it may not take the note down.
  for (const meta of [undefined, null, {}, { promptTokensDetails: "nonsense" }]) {
    const t = tokensFrom(meta);
    assert.equal(t.textIn + t.audioIn + t.out, 0);
    assert.equal(t.calls, 1);
  }
});

test("lowercase modality names still register as audio", () => {
  const t = tokensFrom({
    promptTokenCount: 1920,
    promptTokensDetails: [{ modality: "audio", tokenCount: 1920 }],
  });
  assert.equal(t.audioIn, 1920);
});

// ------------------------------------------------------------------------ costOf

test("prices each modality at its own rate", () => {
  const usd = costOf("gemini-2.5-flash", {
    calls: 1,
    textIn: 1_000_000,
    audioIn: 1_000_000,
    out: 1_000_000,
  });
  assert.equal(usd, 0.3 + 1.0 + 2.5);
});

test("an unknown model prices at zero rather than throwing", () => {
  // It is surfaced by name in the response instead — a visible hole beats a crashed screen.
  assert.equal(costOf("gemini-9.9-imaginary", { calls: 1, textIn: 1e6, audioIn: 0, out: 0 }), 0);
});

test("nothing used costs nothing", () => {
  assert.equal(costOf("gemini-2.5-flash", empty), 0);
});

test("audio input on 2.5-flash is dearer than text by the documented multiple", () => {
  // If this ever collapses to 1.0 the modality split has stopped earning its keep, and a
  // blended rate would be the simpler design.
  const p = PRICES["gemini-2.5-flash"];
  assert.ok(p.audioIn / p.textIn > 3, "audio should still be >3x text");
});

// ------------------------------------------------------------------------ cycleOf

test("cycles are UTC and zero-padded so they sort", () => {
  assert.equal(cycleOf(new Date(Date.UTC(2026, 0, 5))), "2026-01");
  assert.equal(cycleOf(new Date(Date.UTC(2026, 11, 31, 23, 59))), "2026-12");
});

// -------------------------------------------------------------- the whole point

test("one minute of audio costs far more than one minute of audio", () => {
  // The estimate this meter replaces: audio in, at the audio rate, and nothing else.
  const audioTokens = 60 * 32;
  const naive = costOf("gemini-2.5-flash", { calls: 1, textIn: 0, audioIn: audioTokens, out: 0 });

  // What a one-minute note actually bills, as three calls with representative shapes.
  const measured =
    costOf("gemini-2.5-flash", tokensFrom({
      promptTokenCount: audioTokens + 120,
      promptTokensDetails: [{ modality: "AUDIO", tokenCount: audioTokens }],
      candidatesTokenCount: 200,
    })) +
    costOf("gemini-2.5-flash", tokensFrom({
      promptTokenCount: 700,
      candidatesTokenCount: 450,
      thoughtsTokenCount: 300,
    })) +
    costOf("gemini-2.5-flash", tokensFrom({
      promptTokenCount: 400,
      candidatesTokenCount: 60,
    }));

  assert.ok(
    measured / naive > 2,
    `audio-only estimate understates by ${(measured / naive).toFixed(2)}x — ` +
      "if this ratio ever drops near 1, the pipeline changed and the docs above are stale",
  );
});

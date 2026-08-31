import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";

/**
 * Tells the people building this that feedback has arrived.
 *
 * Without it a report lands in Firestore and nothing happens: the dashboard only shows it to
 * someone who thought to go and look. That gap is fine when nobody is using the app and
 * expensive the moment somebody is — a tester who hits a wall on Monday and hears nothing by
 * Friday has already stopped testing.
 *
 * A failure here must never cost the report. The document is already written by the time this
 * runs, so everything below is wrapped and logged rather than thrown: the worst case is an
 * email that didn't arrive and a row still sitting in the dashboard.
 */

const REGION = "africa-south1";

// Set with: firebase functions:secrets:set RESEND_API_KEY
const RESEND_API_KEY = defineSecret("RESEND_API_KEY");

/** Sends from the verified domain; `send.greenforrest.app` carries the SPF and return-path. */
const FROM = "Yap <feedback@greenforrest.app>";
const TO = "dev@greenforrest.app";
const DASHBOARD = "https://admin.yap.greenforrest.app/#feedback";

/** Mirrors the labels the client shows, so the subject line reads the way the sender saw it. */
const TOPIC_LABELS: Record<string, string> = {
  bug: "Something's broken",
  "transcription-quality": "Transcription quality",
  "feature-request": "I wish it could…",
  billing: "Billing or plans",
  performance: "Speed or battery",
  design: "How it looks or feels",
  other: "Something else",
};

function esc(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/**
 * A link that plays the voice message straight from the email.
 *
 * Seven days is the V4 signing maximum, and it is the right number anyway: a link that still
 * works a month later is a link to a user's voice sitting in an inbox forever. If signing
 * isn't available the caller says so in the email rather than losing the notification.
 */
async function signVoice(path: string): Promise<{ url: string | null; reason: string | null }> {
  try {
    const bucket = process.env.STORAGE_BUCKET || "yap-mehdiacho.firebasestorage.app";
    const [url] = await admin.storage().bucket(bucket).file(path).getSignedUrl({
      action: "read",
      expires: Date.now() + 7 * 24 * 60 * 60 * 1000,
    });
    return { url, reason: null };
  } catch (e: any) {
    return { url: null, reason: e?.message ?? "could not sign a link" };
  }
}

export const onFeedbackCreated = onDocumentCreated(
  { region: REGION, document: "feedback/{id}", secrets: [RESEND_API_KEY] },
  async (event) => {
    const d = event.data?.data();
    if (!d) return;
    const id = event.params.id;

    const topic = TOPIC_LABELS[d.topic] ?? d.topic ?? "Feedback";
    const message = typeof d.message === "string" ? d.message : "";
    const seconds = Math.round((Number(d.voiceDurationMs) || 0) / 1000);

    const voice = d.voicePath ? await signVoice(d.voicePath) : null;

    // The subject has to be readable in a notification shade, so it carries the topic and the
    // opening words rather than an id nobody can act on.
    const gist = message.replace(/\s+/g, " ").trim().slice(0, 60);
    const subject = gist
      ? `Yap feedback · ${topic} · ${gist}${message.length > 60 ? "…" : ""}`
      : `Yap feedback · ${topic} · ${seconds}s voice message`;

    const from = [d.email, d.platform, d.appVersion, d.device, d.osVersion]
      .filter(Boolean)
      .join(" · ");

    const voiceText = voice
      ? voice.url
        ? `\nVoice message (${seconds}s): ${voice.url}\n(link works for 7 days)`
        : `\nVoice message (${seconds}s) attached to the report, but no link could be made: ${voice.reason}`
      : "";

    const text = [
      topic,
      "",
      message || "(no typed message)",
      voiceText,
      "",
      "—",
      from,
      DASHBOARD,
    ].join("\n");

    const html = `
<div style="font:15px/1.55 -apple-system,Segoe UI,Roboto,sans-serif;color:#111;max-width:640px">
  <p style="margin:0 0 4px;font-size:13px;color:#666">${esc(topic)}</p>
  ${message
    ? `<div style="white-space:pre-wrap;margin:0 0 18px">${esc(message)}</div>`
    : `<p style="margin:0 0 18px;color:#666"><em>No typed message.</em></p>`}
  ${voice
    ? voice.url
      ? `<p style="margin:0 0 18px"><a href="${esc(voice.url)}" style="color:#0a58ca">▶ Play the ${seconds}s voice message</a>
         <span style="color:#888;font-size:13px">(link works for 7 days)</span></p>`
      : `<p style="margin:0 0 18px;color:#a33">A ${seconds}s voice message came with this, but no link could be made: ${esc(voice.reason ?? "")}</p>`
    : ""}
  <hr style="border:0;border-top:1px solid #e5e5e5;margin:18px 0">
  <p style="margin:0 0 6px;font-size:13px;color:#666">${esc(from)}</p>
  <p style="margin:0;font-size:13px"><a href="${DASHBOARD}" style="color:#0a58ca">Open it in the dashboard</a></p>
</div>`.trim();

    // Reply-To is the point of the whole thing: hitting reply answers the tester directly,
    // rather than opening the dashboard to find an address to copy. Only set when the address
    // looks like one, so a malformed token can't turn into a rejected send.
    const replyTo =
      typeof d.email === "string" && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(d.email) ? d.email : undefined;

    try {
      const res = await fetch("https://api.resend.com/emails", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${RESEND_API_KEY.value()}`,
          "Content-Type": "application/json",
          // Resend deduplicates on this, so a retried invocation of the same trigger sends
          // one email rather than one per attempt.
          "Idempotency-Key": `feedback-${id}`,
        },
        body: JSON.stringify({
          from: FROM,
          to: [TO],
          subject,
          text,
          html,
          ...(replyTo ? { reply_to: replyTo } : {}),
        }),
      });
      if (!res.ok) {
        console.error(`feedback notify ${id}: resend ${res.status} ${await res.text()}`);
        return;
      }
      console.log(`feedback notify ${id}: sent`);
    } catch (e: any) {
      console.error(`feedback notify ${id}: ${e?.message ?? e}`);
    }
  },
);

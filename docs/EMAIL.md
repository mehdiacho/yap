# help@ and dev@greenforrest.app

Free inbound email on the domain, forwarded to Gmail. No Google Workspace, no monthly fee.

Note the spelling: **greenforrest**, two r's — matching the Green Forrest developer name and the
`app.greenforrest.yap` package. `greenforest.app` with one r is somebody else's domain.

## Status: live as of 2026-08-31

Cloudflare Email Routing is enabled on the zone and both addresses forward. Set up over the API
with Wrangler's own OAuth token, which carries `email_routing:write` — no Global API Key and no
DNS scope needed.

| | |
| --- | --- |
| Zone | `greenforrest.app` (`904728d053fe263a26fed17a2a5c237c`) |
| Routing | `enabled: true`, `status: ready` |
| Destination | `mehdiacho@gmail.com` — already verified, no confirmation click needed |
| `help@greenforrest.app` | → forwards to Gmail (rule `328630fc…`) |
| `dev@greenforrest.app` | → forwards to Gmail (rule `b212cba7…`) |
| Catch-all | **off**, action `drop` |

Cloudflare wrote the records it needs, and they are live in public DNS:

```
MX   34 route1.mx.cloudflare.net
MX   61 route2.mx.cloudflare.net
MX   89 route3.mx.cloudflare.net
TXT  v=spf1 include:_spf.mx.cloudflare.net ~all
```

Catch-all stays off deliberately. On a domain that ends up in a public Play listing it collects
spam addressed to every name a scraper can invent, all of it landing in the same Gmail inbox as
the real mail.

## The one step left, and it is yours

**Gmail filters, for the labelled inboxes.** Cloudflare forwards each message with the original
recipient intact, so Gmail can sort on it — this is the part people assume needs Workspace, and
it does not.

Gmail → Settings → Filters and Blocked Addresses → Create a new filter → **To:**
`help@greenforrest.app` → Create filter → **Apply the label:** `help` (create it), and tick
**Never send it to Spam**. Repeat for `dev@`.

The spam tick is not optional in practice. Forwarded mail arrives with a mismatch between the
envelope sender and the forwarding server, which is exactly the pattern Gmail's filter is
suspicious of.

Send a message from an unrelated address to `help@greenforrest.app` to confirm before the
address goes anywhere public.

## What this does not give you

**Sending.** Email Routing is inbound only. Reply to a `help@` message from Gmail and it goes out
as `mehdiacho@gmail.com` — which rather undoes the point of a support address, because the person
wrote to `help@greenforrest.app` and hears back from a stranger's Gmail.

Making Gmail send as the address needs an SMTP relay for "Send mail as" to authenticate against.
Free tiers that work: Brevo (a few hundred a day), Resend, MailerSend. Configure once in Gmail →
Settings → Accounts → Send mail as → Add another email address.

**Do this before the address appears on the Play listing.** For a handful of tester emails,
replies from a Gmail address are survivable. On a published support address they are not.

Cloudflare Email *Sending* is a separate product and the Wrangler token has `email_sending:write`
too, but it sends from Workers — it is not an SMTP relay Gmail can authenticate against, so it
does not solve this.

## Related

- The apex still returns **525** (Cloudflare reaches the edge, origin TLS handshake fails). That
  is unrelated to mail — MX routing does not touch the origin — and it is tracked as Finding 6 in
  `X:\ai.tools\SECURITY-FINDINGS.md`. Diagnosing it needs DNS or zone-settings scope, which the
  Wrangler token does not have.
- The zone lives in the Cloudflare account covered by that same findings file. The compromise
  there is contained and resolved; the outstanding item is rolling the Global API Key, due
  2026-09-05.

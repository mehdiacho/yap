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

## Sending as help@ — Resend

Registered in Resend 2026-09-01 (team Green Forrest), region **eu-west-1**, domain id
`66818463-749b-4210-9c61-b2c9040934ea`. DNS records added to Cloudflare the same day; verification
is asynchronous on Resend's side.

Deliberately **sending only, receiving disabled**. Inbound stays with Cloudflare Email Routing,
which already works; letting Resend take receiving too would put two services in a fight over the
apex MX records.

`eu-west-1` because mail from Southern Africa routes through Europe in practice — Ireland is the
nearest of the four Resend regions that traffic actually passes near. Not `us-east-1`, which is the
default and the furthest.

### The records (added 2026-09-01)

| Type | Name | Value | Priority |
| --- | --- | --- | --- |
| TXT | `resend._domainkey` | `p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDCP4suI38Xn9Nfl6Z5vh4QuZQ3xBo8qv3T6+vO4bWWrkhp58QYCa1pOdGivZz+LwaAmcaGLhEwYNrvjE1aeXVbLK3hBzETtr3k3cEMMUZMK/Dda84dN73GzZdJ9i2SBtuC8ndcjcXvvYxLzkltxAEVgk2tfica9m+akth8k8G4YQIDAQAB` | — |
| MX | `send` | `feedback-smtp.eu-west-1.amazonses.com` | 10 |
| TXT | `send` | `v=spf1 include:amazonses.com ~all` | — |
| TXT | `_dmarc` | `v=DMARC1; p=none; rua=mailto:dev@greenforrest.app` | — |

DMARC is `p=none` — it reports and rejects nothing, which is the only sane opening position on a
domain that has never sent mail. Tighten to `quarantine` once the reports show only Resend.

**None of these touch the apex**, which is the point. The SPF and MX sit on the `send` subdomain
(Resend's default Return-Path), so the apex keeps Cloudflare Email Routing's own
`v=spf1 include:_spf.mx.cloudflare.net ~all` and its `route1/2/3.mx.cloudflare.net` records
untouched. Do **not** merge the two SPF records or put Resend's on the apex — inbound forwarding and
outbound sending are separate paths and each wants its own.

Nothing here is proxied; TXT and MX have no proxy toggle.

Added with the Cloudflare Global API Key, which authenticates as `X-Auth-Email` + `X-Auth-Key` —
**not** as a Bearer token, which returns 401 and reads like an invalid credential rather than a
wrong header. The `cfk_` prefix is part of the value and must not be stripped. Wrangler's OAuth
token cannot do this: it has no `dns_records` scope.

### Then: Gmail "Send mail as"

Create an **API key in the Resend dashboard** — not via a tool, so the secret never lands in a chat
transcript. That is not a hypothetical worry here: the Cloudflare Global API Key is already sitting
in one, and it is the reason that key is being rolled.

Gmail → Settings → Accounts and Import → Send mail as → Add another email address:

| | |
| --- | --- |
| Address | `help@greenforrest.app` |
| Treat as an alias | unticked |
| SMTP server | `smtp.resend.com` |
| Port | `587` (TLS) |
| Username | `resend` |
| Password | the Resend API key |

Gmail sends a confirmation code to `help@greenforrest.app` — which Cloudflare forwards straight to
the same inbox, so the loop closes without any extra setup.

Worth adding once sending works: a **DMARC** record at `_dmarc`, starting at
`v=DMARC1; p=none; rua=mailto:dev@greenforrest.app`. `p=none` observes without rejecting anything,
which is the right first move on a domain that has never sent mail.

## The apex 525, explained

Unrelated to mail, but found while reading the zone. The apex, `www` and a **wildcard** `*` are all
proxied A records pointing at `91.195.240.94` — a Sedo domain-parking address. Cloudflare accepts
the request at its edge, tries to reach that origin over TLS, and the handshake fails. Hence 525 on
`greenforrest.app`, `www.` and `app.` — the last one only because the wildcard catches it.

That closes Finding 6 in `X:i.tools\SECURITY-FINDINGS.md`: leftover registrar parking, not an
attack. Nothing to fix until the domain actually serves something.

One thing worth deciding separately: a **proxied wildcard** means every conceivable hostname under
the domain answers on Cloudflare's edge. That is not hostile, but it is what let the earlier hijack
serve redirects on `app.` and `www.` without either name ever being created deliberately. Narrowing
it to the hostnames actually in use would be a cheap improvement.

## Related

- The apex still returns **525** (Cloudflare reaches the edge, origin TLS handshake fails). That
  is unrelated to mail — MX routing does not touch the origin — and it is tracked as Finding 6 in
  `X:\ai.tools\SECURITY-FINDINGS.md`. Diagnosing it needs DNS or zone-settings scope, which the
  Wrangler token does not have.
- The zone lives in the Cloudflare account covered by that same findings file. The compromise
  there is contained and resolved; the outstanding item is rolling the Global API Key, due
  2026-09-05.

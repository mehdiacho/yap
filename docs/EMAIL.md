# help@ and dev@greenforest.app

Free inbound email on the domain, landing in Gmail, in labelled inboxes. No Google Workspace,
no monthly fee.

## What is true today

`greenforest.app` is **not on Cloudflare**. Its authoritative nameservers are Spaceship's:

```
launch1.spaceship.net
launch2.spaceship.net
```

There are **no MX records at all**, so mail to anything `@greenforest.app` currently bounces.

That matters because Cloudflare Email Routing works by owning the domain's MX records, and
Cloudflare can only write those if it is the authoritative DNS for the zone. There is no
partial or CNAME-only setup for it. So the first step is not "turn on Email Routing" — it is
moving DNS to Cloudflare.

Moving nameservers is the one genuinely disruptive step here. Nothing is currently served
from this domain, so there is nothing to break; do it before anything is.

## Steps

Roughly 20 minutes of work plus waiting for nameservers to propagate.

**1. Add the zone to Cloudflare.**
Cloudflare dashboard → Add a domain → `greenforest.app` → **Free** plan. Cloudflare scans the
existing DNS and imports what it finds. Check the imported records against Spaceship's before
continuing — the scan is best-effort, not authoritative. There is one TXT record
(`460df84a-…`, a verification token) and nothing else, so this should be quick.

**2. Point the registrar at Cloudflare.**
Cloudflare gives two nameservers. In the Spaceship dashboard, replace the nameservers on
`greenforest.app` with those. Propagation is usually under an hour; Cloudflare emails when
the zone goes active. Email Routing cannot be enabled until it does.

**3. Enable Email Routing.**
Cloudflare dashboard → the zone → **Email → Email Routing → Get started**. Cloudflare offers
to add the MX and SPF records it needs; accept. This is what makes the domain able to receive
mail at all.

**4. Verify the destination.**
Add `mehdiacho@gmail.com` as a destination address. Cloudflare sends it a confirmation link.
Nothing forwards until that link is clicked — this is the step that silently blocks people
who assume it worked.

**5. Create the routes.**

| Address | Action | Destination |
| --- | --- | --- |
| `help@greenforest.app` | Send to an email | `mehdiacho@gmail.com` |
| `dev@greenforest.app` | Send to an email | `mehdiacho@gmail.com` |

Leave **catch-all** off, or set it to Drop. A catch-all on a domain that exists publicly
collects spam addressed to every name a scraper can invent, and all of it lands in the same
Gmail inbox as the real mail.

**6. Get the labelled-inbox behaviour in Gmail.**
This is the part people assume needs Workspace. It does not. Cloudflare forwards the message
with the original recipient intact, so Gmail can filter on it:

Gmail → Settings → Filters and Blocked Addresses → Create a new filter → **To:**
`help@greenforest.app` → Create filter → **Apply the label:** `help` (create it), and tick
**Never send it to Spam**. Repeat for `dev@`.

The "Never send it to Spam" tick is not optional in practice. Forwarded mail arrives with a
mismatch between the envelope sender and the forwarding server, and Gmail's spam filter is
suspicious of exactly that pattern.

## What this does not give you

**Sending.** Email Routing is inbound only — Cloudflare will forward mail to you, and cannot
send mail as you. Reply to a `help@` message from Gmail and it goes out as
`mehdiacho@gmail.com`, which rather undoes the point of having a support address.

Making Gmail send as `help@greenforest.app` needs an SMTP relay for Gmail's "Send mail as" to
authenticate against. The free options are transactional-mail providers on their free tiers
(Brevo allows a few hundred a day, Resend and MailerSend have similar), configured once in
Gmail → Settings → Accounts → Send mail as → Add another email address. That is a separate
piece of setup and it is worth doing before the support address appears anywhere public.

Until then: forwarding works, replies come from the Gmail address. For a handful of tester
emails that is survivable. For a support address printed in a Play listing it is not, because
the reply address will not match the one people wrote to.

## Why not the registrar's own forwarding

Spaceship offers email forwarding on some plans, and if it is free on this one it would skip
steps 1 and 2 entirely. The reason to prefer Cloudflare anyway is that the DNS ends up
somewhere you will want it regardless — the moment this domain serves a landing page, a
status page, or the admin console on a real hostname, it needs to be on a DNS provider you
control properly. Doing that move now, while nothing depends on the domain, is cheaper than
doing it later.

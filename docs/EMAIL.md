# help@ and dev@greenforrest.app

Free inbound email on the domain, landing in Gmail, in labelled inboxes. No Google Workspace,
no monthly fee.

Note the spelling: **greenforrest**, two r's, matching the developer name Green Forrest and
the Android package `app.greenforrest.yap`.

## What is true today

The zone is already on Cloudflare — its authoritative nameservers are `jose.ns.cloudflare.com`
and `dee.ns.cloudflare.com`. Nothing has to be migrated.

There are **no MX records and no TXT records**, so mail to anything `@greenforrest.app`
currently bounces. Email Routing has not been switched on; that is the only thing missing.

## Steps

Ten minutes, all of it in two dashboards.

**1. Enable Email Routing.**
Cloudflare dashboard → `greenforrest.app` → **Email → Email Routing → Get started**. It offers
to add the MX and SPF records it needs. Accept — that is what makes the domain able to receive
mail at all, and it is why there are no MX records right now.

**2. Verify the destination.**
Add `mehdiacho@gmail.com` as a destination address. Cloudflare emails it a confirmation link.
**Nothing forwards until that link is clicked** — this is the step that silently defeats people
who assume the routes are live.

**3. Create the routes.**

| Address | Action | Destination |
| --- | --- | --- |
| `help@greenforrest.app` | Send to an email | `mehdiacho@gmail.com` |
| `dev@greenforrest.app` | Send to an email | `mehdiacho@gmail.com` |

Leave **catch-all** off, or set it to Drop. A catch-all on a domain that appears in a public
Play listing collects spam addressed to every name a scraper can invent, and all of it lands
in the same Gmail inbox as the real mail.

**4. Get the labelled-inbox behaviour in Gmail.**
This is the part people assume needs Workspace. It does not. Cloudflare forwards the message
with the original recipient intact, so Gmail can filter on it:

Gmail → Settings → Filters and Blocked Addresses → Create a new filter → **To:**
`help@greenforrest.app` → Create filter → **Apply the label:** `help` (create it), and tick
**Never send it to Spam**. Repeat for `dev@`.

The "Never send it to Spam" tick is not optional in practice. Forwarded mail arrives with a
mismatch between the envelope sender and the forwarding server, and Gmail's spam filter is
suspicious of exactly that pattern.

**5. Send yourself a test** from an unrelated address to `help@greenforrest.app` before putting
it anywhere public. MX propagation is quick but not instant.

## What this does not give you

**Sending.** Email Routing is inbound only — Cloudflare will forward mail to you, and cannot
send mail as you. Reply to a `help@` message from Gmail and it goes out as
`mehdiacho@gmail.com`, which rather undoes the point of having a support address.

Making Gmail send as `help@greenforrest.app` needs an SMTP relay for Gmail's "Send mail as" to
authenticate against. The free options are transactional-mail providers on their free tiers
(Brevo allows a few hundred a day; Resend and MailerSend are comparable), configured once in
Gmail → Settings → Accounts → Send mail as → Add another email address.

Do that before the address goes on the Play listing. For a handful of tester emails, replies
arriving from a Gmail address are survivable. On a published support address they are not —
the person wrote to `help@greenforrest.app` and gets a reply from a stranger's Gmail.

## Worth doing while you are in there

The zone is on Cloudflare and serving nothing. The admin console currently lives at
`yap-mehdiacho.web.app`, which is a URL nobody can be told over the phone. Firebase Hosting
takes a custom domain in a few minutes, and `admin.greenforrest.app` would also give the Play
listing's privacy policy a permanent home that does not name the Firebase project.

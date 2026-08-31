# Hosting and domains

Two Firebase Hosting sites live in the `yap-mehdiacho` project, and they serve different things
from different repositories.

| Site | Serves | From | Custom domain |
|---|---|---|---|
| `yap-mehdiacho` (default) | The admin dashboard, plus the two Play-listing legal pages | **this repo**, `admin/dist` | `admin.yap.greenforrest.app` |
| `yap-desktop` | The desktop web app | `X:\Projects\yap-desktop` | `yap.greenforrest.app` |

Both keep their `*.web.app` addresses as well; adding a custom domain does not take the default one
away, and the Play listing still points at `yap-mehdiacho.web.app`.

## `firebase.json` names its site explicitly

The hosting block in this repo carries `"site": "yap-mehdiacho"`. With two sites in one project and
an unqualified hosting config, a deploy resolves to whichever site the CLI decides is the default —
and a deploy from here that landed on `yap-desktop` would replace the desktop app with the admin
console. Naming the site makes that impossible rather than unlikely.

## The DNS records must stay DNS-only

Both custom domains are `CNAME` records in the `greenforrest.app` Cloudflare zone:

| Name | Type | Value | Proxy |
|---|---|---|---|
| `yap` | CNAME | `yap-desktop.web.app` | **off (grey cloud)** |
| `admin.yap` | CNAME | `yap-mehdiacho.web.app` | **off (grey cloud)** |

**Turning the orange cloud on breaks them.** Proxied, Cloudflare answers with its own addresses,
Firebase's ownership check sees something other than its CNAME, and certificate provisioning stops —
either failing to issue or failing to renew ninety days later, which is the worse version because it
happens silently long after the change. Firebase terminates TLS for these names itself; there is
nothing for Cloudflare's proxy to add.

The records carry a comment in Cloudflare saying so.

### They also override the wildcard

The zone has a proxied wildcard `*` A record pointing at parked-domain infrastructure. Before these
records existed, `yap.greenforrest.app` and `admin.yap.greenforrest.app` both resolved through that
wildcard and returned a 525 — Cloudflare reaching an origin whose TLS handshake fails. An exact-match
record beats a wildcard, so creating the CNAMEs fixed those names. Every *other* name in the zone
still resolves to the parking origin and still 525s; that is expected, not a fault.

## The Play listing URLs

`https://yap-mehdiacho.web.app/privacy.html` and `/delete-account.html` are what is filed with Play,
and they are served out of `admin/public/` in this repo. They keep working: the `.web.app` address is
permanent.

They are worth moving eventually. A privacy policy that lives on the *admin console's* domain is a
strange place for a public legal document, and the store listing's "Website" field currently points
at a login screen for a dashboard nobody but the developer can enter. `yap.greenforrest.app` is the
right home for both, but the pages would have to move to the desktop repo and the Play listing edited
to match — a change to make deliberately, not in the middle of a review.

## `yap.mehdiacho.tech` is retired

`yap.mehdiacho.tech` was the desktop app's original custom domain, attached to `yap-desktop` on
2026-07-29. It was detached and its CNAME deleted on 2026-09-01, once `yap.greenforrest.app` was
confirmed serving the same site over HTTPS. `yap-desktop` now has exactly one custom domain.

Nothing in either repository referenced the old hostname, so retiring it cost nothing findable by
searching. If it turns out to be linked from somewhere external, the fix is a Cloudflare redirect
rule on the `mehdiacho.tech` zone — which needs that record's proxy turned **on**, the opposite of
the rule above, because a redirect rule only runs on traffic that reaches Cloudflare's edge. Putting
the record back is a two-minute job either way.

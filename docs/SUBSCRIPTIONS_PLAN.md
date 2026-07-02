# Yap — Subscriptions, entitlements, promo codes & admin tool (working plan)

Agreed direction: **no separate API server** — everything is additional Firebase callable
functions in the existing `functions/` codebase (same deploy, africa-south1). The admin web
tool is a small static page on Firebase Hosting calling admin-only functions.

## 1. Data model (Firestore)

```
entitlements/{uid}        ← admin/server-written ONLY (top-level, like usage/{uid})
  plan: "free" | "pro"            (byok tiers added when BYOK ships)
  source: "default" | "promo" | "admin" | "play_billing"
  promoCode?: string
  activatedAt: number
  expiresAt?: number              (promo/admin grants can expire → auto-revert to free)
  byokUnlocked: boolean

usage/{uid}               ← exists today: { cycle: "YYYY-MM", secondsUsed }

promos/{CODE}             ← admin-written
  grant: { plan: "pro", days: 30 } | { byokUnlock: true }
  criteria: {                     (all optional; all present must pass)
    uid?: string                  (code locked to one account)
    accountAgeMaxDays?: number    ("accounts younger than N")
    accountAgeMinDays?: number    ("accounts older than N")
  }
  maxRedemptions: number
  redeemedCount: number
  active: boolean
  expiresAt?: number
  redemptions/{uid}: { at }       (subcollection → enforces one-per-user)
```

**Security rules:** `entitlements/{uid}` and `usage/{uid}` get `allow read: if auth.uid == uid`
(so the app can render plan + meter) and **no client write**. `promos` has no client rule at all.

## 2. Tier enforcement (in transcribeSession)

Per-plan monthly caps replace the flat dev cap:
- **free**: 30 min/month (spec)
- **pro**: 1,800 min/month (30 h, spec)
- expired entitlement → treated as free
- (BYOK: skips managed quota — future block)

Server reads `entitlements/{uid}` (missing doc = free) before the usage transaction.
Over-cap → `resource-exhausted` with a clear message; the app shows an upsell/redeem prompt.

## 3. New callable functions

| Function | Auth | Purpose |
|---|---|---|
| `getAccountStatus` | user | Returns { plan, expiresAt, minutesUsed, minutesCap, cycle } for the UI |
| `redeemPromo` | user | Validates code (active, not expired, redemptions left, criteria vs the caller's account incl. creation-date age), transactionally records redemption + writes entitlement |
| `adminSearchUser` | admin | Look up user by email/uid → profile + plan + usage |
| `adminSetEntitlement` | admin | Grant/revoke a plan for any uid (this is how we test tier switching without payments) |
| `adminCreatePromo` | admin | Create per-account / general / criteria-based codes |
| `adminListPromos` | admin | List codes + redemption counts |

**Admin = custom claim** `admin: true` set once on the owner's Firebase user (server-side
script). Every admin function checks `request.auth.token.admin === true`.

## 4. App UI (per the design kit)

- **Subscription & Usage screen** (Settings → Subscription & Usage):
  plan card (Free/Pro + what's included), **usage meter** (minutes used / cap, cycle reset),
  **Redeem code** field, upgrade CTA (disabled "coming soon" until Play Billing).
- **Quota-hit handling**: transcription failure from `resource-exhausted` shows the designed
  "out of minutes" sheet (upgrade / redeem) instead of generic FAILED.
- Account card already exists (auth block).

## 5. Admin web tool (Firebase Hosting)

Single static page (plain HTML/JS + Firebase SDK; no framework): sign in with Google →
only works for admin-claim users → tabs: **Users** (search email → see plan/usage → set plan),
**Promo codes** (create: code text or auto-generated, grant, criteria, max uses, expiry; list
with redemption counts). Hosted at `yap-mehdiacho.web.app`.

## 6. Payments (deferred, discussed)

- **Google Play Billing is mandatory** for in-app digital subscriptions on Play Store
  (Stripe et al. not allowed for digital goods in-app). Integrate when the app enters
  Play Console (internal testing track); **license-tester accounts make test purchases free**,
  which solves "I can't keep paying to test tiers" for the Billing phase.
- Until then: promo codes + adminSetEntitlement are the entitlement source — they exercise the
  exact same `entitlements/{uid}` path Billing will later write to, so nothing gets rebuilt.

## 7. Build order

1. Backend: entitlements + per-plan caps + `getAccountStatus` + `redeemPromo` + admin functions + rules; set admin claim on owner.
2. App: Subscription & Usage screen + redeem flow + quota-hit upsell.
3. Admin web tool on Hosting.
4. (Later block) Play Billing; BYOK tiers.

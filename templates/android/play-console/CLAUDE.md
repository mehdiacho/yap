# Play Console — per-app setup

Everything Play Console makes you fill out for a new app, and the two legal pages you must host
first.

## Files
- **[`PLAY_CONSOLE_SETUP_TEMPLATE.md`](PLAY_CONSOLE_SETUP_TEMPLATE.md)** — the walkthrough. Every
  content/policy/data-safety/monetization section, each with a reusable default + reasoning and a
  **PER APP** blank. Sections that genuinely change per app are marked **⚠ RE-DECIDE PER APP**
  (content rating, data-safety matrix, financial features, health, target audience) — copy the
  *approach*, not the answer.
- **`privacy.template.html`** — hostable privacy policy. Replace `{{PLACEHOLDERS}}`, remove the
  feature bullets that don't apply, deploy, use its URL in Play's Privacy policy field + Data safety.
- **`delete-account.template.html`** — hostable account-deletion page. Required by Data safety once
  the app declares accounts. Same placeholder-swap flow.

## How to use in a session
> "Set up Play Console for `{{APP}}`. Use `templates/android/play-console/` — fill the header,
> host the two HTML templates on Firebase Hosting, then walk the setup template top-to-bottom.
> Stop at each ⚠ RE-DECIDE section and ask me about this app's features."

## Placeholders used by the HTML templates
`{{APP_NAME}}` · `{{DEVELOPER}}` · `{{PACKAGE}}` · `{{SUPPORT_EMAIL}}` · `{{HOSTING_DOMAIN}}`
(e.g. `myapp.web.app`) · `{{REGION}}` (e.g. `europe-west1`) · `{{EFFECTIVE_DATE}}`.

## Worked example (filled-in instance)
This project's live Yap versions: `admin/privacy.html`, `admin/delete-account.html`, and
`docs/PLAY_CONSOLE_ANSWERS.md` (Yap's actual answers). Look there to see a template instantiated.

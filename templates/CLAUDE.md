# Templates library

Reusable, project-agnostic templates, skills, and boilerplate — extracted from real work so the
same thing never has to be figured out twice. **Point any session at this folder** ("check the
templates in `templates/` to set up X") and it can instantiate the relevant piece for a new app.

This folder is intentionally **one level above** any single app's code — it spans stacks. Each
subfolder owns a domain and carries its own `CLAUDE.md` that takes over from this one; drill into
the folder closest to your task.

## What's here

| Folder | Use it when you're… | Stack |
|---|---|---|
| [`android/`](android/CLAUDE.md) | shipping an Android app: Play Console setup, release CI/CD, signing, legal pages, store assets | Android / Kotlin / Play |
| [`design/`](design/CLAUDE.md) | producing or handing off UI designs so they build pixel-accurately in one pass | any UI framework |

## Conventions
- **Placeholders** are written `{{LIKE_THIS}}` (or noted inline). Instantiate a template = copy it
  out, replace every placeholder, delete the guidance comments.
- **Templates are copies, not links.** Copy the file into the new project and edit; don't reference
  it in place (this folder may not travel with that project).
- **Worked examples** point back at this project (Yap) so you can see a filled-in instance next to
  the blank template.
- Add new reusables here whenever something is done a second time. One `CLAUDE.md` per folder,
  updated to list what the folder holds.

# Store assets

Generate the two required Play graphics from a brand, so you're not hand-exporting in an image editor.

## File
- **`generate_play_assets.py`** — Pillow script. Edit the `CONFIG` block (gradient colors, wordmark,
  tagline) and replace `draw_glyph()` with the real brand mark, then run:
  ```
  pip install pillow
  python generate_play_assets.py
  ```
  Outputs `out/icon-512.png` (512×512, full-bleed — Play masks corners) and
  `out/feature-1024x500.png`.

## Still needed by hand (Play requires, not generated here)
- **≥4 phone screenshots**, each ≥1080px on the short side. Capture real app screens on-device or
  via emulator. Tablet/Chromebook/XR screenshots: optional, skip.

## Reusable techniques baked in
- 3-stop diagonal gradient generator.
- **Draw semi-transparent glyph parts on a transparent overlay, then `alpha_composite`** — drawing
  directly with alpha onto the base *replaces* pixels instead of blending (a bug we hit).
- Cross-platform font fallback (Segoe UI → Arial → DejaVu → PIL default).

import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `public/` is copied to the output root verbatim. That is load-bearing here and not a
// default worth changing: privacy.html and delete-account.html are the URLs submitted to the
// Play listing, and they have to keep resolving at /privacy.html and /delete-account.html
// after this app replaced the static site that used to serve them.
export default defineConfig({
  plugins: [react()],
  build: { outDir: "dist", emptyOutDir: true },
});

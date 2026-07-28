import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Port is fixed because the Tauri shell points its webview at it in dev.
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    port: 5273,
    strictPort: true,
  },
  build: {
    outDir: "dist",
    sourcemap: true,
  },
});

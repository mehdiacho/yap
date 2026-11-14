/*
 * Shell adapter — the portability boundary.
 *
 * RULE: nothing under src/features or src/components may import a shell SDK
 * (@tauri-apps/*, electron, node APIs) directly. They import this module only.
 * That keeps the React core a plain web app, so it runs in `vite dev`, can be
 * wrapped by a different shell, or ship as the web companion, without edits.
 *
 * Adding a native capability means: extend `Shell`, implement it for each
 * shell, and give the web build an honest fallback (or a no-op that reports
 * `false` so the UI can hide the affordance).
 */

export interface Shell {
  /** Which host we're running under — for telemetry and UI affordances. */
  readonly name: "web" | "tauri" | "electron";
  /** True when running inside a native shell (custom titlebar, tray, hotkeys). */
  readonly isDesktop: boolean;

  minimize(): Promise<void>;
  toggleMaximize(): Promise<void>;
  close(): Promise<void>;

  /** Open a URL in the user's real browser, never in the app webview. */
  openExternal(url: string): Promise<void>;

  /** Save text to disk. Returns false if the user cancelled. */
  saveTextFile(suggestedName: string, contents: string): Promise<boolean>;
}

/** Browser fallback: window controls are no-ops, saving uses a download. */
const webShell: Shell = {
  name: "web",
  isDesktop: false,

  async minimize() {},
  async toggleMaximize() {},
  async close() {},

  async openExternal(url) {
    window.open(url, "_blank", "noopener,noreferrer");
  },

  async saveTextFile(suggestedName, contents) {
    const blob = new Blob([contents], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = suggestedName;
    a.click();
    URL.revokeObjectURL(url);
    return true;
  },
};

function isTauri(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

/*
 * Loaded lazily and through a non-literal specifier so the bundler does not try
 * to resolve @tauri-apps/api in the web build (where it isn't installed).
 * The Tauri shell is wired in src-tauri; see README "Adding the Tauri shell".
 */
async function loadTauriShell(): Promise<Shell> {
  const windowMod = "@tauri-apps/api/window";
  const shellMod = "@tauri-apps/plugin-shell";
  const dialogMod = "@tauri-apps/plugin-dialog";
  const fsMod = "@tauri-apps/plugin-fs";

  const [{ getCurrentWindow }, { open }, { save }, { writeTextFile }] = await Promise.all([
    import(/* @vite-ignore */ windowMod),
    import(/* @vite-ignore */ shellMod),
    import(/* @vite-ignore */ dialogMod),
    import(/* @vite-ignore */ fsMod),
  ]);

  const win = getCurrentWindow();
  return {
    name: "tauri",
    isDesktop: true,
    minimize: () => win.minimize(),
    toggleMaximize: () => win.toggleMaximize(),
    close: () => win.close(),
    openExternal: (url: string) => open(url),
    async saveTextFile(suggestedName: string, contents: string) {
      const path = await save({
        defaultPath: suggestedName,
        filters: [{ name: "Markdown", extensions: ["md"] }],
      });
      if (!path) return false;
      await writeTextFile(path, contents);
      return true;
    },
  };
}

let cached: Shell | null = null;

/** Resolve the active shell once; falls back to web if the native load fails. */
export async function getShell(): Promise<Shell> {
  if (cached) return cached;
  if (isTauri()) {
    try {
      cached = await loadTauriShell();
      return cached;
    } catch (err) {
      console.warn("Tauri shell failed to load; falling back to web behaviour.", err);
    }
  }
  cached = webShell;
  return cached;
}

/**
 * Synchronous best-guess for first render, before getShell() resolves.
 * Only use for layout decisions (e.g. whether to reserve titlebar height).
 */
export const shellIsDesktop = isTauri();

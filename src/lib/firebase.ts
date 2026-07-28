import { initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";
import { getFunctions } from "firebase/functions";
import { getStorage } from "firebase/storage";
import {
  initializeFirestore,
  persistentLocalCache,
  persistentMultipleTabManager,
} from "firebase/firestore";

/*
 * Firebase web config is not a secret — it ships in the JS bundle of every
 * Firebase web app. Access is gated by Firestore/Storage rules (per-user
 * subtree isolation, see firestore.rules in the yap repo), not by hiding this.
 * Values come from .env so the shell can point at an emulator later.
 */
const cfg = {
  apiKey: import.meta.env.VITE_FB_API_KEY,
  authDomain: import.meta.env.VITE_FB_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FB_PROJECT_ID,
  storageBucket: import.meta.env.VITE_FB_STORAGE_BUCKET,
  messagingSenderId: import.meta.env.VITE_FB_SENDER_ID,
  appId: import.meta.env.VITE_FB_APP_ID,
};

const missing = Object.entries(cfg)
  .filter(([, v]) => !v)
  .map(([k]) => k);
if (missing.length) {
  throw new Error(
    `Firebase config incomplete — missing ${missing.join(", ")}. Copy .env.example to .env.`,
  );
}

export const app = initializeApp(cfg);

/*
 * Persistent cache gives us the same local-first behaviour the Android client
 * gets from Firestore's offline persistence: the library renders from disk
 * instantly, then reconciles with the server. Multi-tab manager keeps the
 * dev browser and the Tauri webview from fighting over the same IndexedDB lease.
 */
export const db = initializeFirestore(app, {
  localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }),
});

export const auth = getAuth(app);
export const storage = getStorage(app);

/*
 * Region must match the deployed functions (africa-south1) — the default "us-central1"
 * would 404. Mirrors TranscriptionClient.kt in the Android app.
 */
export const functions = getFunctions(app, "africa-south1");

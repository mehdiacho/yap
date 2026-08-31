import { initializeApp } from "firebase/app";
import { getAuth, GoogleAuthProvider, signInWithPopup, signOut } from "firebase/auth";
import { getFunctions, httpsCallable } from "firebase/functions";

// Public client config — safe in the bundle. Nothing here grants access: every admin
// callable checks the `admin` custom claim on the caller's token, and the collections this
// dashboard reads have no client rule at all, so a stranger with this config can see nothing.
const app = initializeApp({
  projectId: "yap-mehdiacho",
  appId: "1:179392577570:web:dbe8bb3df15dbaf3fa0a7b",
  storageBucket: "yap-mehdiacho.firebasestorage.app",
  apiKey: "AIzaSyDB4Vp-cg930j73KzwvYyrUnNwgl5JYk0o",
  authDomain: "yap-mehdiacho.firebaseapp.com",
  messagingSenderId: "179392577570",
});

export const auth = getAuth(app);
const functions = getFunctions(app, "africa-south1");

export function signIn() {
  return signInWithPopup(auth, new GoogleAuthProvider());
}

export function leave() {
  return signOut(auth);
}

/**
 * Call a backend function and return its data.
 *
 * Unwrapping `.data` here rather than at each call site keeps the views free of Firebase
 * shapes, and re-throwing the message rather than the FunctionsError means a failure renders
 * as the sentence the backend wrote instead of "internal".
 */
export async function call(name, payload = {}) {
  try {
    const res = await httpsCallable(functions, name)(payload);
    return res.data;
  } catch (e) {
    throw new Error(e?.message || `${name} failed`);
  }
}

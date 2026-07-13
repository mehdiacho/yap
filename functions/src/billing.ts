import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { GoogleAuth } from "google-auth-library";

const REGION = "africa-south1";
const PACKAGE_NAME = "com.mehdiacho.yap";
const MONTH_MS = 32 * 86_400_000; // grace day over a calendar month
const YEAR_MS = 367 * 86_400_000;

// Product ids ↔ entitlements. Must match Play Console products and the app's BillingManager.
const SUBSCRIPTION_PLANS: Record<string, { plan: string; periodMs: number; byok?: boolean }> = {
  pro_monthly: { plan: "pro", periodMs: MONTH_MS },
  pro_yearly: { plan: "pro", periodMs: YEAR_MS },
  byok_storage_monthly: { plan: "byok", periodMs: MONTH_MS, byok: true },
};
const ONE_TIME_BYOK = "byok_unlock";

/**
 * Calls the Play Developer API as the Functions runtime service account (ADC — no key
 * file). SETUP (one-time, in Play Console → Users and permissions): invite
 * `yap-mehdiacho@appspot.gserviceaccount.com` and grant "View financial data" +
 * "Manage orders and subscriptions".
 */
async function playApi(path: string): Promise<any> {
  const auth = new GoogleAuth({
    scopes: ["https://www.googleapis.com/auth/androidpublisher"],
  });
  const client = await auth.getClient();
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}/${path}`;
  const res = await client.request({ url });
  return res.data;
}

function requireAuth(request: CallableRequest): string {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign-in required.");
  return request.auth.uid;
}

/**
 * verifyPlayPurchase — the app calls this right after Google Play reports a purchase.
 * We verify the token server-side with the Play Developer API, then grant the
 * entitlement. The client acknowledges only after this succeeds.
 *
 * Request:  { productId: string, purchaseToken: string }
 * Response: { ok: true, plan, byokUnlocked, expiresAt|null }
 */
export const verifyPlayPurchase = onCall(
  { region: REGION, timeoutSeconds: 60 },
  async (request) => {
    const uid = requireAuth(request);
    const productId = String(request.data?.productId ?? "");
    const purchaseToken = String(request.data?.purchaseToken ?? "");
    if (!productId || !purchaseToken) {
      throw new HttpsError("invalid-argument", "productId and purchaseToken required.");
    }

    const db = admin.firestore();

    // Replay guard — one grant per purchase token.
    const tokenId = Buffer.from(purchaseToken).toString("base64url").slice(0, 500);
    const tokenRef = db.collection("purchaseTokens").doc(tokenId);
    const seen = await tokenRef.get();
    if (seen.exists && seen.get("uid") !== uid) {
      throw new HttpsError("already-exists", "This purchase belongs to another account.");
    }

    const entRef = db.collection("entitlements").doc(uid);

    if (productId === ONE_TIME_BYOK) {
      // One-time in-app product.
      let purchase: any;
      try {
        purchase = await playApi(`purchases/products/${productId}/tokens/${purchaseToken}`);
      } catch (e: any) {
        console.error("play products.get failed", { message: e?.message });
        throw new HttpsError("permission-denied", "Purchase could not be verified with Google Play.");
      }
      if (purchase.purchaseState !== 0) {
        throw new HttpsError("failed-precondition", "Purchase is not in a completed state.");
      }
      await entRef.set({ byokUnlocked: true, updatedAt: Date.now() }, { merge: true });
      await tokenRef.set({ uid, productId, at: Date.now() });
      const ent = (await entRef.get()).data() ?? {};
      return { ok: true, plan: ent.plan ?? "free", byokUnlocked: true, expiresAt: ent.expiresAt ?? null };
    }

    const mapping = SUBSCRIPTION_PLANS[productId];
    if (!mapping) throw new HttpsError("invalid-argument", `Unknown product ${productId}.`);

    // Subscription — use subscriptionsv2 for the authoritative state.
    let sub: any;
    try {
      sub = await playApi(`purchases/subscriptionsv2/tokens/${purchaseToken}`);
    } catch (e: any) {
      console.error("play subscriptionsv2.get failed", { message: e?.message });
      throw new HttpsError("permission-denied", "Subscription could not be verified with Google Play.");
    }
    const state = String(sub.subscriptionState ?? "");
    const active = state === "SUBSCRIPTION_STATE_ACTIVE" || state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD";
    if (!active) {
      throw new HttpsError("failed-precondition", `Subscription is not active (${state}).`);
    }
    const lineExpiry = sub.lineItems?.[0]?.expiryTime
      ? new Date(sub.lineItems[0].expiryTime).getTime()
      : Date.now() + mapping.periodMs;

    await entRef.set(
      {
        plan: mapping.plan,
        expiresAt: lineExpiry,
        ...(mapping.byok ? { byokUnlocked: true } : {}),
        source: "play",
        productId,
        updatedAt: Date.now(),
      },
      { merge: true },
    );
    await tokenRef.set({ uid, productId, at: Date.now() });
    return {
      ok: true,
      plan: mapping.plan,
      byokUnlocked: mapping.byok === true || ((await entRef.get()).get("byokUnlocked") ?? false),
      expiresAt: lineExpiry,
    };
  },
);

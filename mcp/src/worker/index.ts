/**
 * The Yap MCP server as a Claude connector: a Cloudflare Worker that is both the OAuth
 * authorization server and the MCP resource at `/mcp`.
 *
 * `@cloudflare/workers-oauth-provider` implements the protocol — discovery metadata, the
 * 401 challenge, CIMD and DCR client registration, PKCE, token issuance, rotation and
 * revocation. This file supplies the two things it can't: who the person is (a Firebase
 * sign-in on the /authorize page) and what `/mcp` does with them (the same tools as the
 * local server).
 *
 * The grant's encrypted props hold the person's Firebase refresh token, so every tool call
 * reaches Firestore as that person and `firestore.rules` still decide what it can read —
 * exactly as the local server and the phone app do. There is no admin credential here.
 */
import {
  AuthorizationError,
  OAuthProvider,
  type AuthRequest,
  type OAuthHelpers,
} from "@cloudflare/workers-oauth-provider";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { CfWorkerJsonSchemaValidator } from "@modelcontextprotocol/sdk/validation/cfworker";

import { SessionStore } from "../firestore.js";
import { exchangeRefreshToken, RefreshTokenSource, SignInExpiredError } from "../token.js";
import { Library, registerTools, SERVER_INFO, SERVER_INSTRUCTIONS } from "../tools.js";
import { authorizePage, landingPage, messagePage } from "./page.js";

interface Env {
  OAUTH_KV: KVNamespace;
  OAUTH_PROVIDER: OAuthHelpers;
  /** The public Firebase web API key. A secret only to keep it out of the repo. */
  FIREBASE_API_KEY: string;
  FIREBASE_PROJECT_ID: string;
  FIREBASE_AUTH_DOMAIN: string;
  /** `https://yap-mcp.greenforrest.app` in production; the wrangler dev URL locally. */
  PUBLIC_ORIGIN: string;
}

/** What a grant carries. Encrypted at rest by the provider, keyed by the access token. */
interface Props {
  uid: string;
  refreshToken: string;
}

/** Stashed between showing /authorize and the sign-in coming back. */
interface Pending {
  request: AuthRequest;
  clientName: string;
}

const SCOPES = ["notes"];
const MAX_SCAN = 2000;
const PENDING_TTL_S = 600;
/**
 * `__Host-` makes the browser refuse the cookie unless it is Secure, Path=/ and has no
 * Domain — so it can never be set for, or read by, the other apps on greenforrest.app.
 */
const COOKIE = "__Host-yap_auth";

// ---------------------------------------------------------------------------------------
// /mcp

/**
 * Libraries by uid, so the ten-second scan cache and the hour-long ID token survive from
 * one request to the next within an isolate. Bounded, and dropped if the refresh token
 * changed (the person reconnected).
 */
const libraries = new Map<string, { refreshToken: string; lib: Library }>();

function libraryFor(p: Props, env: Env): Library {
  const hit = libraries.get(p.uid);
  if (hit && hit.refreshToken === p.refreshToken) {
    libraries.delete(p.uid);
    libraries.set(p.uid, hit); // most recently used goes last
    return hit.lib;
  }
  const tokens = new RefreshTokenSource(
    env.FIREBASE_API_KEY,
    p.refreshToken,
    "Disconnect the Yap connector in Claude's settings, then connect it again.",
  );
  const lib = new Library(new SessionStore(env.FIREBASE_PROJECT_ID, tokens), MAX_SCAN);
  libraries.set(p.uid, { refreshToken: p.refreshToken, lib });
  while (libraries.size > 20) libraries.delete(libraries.keys().next().value!);
  return lib;
}

const mcpHandler = {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    // Stateless: every POST is answered in full. There is no server-initiated stream to
    // hold open, and the spec allows 405 for GET when a server offers none.
    if (request.method !== "POST") {
      return new Response(null, { status: 405, headers: { Allow: "POST" } });
    }
    const props = (ctx as ExecutionContext & { props: Props }).props;
    const server = new McpServer(SERVER_INFO, {
      instructions: SERVER_INSTRUCTIONS,
      // The default validator compiles schemas with `new Function`, which Workers forbid.
      jsonSchemaValidator: new CfWorkerJsonSchemaValidator(),
    });
    registerTools(server, libraryFor(props, env));
    const transport = new WebStandardStreamableHTTPServerTransport({
      sessionIdGenerator: undefined,
      enableJsonResponse: true,
    });
    await server.connect(transport);
    return transport.handleRequest(request);
  },
};

// ---------------------------------------------------------------------------------------
// Everything else: the landing page and the sign-in

const PAGE_HEADERS = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "no-store",
  // A consent page must never be framed: clickjacking is the classic way to fake a yes.
  "Content-Security-Policy": "frame-ancestors 'none'",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
};

const html = (body: string, status = 200, extra: Record<string, string> = {}) =>
  new Response(body, { status, headers: { ...PAGE_HEADERS, ...extra } });

const jsonError = (status: number, error: string) =>
  Response.json({ error }, { status, headers: { "Cache-Control": "no-store" } });

function readCookie(request: Request, name: string): string | null {
  for (const part of (request.headers.get("Cookie") ?? "").split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return v.join("=");
  }
  return null;
}

async function showAuthorize(request: Request, env: Env): Promise<Response> {
  let oauthRequest: AuthRequest;
  try {
    oauthRequest = await env.OAUTH_PROVIDER.parseAuthRequest(request);
  } catch (error) {
    if (!(error instanceof AuthorizationError)) throw error;
    if (!error.redirectUri) {
      // Unknown client or unregistered redirect: never redirect anywhere, say so here.
      return html(messagePage("This connection link isn't valid", esc(error.description)), 400);
    }
    const redirect = new URL(error.redirectUri);
    redirect.searchParams.set("error", error.code);
    redirect.searchParams.set("error_description", error.description);
    if (error.state) redirect.searchParams.set("state", error.state);
    if (error.issuer) redirect.searchParams.set("iss", error.issuer);
    return Response.redirect(redirect.toString(), 302);
  }

  const client = await env.OAUTH_PROVIDER.lookupClient(oauthRequest.clientId);
  if (!client) return html(messagePage("Unknown app", "This app isn't registered with Yap."), 400);

  const nonce = crypto.randomUUID();
  const clientName = client.clientName?.trim() || "this app";
  const pending: Pending = { request: oauthRequest, clientName };
  await env.OAUTH_KV.put(`yap-pending:${nonce}`, JSON.stringify(pending), {
    expirationTtl: PENDING_TTL_S,
  });

  return html(
    authorizePage({
      clientName,
      redirectHost: new URL(oauthRequest.redirectUri).host,
      nonce,
      firebase: {
        apiKey: env.FIREBASE_API_KEY,
        authDomain: env.FIREBASE_AUTH_DOMAIN,
        projectId: env.FIREBASE_PROJECT_ID,
      },
    }),
    200,
    {
      "Set-Cookie": `${COOKIE}=${nonce}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=${PENDING_TTL_S}`,
    },
  );
}

/**
 * The page posts the refresh token from its Firebase sign-in. Three checks tie that post to
 * the page this Worker just served, in this browser: the Origin header, the nonce in the
 * body, and the same nonce in a host-only cookie.
 */
async function completeAuthorize(request: Request, env: Env): Promise<Response> {
  if (request.headers.get("Origin") !== env.PUBLIC_ORIGIN) {
    return jsonError(403, "That request didn't come from the Yap sign-in page.");
  }

  let input: { nonce?: unknown; refreshToken?: unknown };
  try {
    input = await request.json();
  } catch {
    return jsonError(400, "The sign-in page sent something unreadable. Reload it and try again.");
  }
  const nonce = typeof input.nonce === "string" ? input.nonce : "";
  const refreshToken = typeof input.refreshToken === "string" ? input.refreshToken : "";
  const expired = "This sign-in page has expired. Go back to Claude and connect again.";
  if (!nonce || !refreshToken || readCookie(request, COOKIE) !== nonce) {
    return jsonError(400, expired);
  }

  const key = `yap-pending:${nonce}`;
  const pending = await env.OAUTH_KV.get<Pending>(key, "json");
  if (!pending) return jsonError(400, expired);
  await env.OAUTH_KV.delete(key);

  let session;
  try {
    session = await exchangeRefreshToken(env.FIREBASE_API_KEY, refreshToken);
  } catch (e) {
    if (e instanceof SignInExpiredError) {
      return jsonError(401, "Yap couldn't confirm that sign-in. Try again.");
    }
    throw e;
  }
  if (session.provider === "anonymous") {
    return jsonError(
      403,
      "That's a guest account. Guest notes stay on your phone, so there's nothing to connect. " +
        "Sign in to Yap with Google or email in the app first.",
    );
  }

  const props: Props = { uid: session.uid, refreshToken };
  const { redirectTo } = await env.OAUTH_PROVIDER.completeAuthorization({
    request: pending.request,
    userId: session.uid,
    // Stored unencrypted, so nothing personal: no email, no name.
    metadata: { clientName: pending.clientName },
    scope: SCOPES,
    props,
  });

  return Response.json(
    { redirectTo },
    {
      headers: {
        "Cache-Control": "no-store",
        "Set-Cookie": `${COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0`,
      },
    },
  );
}

function esc(s: string): string {
  return s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

const defaultHandler = {
  async fetch(request: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/authorize" && request.method === "GET") return showAuthorize(request, env);
    if (pathname === "/authorize/complete" && request.method === "POST") {
      return completeAuthorize(request, env);
    }
    if (pathname === "/" && request.method === "GET") return html(landingPage(env.PUBLIC_ORIGIN));
    return new Response("Not found", { status: 404 });
  },
};

// ---------------------------------------------------------------------------------------

/**
 * Built on first request rather than at module load, because the resource URL has to match
 * the origin the Worker is actually served from, and only `env` knows that.
 */
function buildProvider(origin: string): OAuthProvider<Env> {
  return new OAuthProvider<Env>({
    apiRoute: "/mcp",
    apiHandler: mcpHandler,
    defaultHandler,
    authorizeEndpoint: "/authorize",
    tokenEndpoint: "/token",
    // Claude prefers CIMD; DCR stays for clients that don't do CIMD yet.
    clientIdMetadataDocumentEnabled: true,
    clientRegistrationEndpoint: "/register",
    scopesSupported: SCOPES,
    resourceMetadata: {
      resource: `${origin}/mcp`,
      authorization_servers: [origin],
      scopes_supported: SCOPES,
      resource_name: "Yap",
    },
  });
}

let provider: OAuthProvider<Env> | null = null;

export default {
  fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    provider ??= buildProvider(env.PUBLIC_ORIGIN);
    // The provider derives its issuer and endpoint URLs from request.url, and they must
    // match the resource metadata exactly. `wrangler dev` rewrites the host to the route's,
    // so pin every request to the one public origin rather than trust whatever arrived.
    const url = new URL(request.url);
    if (url.origin !== env.PUBLIC_ORIGIN) {
      request = new Request(`${env.PUBLIC_ORIGIN}${url.pathname}${url.search}`, request);
    }
    return provider.fetch(request, env, ctx);
  },
};

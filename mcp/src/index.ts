#!/usr/bin/env node
/**
 * The local server, over stdio, for Claude Code on this machine. The same tools run remotely
 * as a Claude connector from `worker/index.ts`.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { loadConfig } from "./config.js";
import { loadCredentials } from "./auth.js";
import { SessionStore } from "./firestore.js";
import { RefreshTokenSource } from "./token.js";
import { Library, registerTools, SERVER_INFO, SERVER_INSTRUCTIONS } from "./tools.js";

const cfg = loadConfig();
const tokens = new RefreshTokenSource(
  cfg.apiKey,
  loadCredentials(cfg).refreshToken,
  'Run "npm run login" again.',
);

const server = new McpServer(SERVER_INFO, { instructions: SERVER_INSTRUCTIONS });
registerTools(server, new Library(new SessionStore(cfg.projectId, tokens), cfg.maxScan));

// stdout belongs to the protocol; anything written there corrupts the stream.
await server.connect(new StdioServerTransport());
console.error("yap-mcp ready");

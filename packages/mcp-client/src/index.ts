import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { z } from "zod";
const origin = process.env.TEXTKEEP_SERVER,
  token = process.env.TEXTKEEP_TOKEN;
if (!origin?.startsWith("https://") || !token?.startsWith("tk_"))
  throw Error(
    "Set TEXTKEEP_SERVER to your HTTPS origin and TEXTKEEP_TOKEN to a scoped personal token",
  );
const client = new Client({ name: "textkeep-local-adapter", version: "0.1.0" });
await client.connect(
  new StreamableHTTPClientTransport(new URL("/mcp", origin), {
    requestInit: { headers: { Authorization: `Bearer ${token}` } },
  }),
);
const tools = await client.listTools();
const server = new McpServer({ name: "TextKeep local", version: "0.1.0" });
for (const t of tools.tools) {
  server.registerTool(
    t.name,
    {
      description: t.description || t.name,
      inputSchema: { arguments: z.record(z.string(), z.unknown()).default({}) },
      annotations: t.annotations,
    },
    async ({ arguments: args }) =>
      client.callTool({ name: t.name, arguments: args }) as any,
  );
}
await server.connect(new StdioServerTransport());

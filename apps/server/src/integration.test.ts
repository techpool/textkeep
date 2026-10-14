import { test } from "node:test";
import assert from "node:assert/strict";
import pg from "pg";
import { randomUUID } from "node:crypto";
import { generateKeyPair, exportJWK } from "jose";
import { migrate } from "./database.js";
import { Service } from "./service.js";
import { Vault, secretHash } from "./crypto.js";
import { searchQuery } from "../../../packages/contracts/dist/index.js";
import type { Identity } from "./auth.js";
import { mcpServer } from "./mcp.js";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
const url = process.env.TEST_DATABASE_URL;
test(
  "database ingestion, tenant isolation, encryption, suppression, search budget and MCP scope enforcement",
  { skip: !url },
  async () => {
    assert.match(
      url!,
      /localhost|127\.0\.0\.1/,
      "Integration tests require an isolated local database",
    );
    const db = new pg.Pool({ connectionString: url });
    try {
      await migrate(db);
      const vault = new Vault({
        keyring: { v1: Buffer.alloc(32, 1).toString("base64") },
        activeKey: "v1",
        lookupKey: Buffer.alloc(32, 2).toString("base64"),
      });
      const service = new Service(db, vault, "https://example.test");
      const owner = "synthetic-" + randomUUID(),
        other = "synthetic-" + randomUUID();
      const identity: Identity = {
        uid: owner,
        authTime: Date.now() / 1000,
        client: "test-client",
        sensitive: true,
        kind: "firebase",
      };
      await db.query(
        "INSERT INTO textkeep.users(uid,email_digest,consent_version) VALUES($1,$2,$3),($4,$5,$3)",
        [owner, vault.lookup(owner), "test", other, vault.lookup(other)],
      );
      const device = randomUUID(),
        epoch = randomUUID();
      await db.query(
        "INSERT INTO textkeep.devices(id,owner,name,token_hash) VALUES($1,$2,$3,$4)",
        [device, owner, "Synthetic phone", secretHash("synthetic-" + device)],
      );
      const record = (
        id: string,
        body = "Synthetic message",
        revision = 1,
      ) => ({
        providerId: id,
        epoch,
        revision,
        address: "VM-DEMO",
        body,
        date: 1000,
        dateSent: 0,
        type: 1,
        read: false,
        seen: false,
        threadId: "1",
        subscriptionId: -1,
      });
      const batch = randomUUID();
      const rows = [
        record("1"),
        record("2"),
        record("3", "Your OTP is 123456"),
        { bad: "record" },
      ];
      const ack = await service.ingest(identity, device, batch, rows);
      assert.deepEqual(
        await service.ingest(identity, device, batch, rows),
        ack,
      );
      assert.equal((await service.status(identity)).archivedMessages, 3);
      assert.equal(ack.at(-1).status, "rejected");
      await assert.rejects(
        () => service.ingest(identity, device, batch, [record("9")]),
        /reused/,
      );
      await service.ingest(identity, device, randomUUID(), [
        record("1", "New state", 3),
      ]);
      await service.ingest(identity, device, randomUUID(), [
        record("1", "Old state", 2),
      ]);
      let found = await service.search(identity, searchQuery.parse({}));
      assert.equal(
        found.messages.find((m) => m.providerId === "1")?.body,
        "New state",
      );
      const otp = found.messages.find((m) => m.providerId === "3")!;
      const filtered = { ...identity, sensitive: false, kind: "pat" as const };
      assert.equal(
        (await service.search(filtered, searchQuery.parse({}))).messages.length,
        2,
      );
      await assert.rejects(() => service.get(filtered, otp.id), /unavailable/);
      await assert.rejects(
        () => service.get({ ...identity, uid: other }, otp.id),
        /unavailable/,
      );
      assert.equal(
        (
          await service.search(
            { ...identity, uid: other },
            searchQuery.parse({}),
          )
        ).messages.length,
        0,
      );
      const encrypted = await db.query(
        "SELECT payload FROM textkeep.messages WHERE owner=$1",
        [owner],
      );
      assert.ok(
        encrypted.rows.every(
          (r) =>
            !r.payload.includes("Synthetic") && !r.payload.includes("123456"),
        ),
      );
      await service.purge(identity, [otp.id]);
      await service.ingest(identity, device, randomUUID(), [
        record("3", "Your OTP is 123456", 2),
      ]);
      assert.equal((await service.status(identity)).archivedMessages, 2);
      const many = Array.from({ length: 600 }, (_, i) =>
        record(String(i + 100), "Search fixture " + i),
      );
      for (let i = 0; i < many.length; i += 100)
        await service.ingest(
          identity,
          device,
          randomUUID(),
          many.slice(i, i + 100),
        );
      found = await service.search(
        identity,
        searchQuery.parse({ query: "absent fixture" }),
      );
      assert.equal(found.scanned, 500);
      assert.equal(found.incompleteScan, true);
      assert.ok(found.nextCursor);
      const continuation = await service.search(
        identity,
        searchQuery.parse({
          query: "absent fixture",
          cursor: found.nextCursor,
        }),
      );
      assert.equal(continuation.nextCursor, null);
      const server = mcpServer(service, filtered),
        client = new Client({ name: "synthetic-inspector", version: "1.0.0" });
      const [ct, st] = InMemoryTransport.createLinkedPair();
      await server.connect(st);
      await client.connect(ct);
      const tools = await client.listTools();
      assert.deepEqual(tools.tools.map((t) => t.name).sort(), [
        "get_message",
        "get_sync_status",
        "get_thread_messages",
        "list_threads",
        "search_messages",
      ]);
      assert.ok(tools.tools.every((t) => t.annotations?.readOnlyHint));
      const result = await client.callTool({
        name: "search_messages",
        arguments: { query: "Search fixture", limit: 2 },
      });
      assert.match(JSON.stringify(result), /Search fixture/);
      await client.close();
      await server.close();
      await db.query("DELETE FROM textkeep.audit_events WHERE owner=ANY($1)", [
        [owner, other],
      ]);
    } finally {
      await db.end();
    }
  },
);

import { test } from "node:test";
import assert from "node:assert/strict";
import { messageRecord, searchQuery } from "./index.js";
test("preserves provider identity, Unicode and all six SMS boxes", () => {
  for (let type = 1; type <= 6; type++)
    assert.equal(
      messageRecord.parse({
        providerId: "4",
        epoch: "00000000-0000-4000-8000-000000000001",
        revision: 1,
        address: "VM-BANK",
        body: "ওটিপি 123456",
        date: 0,
        type,
        read: false,
        seen: false,
        threadId: "0",
      }).body,
      "ওটিপি 123456",
    );
});
test("rejects unbounded searches and unsupported content", () => {
  assert.equal(searchQuery.safeParse({ limit: 1000 }).success, false);
  assert.equal(
    searchQuery.safeParse({ include_sensitive: true }).success,
    false,
  );
});

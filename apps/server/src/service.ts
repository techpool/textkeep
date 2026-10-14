import type pg from "pg";
import { randomUUID } from "node:crypto";
import {
  messageRecord,
  type MessageRecord,
  type SearchQuery,
} from "../../../packages/contracts/dist/index.js";
import { Vault, secretHash, sensitive, redactSensitive } from "./crypto.js";
import { HttpError, type Identity } from "./auth.js";
export class Service {
  constructor(
    public db: pg.Pool,
    public vault: Vault,
    public origin: string,
  ) {}
  context(owner: string, id: string) {
    return `sms:${owner}:${id}`;
  }
  async ingest(
    i: Identity,
    device: string,
    batchId: string,
    records: unknown[],
  ) {
    const client = await this.db.connect();
    const digest = this.vault.lookup(JSON.stringify(records));
    try {
      await client.query("BEGIN");
      await client.query("SELECT pg_advisory_xact_lock(hashtext($1))", [
        `${i.uid}:${device}`,
      ]);
      const prior = await client.query(
        "SELECT * FROM textkeep.ingestion_batches WHERE owner=$1 AND device=$2 AND batch_id=$3",
        [i.uid, device, batchId],
      );
      if (prior.rowCount) {
        if (prior.rows[0].request_digest !== digest)
          throw new HttpError(409, "Batch identifier reused");
        await client.query("COMMIT");
        return prior.rows[0].acknowledgements;
      }
      const acknowledgements: object[] = [];
      for (const raw of records) {
        const parsed = messageRecord.safeParse(raw);
        if (!parsed.success) {
          acknowledgements.push({
            providerId:
              typeof raw === "object" && raw !== null && "providerId" in raw
                ? String(raw.providerId).slice(0, 64)
                : null,
            status: "rejected",
            error: "Invalid record",
          });
          continue;
        }
        const m = parsed.data;
        const suppression = await client.query(
          "SELECT 1 FROM textkeep.message_suppressions WHERE owner=$1 AND device=$2 AND epoch=$3 AND provider_id=$4",
          [i.uid, device, m.epoch, m.providerId],
        );
        if (suppression.rowCount) {
          acknowledgements.push({
            providerId: m.providerId,
            epoch: m.epoch,
            revision: m.revision,
            status: "suppressed",
          });
          continue;
        }
        const prev = await client.query(
          "SELECT id,revision FROM textkeep.messages WHERE owner=$1 AND device=$2 AND epoch=$3 AND provider_id=$4",
          [i.uid, device, m.epoch, m.providerId],
        );
        const p = prev.rows[0];
        if (p && Number(p.revision) >= m.revision) {
          acknowledgements.push({
            providerId: m.providerId,
            epoch: m.epoch,
            revision: m.revision,
            status: "stale",
          });
          continue;
        }
        const id = p?.id || randomUUID();
        const payload = this.vault.seal(
          {
            address: m.address,
            body: m.body,
            otp: m.otp || null,
            parserVersion: m.parserVersion,
            deliveryStatus: m.deliveryStatus,
          },
          this.context(i.uid, id),
        );
        await client.query(
          `INSERT INTO textkeep.messages(id,owner,device,epoch,provider_id,revision,payload,sender_digest,source_date,date_sent,box,is_read,seen,thread_id,subscription_id,sensitive) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16) ON CONFLICT(owner,device,epoch,provider_id) DO UPDATE SET revision=EXCLUDED.revision,payload=EXCLUDED.payload,sender_digest=EXCLUDED.sender_digest,source_date=EXCLUDED.source_date,date_sent=EXCLUDED.date_sent,box=EXCLUDED.box,is_read=EXCLUDED.is_read,seen=EXCLUDED.seen,thread_id=EXCLUDED.thread_id,subscription_id=EXCLUDED.subscription_id,sensitive=EXCLUDED.sensitive,ingested_at=now() WHERE textkeep.messages.revision<EXCLUDED.revision`,
          [
            id,
            i.uid,
            device,
            m.epoch,
            m.providerId,
            m.revision,
            payload,
            this.vault.lookup(m.address),
            m.date,
            m.dateSent,
            m.type,
            m.read,
            m.seen,
            m.threadId,
            m.subscriptionId,
            sensitive(m.body, m.otp),
          ],
        );
        acknowledgements.push({
          providerId: m.providerId,
          epoch: m.epoch,
          revision: m.revision,
          status: "committed",
        });
      }
      await client.query(
        "INSERT INTO textkeep.ingestion_batches(owner,device,batch_id,request_digest,acknowledgements) VALUES($1,$2,$3,$4,$5)",
        [i.uid, device, batchId, digest, JSON.stringify(acknowledgements)],
      );
      await client.query(
        "UPDATE textkeep.devices SET last_sync=now() WHERE id=$1 AND owner=$2",
        [device, i.uid],
      );
      await client.query(
        "DELETE FROM textkeep.ingestion_batches WHERE created_at<now()-interval '7 days'",
      );
      await client.query("COMMIT");
      return acknowledgements;
    } catch (e) {
      await client.query("ROLLBACK");
      throw e;
    } finally {
      client.release();
    }
  }
  decoded(i: Identity, row: Record<string, any>) {
    if (!i.sensitive && row.sensitive) return null;
    const data = this.vault.open<{
      address: string;
      body: string;
      otp: string | null;
      deliveryStatus?: number;
    }>(row.payload, this.context(i.uid, row.id));
    return {
      id: row.id,
      device: row.device,
      epoch: row.epoch,
      providerId: row.provider_id,
      revision: Number(row.revision),
      address: data.address,
      body: i.sensitive ? data.body : redactSensitive(data.body),
      date: Number(row.source_date),
      dateSent: Number(row.date_sent),
      type: row.box,
      read: row.is_read,
      seen: row.seen,
      threadId: row.thread_id,
      subscriptionId: row.subscription_id,
      deliveryStatus: data.deliveryStatus ?? -1,
      sensitive: row.sensitive,
      ingestedAt: row.ingested_at,
      deepLink: `${this.origin}/app/messages/${row.id}`,
    };
  }
  async audit(i: Identity, action: string, ids: string[]) {
    await this.db.query(
      "INSERT INTO textkeep.audit_events(owner,client,action,message_ids) VALUES($1,$2,$3,$4)",
      [i.uid, i.client, action, JSON.stringify(ids)],
    );
  }
  async search(
    i: Identity,
    q: SearchQuery,
    thread?: { device: string; epoch: string; id: string },
  ) {
    const values: unknown[] = [i.uid];
    const where = ["owner=$1"];
    const add = (clause: string, v: unknown) => {
      values.push(v);
      where.push(clause.replace("?", `$${values.length}`));
    };
    if (!i.sensitive) where.push("sensitive=false");
    if (q.sender) add("sender_digest=?", this.vault.lookup(q.sender));
    if (q.device) add("device=?", q.device);
    if (q.type) add("box=?", q.type);
    if (q.from !== undefined) add("source_date>=?", q.from);
    if (q.to !== undefined) add("source_date<=?", q.to);
    if (thread) {
      add("device=?", thread.device);
      add("epoch=?", thread.epoch);
      add("thread_id=?", thread.id);
    }
    if (q.cursor) {
      let cursor: { date: number; id: string; filter: string };
      try {
        cursor = JSON.parse(Buffer.from(q.cursor, "base64url").toString());
        if (
          !Number.isSafeInteger(cursor.date) ||
          !/^[-\w]{36}$/.test(cursor.id) ||
          cursor.filter !==
            this.vault.lookup(JSON.stringify({ ...q, cursor: undefined }))
        )
          throw Error();
      } catch {
        throw new HttpError(400, "Invalid search cursor");
      }
      values.push(cursor.date, cursor.id);
      where.push(`(source_date,id)<($${values.length - 1},$${values.length})`);
    }
    values.push(501);
    const r = await this.db.query(
      `SELECT * FROM textkeep.messages WHERE ${where.join(" AND ")} ORDER BY source_date DESC,id DESC LIMIT $${values.length}`,
      values,
    );
    const found: NonNullable<ReturnType<Service["decoded"]>>[] = [];
    let scanned = 0;
    let last: Record<string, any> | undefined;
    for (const row of r.rows.slice(0, 500)) {
      scanned++;
      last = row;
      const m = this.decoded(i, row);
      if (
        m &&
        (!q.query ||
          `${m.address}\n${m.body}`
            .toLowerCase()
            .includes(q.query.toLowerCase()))
      )
        found.push(m);
      if (found.length >= q.limit) break;
    }
    const hasMore = scanned < r.rows.length;
    const next =
      hasMore && last
        ? Buffer.from(
            JSON.stringify({
              date: Number(last.source_date),
              id: last.id,
              filter: this.vault.lookup(
                JSON.stringify({ ...q, cursor: undefined }),
              ),
            }),
          ).toString("base64url")
        : null;
    await this.audit(
      i,
      "search_messages",
      found.map((m) => m.id),
    );
    return {
      messages: found,
      nextCursor: next,
      scanned,
      incompleteScan: hasMore && found.length < q.limit,
      scanBudget: 500,
    };
  }
  async get(i: Identity, id: string) {
    const r = await this.db.query(
      "SELECT * FROM textkeep.messages WHERE owner=$1 AND id=$2",
      [i.uid, id],
    );
    if (!r.rows[0]) throw new HttpError(404, "Message unavailable");
    const m = this.decoded(i, r.rows[0]);
    if (!m) throw new HttpError(404, "Message unavailable");
    await this.audit(i, "get_message", [id]);
    return m;
  }
  async threads(i: Identity) {
    const r = await this.db.query(
      `SELECT DISTINCT ON(device,epoch,thread_id) * FROM textkeep.messages WHERE owner=$1 ${i.sensitive ? "" : "AND sensitive=false"} ORDER BY device,epoch,thread_id,source_date DESC LIMIT 100`,
      [i.uid],
    );
    const threads = r.rows
      .map((row) => {
        const m = this.decoded(i, row)!;
        return {
          id: Buffer.from(
            JSON.stringify({
              device: m.device,
              epoch: m.epoch,
              id: m.threadId,
            }),
          ).toString("base64url"),
          address: m.address,
          latest: m,
          date: m.date,
        };
      })
      .sort((a, b) => b.date - a.date);
    await this.audit(
      i,
      "list_threads",
      threads.map((t) => t.latest.id),
    );
    return { threads, truncated: r.rowCount === 100 };
  }
  async thread(i: Identity, id: string, q: SearchQuery) {
    let ref: { device: string; epoch: string; id: string };
    try {
      ref = JSON.parse(Buffer.from(id, "base64url").toString());
      if (!ref.device || !ref.epoch || !ref.id) throw Error();
    } catch {
      throw new HttpError(400, "Invalid thread");
    }
    return this.search(i, q, ref);
  }
  async status(i: Identity) {
    const r = await this.db.query(
      "SELECT id,name,last_sync,revoked_at FROM textkeep.devices WHERE owner=$1 ORDER BY created_at DESC",
      [i.uid],
    );
    const count = await this.db.query(
      "SELECT count(*) FROM textkeep.messages WHERE owner=$1",
      [i.uid],
    );
    return {
      devices: r.rows,
      archivedMessages: Number(count.rows[0].count),
      freshness:
        "Last sync describes uploaded data; the phone may have pending or inaccessible messages.",
    };
  }
  async purge(i: Identity, ids: string[]) {
    const client = await this.db.connect();
    try {
      await client.query("BEGIN");
      const r = await client.query(
        "SELECT * FROM textkeep.messages WHERE owner=$1 AND id=ANY($2::uuid[]) FOR UPDATE",
        [i.uid, ids],
      );
      for (const m of r.rows)
        await client.query(
          "INSERT INTO textkeep.message_suppressions(owner,device,epoch,provider_id) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING",
          [i.uid, m.device, m.epoch, m.provider_id],
        );
      await client.query(
        "DELETE FROM textkeep.messages WHERE owner=$1 AND id=ANY($2::uuid[])",
        [i.uid, ids],
      );
      await client.query("COMMIT");
      await this.audit(
        i,
        "purge",
        r.rows.map((m) => m.id),
      );
      return { deleted: r.rowCount };
    } catch (e) {
      await client.query("ROLLBACK");
      throw e;
    } finally {
      client.release();
    }
  }
}

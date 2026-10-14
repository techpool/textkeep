import pg from "pg";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import type { Config } from "./config.js";
export function pool(c: Config) {
  return new pg.Pool({
    connectionString: c.databaseUrl,
    max: 8,
    idleTimeoutMillis: 30000,
    connectionTimeoutMillis: 8000,
    ssl: c.databaseUrl.includes("sslmode=") ? undefined : false,
  });
}
export async function migrate(db: pg.Pool) {
  const client = await db.connect();
  try {
    await client.query(
      "SELECT pg_advisory_lock(hashtext('textkeep-migrations'))",
    );
    await client.query("BEGIN");
    const schema = await client.query(
      "SELECT 1 FROM pg_namespace WHERE nspname='textkeep'",
    );
    if (!schema.rowCount) await client.query("CREATE SCHEMA textkeep");
    for (const [version, file] of [
      ["001", "001_initial.sql"],
      ["002", "002_web_sessions.sql"],
    ]) {
      const sql = await readFile(
        fileURLToPath(
          new URL(`../../../database/migrations/${file}`, import.meta.url),
        ),
        "utf8",
      );
      await client.query(sql);
      await client.query(
        "INSERT INTO textkeep.schema_migrations(version) VALUES($1) ON CONFLICT DO NOTHING",
        [version],
      );
    }
    await client.query("COMMIT");
  } catch (e) {
    await client.query("ROLLBACK");
    throw e;
  } finally {
    await client.query(
      "SELECT pg_advisory_unlock(hashtext('textkeep-migrations'))",
    );
    client.release();
  }
}

import { config } from "./config.js";
import { pool, migrate } from "./database.js";
import { buildApp } from "./app.js";
const c = config(),
  db = pool(c);
if (process.argv.includes("--migrate")) {
  await migrate(db);
  await db.end();
} else {
  const app = await buildApp(c, db);
  await app.listen({ host: "0.0.0.0", port: c.port });
  for (const signal of ["SIGTERM", "SIGINT"])
    process.on(signal, () => {
      void app.close();
    });
}

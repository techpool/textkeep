import { z } from "zod";
export const messageRecord = z
  .object({
    providerId: z.string().regex(/^\d+$/),
    epoch: z.string().uuid(),
    revision: z.number().int().positive(),
    address: z.string().max(512),
    body: z.string().max(100000),
    date: z.number().int().min(0).max(8640000000000000),
    dateSent: z.number().int().min(0).default(0),
    type: z.number().int().min(1).max(6),
    read: z.boolean(),
    seen: z.boolean(),
    threadId: z.string().max(64),
    subscriptionId: z.number().int().default(-1),
    deliveryStatus: z.number().int().default(-1),
    otp: z.string().max(16).nullable().optional(),
    parserVersion: z.number().int().default(1),
  })
  .strict();
export type MessageRecord = z.infer<typeof messageRecord>;
export const syncBatch = z
  .object({
    deviceId: z.string().uuid(),
    batchId: z.string().uuid(),
    records: z.array(z.unknown()).min(1).max(100),
  })
  .strict();
export const searchQuery = z
  .object({
    query: z.string().max(200).optional(),
    sender: z.string().max(512).optional(),
    device: z.string().uuid().optional(),
    type: z.coerce.number().int().min(1).max(6).optional(),
    from: z.coerce.number().int().nonnegative().optional(),
    to: z.coerce.number().int().nonnegative().optional(),
    cursor: z.string().max(512).optional(),
    limit: z.coerce.number().int().min(1).max(50).default(25),
  })
  .strict();
export type SearchQuery = z.infer<typeof searchQuery>;
export const SENSITIVE_SCOPE = "messages:sensitive";
export const READ_SCOPE = "messages:read";
export const CONSENT_VERSION = "2026-10-14";
export const boxes: Record<number, string> = {
  1: "Inbox",
  2: "Sent",
  3: "Draft",
  4: "Outbox",
  5: "Failed",
  6: "Queued",
};

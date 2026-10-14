let csrf = "";
export async function api<T = any>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  if (options.method && options.method !== "GET" && !csrf) {
    const response = await fetch("/api/v1/auth/csrf", {
      credentials: "same-origin",
    });
    csrf = (await response.json()).token;
  }
  const res = await fetch(path, {
    credentials: "same-origin",
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(csrf ? { "X-CSRF-Token": csrf } : {}),
      ...options.headers,
    },
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw Error(body.error || `Request failed (${res.status})`);
  return body as T;
}
export interface Message {
  id: string;
  address: string;
  body: string;
  date: number;
  dateSent: number;
  type: number;
  device: string;
  sensitive: boolean;
  threadId: string;
  read: boolean;
  ingestedAt: string;
}
export interface PublicConfig {
  firebase: Record<string, string>;
  release: {
    version: string;
    sha256: string;
    certificateSha256: string;
    size: number;
    experimental: boolean;
  } | null;
  registration: string;
}

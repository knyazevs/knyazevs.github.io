/**
 * Short-lived HMAC session tokens — fetched from /api/session on startup and
 * auto-refreshed near expiry. Bound to the browser fingerprint server-side,
 * so a stolen token can't be replayed from a different browser.
 */

import { BACKEND_URL } from "../config";
import { computeFingerprint } from "./fingerprint";

const REFRESH_BUFFER_MS = 2 * 60 * 1000; // refresh when ≤ 2 min remaining

interface SessionToken {
  token: string;
  expiresAtMs: number;
}

let cached: SessionToken | null = null;
let inflight: Promise<SessionToken> | null = null;

async function fetchSession(fingerprint: string): Promise<SessionToken> {
  const res = await fetch(`${BACKEND_URL}/api/session`, {
    headers: { "X-Fingerprint": fingerprint },
  });
  if (!res.ok) {
    throw new Error(`session fetch failed: ${res.status}`);
  }
  const data = await res.json();
  return { token: data.token, expiresAtMs: data.expiresAtMs };
}

async function ensureSession(fingerprint: string): Promise<SessionToken> {
  const now = Date.now();
  if (cached && cached.expiresAtMs - now > REFRESH_BUFFER_MS) return cached;
  if (inflight) return inflight;

  inflight = fetchSession(fingerprint)
    .then((s) => {
      cached = s;
      return s;
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/**
 * Returns headers to attach to any protected /api/* call:
 *   X-Fingerprint + X-Api-Session.
 *
 * Silently returns just the fingerprint header if session fetch fails — the
 * server will respond 401 and UI can surface a generic error. Better than
 * blocking all calls behind a session fetch that could have transient errors.
 */
export async function authHeaders(): Promise<Record<string, string>> {
  const fingerprint = await computeFingerprint();
  try {
    const session = await ensureSession(fingerprint);
    return {
      "X-Fingerprint": fingerprint,
      "X-Api-Session": session.token,
    };
  } catch {
    return { "X-Fingerprint": fingerprint };
  }
}

/** Force next call to re-issue (e.g. after a 401 from the server). */
export function invalidateSession(): void {
  cached = null;
}

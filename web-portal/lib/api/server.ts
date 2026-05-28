import { cookies } from 'next/headers';
import { REFRESH_COOKIE_NAME, SERVER_API_BASE_URL } from '@/lib/config';
import type { Problem } from './types';

export class ServerApiError extends Error {
  status: number;
  problem?: Problem;
  constructor(status: number, message: string, problem?: Problem) {
    super(message);
    this.status = status;
    this.problem = problem;
  }
}

/**
 * Short-lived server-side cache for access tokens.
 *
 * Problem: `serverFetch` runs on every RSC render (including `router.refresh()`).
 * Each call exchanges the refresh token at the upstream auth service. If the auth
 * service uses rotating refresh tokens, each exchange invalidates the current token
 * and issues a new one — but the new token is never written back to the HttpOnly
 * cookie (RSCs cannot set cookies). The next client call to `/api/auth/refresh`
 * then fails with 401 because the cookie still holds the already-rotated token.
 *
 * Fix: cache the access token for slightly less than its TTL. Subsequent RSC
 * renders reuse the cached token without calling the upstream, leaving the refresh
 * token in the cookie untouched.
 */
const atCache = new Map<string, { token: string; expiresAt: number }>();
const AT_TTL_MS = 50_000; // 50 s — assumes access tokens live at least 60 s

async function exchangeRefreshForAccessToken(refreshToken: string): Promise<string | null> {
  const cached = atCache.get(refreshToken);
  if (cached && cached.expiresAt > Date.now()) return cached.token;

  try {
    const res = await fetch(`${SERVER_API_BASE_URL}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
      cache: 'no-store',
    });
    if (!res.ok) return null;
    const data = (await res.json()) as { accessToken: string };
    atCache.set(refreshToken, { token: data.accessToken, expiresAt: Date.now() + AT_TTL_MS });
    // Evict expired entries to avoid unbounded growth
    for (const [key, entry] of atCache) {
      if (entry.expiresAt <= Date.now()) atCache.delete(key);
    }
    return data.accessToken;
  } catch {
    return null;
  }
}

export async function serverFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const cookieStore = cookies();
  const refresh = cookieStore.get(REFRESH_COOKIE_NAME)?.value;
  if (!refresh) throw new ServerApiError(401, 'No refresh cookie');

  const token = await exchangeRefreshForAccessToken(refresh);
  if (!token) throw new ServerApiError(401, 'Refresh failed');

  const res = await fetch(`${SERVER_API_BASE_URL}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
      ...(init.headers ?? {})
    },
    cache: 'no-store'
  });

  if (!res.ok) {
    let problem: Problem | undefined;
    try { problem = (await res.json()) as Problem; } catch { /* ignore */ }
    throw new ServerApiError(res.status, problem?.title ?? res.statusText, problem);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export function hasRefreshCookie(): boolean {
  return Boolean(cookies().get(REFRESH_COOKIE_NAME)?.value);
}

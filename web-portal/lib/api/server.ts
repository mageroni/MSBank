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

async function exchangeRefreshForAccessToken(refreshToken: string): Promise<string | null> {
  try {
    const res = await fetch(`${SERVER_API_BASE_URL}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
      cache: 'no-store'
    });
    if (!res.ok) return null;
    const data = (await res.json()) as { accessToken: string };
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

import { PUBLIC_API_BASE_URL } from '@/lib/config';
import type { Problem } from './types';

export class ApiError extends Error {
  status: number;
  problem?: Problem;
  constructor(status: number, message: string, problem?: Problem) {
    super(message);
    this.status = status;
    this.problem = problem;
  }
}

type AccessTokenGetter = () => string | null;
type AccessTokenSetter = (token: string | null) => void;

let getAccessToken: AccessTokenGetter = () => null;
let setAccessToken: AccessTokenSetter = () => undefined;
let onUnauthorized: () => void = () => undefined;

export function configureClient(opts: {
  getAccessToken: AccessTokenGetter;
  setAccessToken: AccessTokenSetter;
  onUnauthorized: () => void;
}) {
  getAccessToken = opts.getAccessToken;
  setAccessToken = opts.setAccessToken;
  onUnauthorized = opts.onUnauthorized;
}

export interface FetcherOptions extends Omit<RequestInit, 'body'> {
  body?: unknown;
  query?: Record<string, string | number | undefined | null>;
  auth?: boolean;
  baseUrl?: string;
}

async function tryRefresh(): Promise<string | null> {
  try {
    const res = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' });
    if (!res.ok) return null;
    const data = (await res.json()) as { accessToken: string };
    setAccessToken(data.accessToken);
    return data.accessToken;
  } catch {
    return null;
  }
}

export async function fetcher<T>(path: string, opts: FetcherOptions = {}): Promise<T> {
  const { body, query, auth = true, baseUrl = PUBLIC_API_BASE_URL, headers, ...rest } = opts;
  const url = buildUrl(baseUrl, path, query);
  const doFetch = async (token: string | null): Promise<Response> => {
    const finalHeaders: Record<string, string> = {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(headers as Record<string, string> | undefined)
    };
    return fetch(url, {
      ...rest,
      headers: finalHeaders,
      body: body !== undefined ? JSON.stringify(body) : undefined
    });
  };

  let token = auth ? getAccessToken() : null;
  let res = await doFetch(token);

  if (res.status === 401 && auth) {
    const refreshed = await tryRefresh();
    if (refreshed) {
      res = await doFetch(refreshed);
    } else {
      onUnauthorized();
      throw new ApiError(401, 'Unauthorized');
    }
  }

  if (!res.ok) {
    let problem: Problem | undefined;
    try { problem = (await res.json()) as Problem; } catch { /* ignore */ }
    throw new ApiError(res.status, problem?.title ?? res.statusText, problem);
  }

  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

function buildUrl(baseUrl: string, path: string, query?: FetcherOptions['query']): string {
  const url = new URL(path.startsWith('http') ? path : `${baseUrl}${path}`);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v));
    }
  }
  return url.toString();
}

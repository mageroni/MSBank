import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import { REFRESH_COOKIE_NAME, SERVER_API_BASE_URL } from '@/lib/config';
import type { TokenPair } from '@/lib/api/types';

export async function POST() {
  const refresh = cookies().get(REFRESH_COOKIE_NAME)?.value;
  if (!refresh) {
    return NextResponse.json({ title: 'No refresh token' }, { status: 401 });
  }

  const upstream = await fetch(`${SERVER_API_BASE_URL}/api/v1/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: refresh })
  });

  if (!upstream.ok) {
    const res = NextResponse.json({ title: 'Refresh failed' }, { status: 401 });
    res.cookies.set(REFRESH_COOKIE_NAME, '', { maxAge: 0, path: '/' });
    return res;
  }

  const tokens = (await upstream.json()) as TokenPair;
  const res = NextResponse.json({ accessToken: tokens.accessToken });
  res.cookies.set(REFRESH_COOKIE_NAME, tokens.refreshToken, {
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
    sameSite: 'lax',
    path: '/',
    maxAge: 60 * 60 * 24 * 30
  });
  return res;
}

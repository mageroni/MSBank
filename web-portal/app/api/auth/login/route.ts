import { NextResponse } from 'next/server';
import { REFRESH_COOKIE_NAME, SERVER_API_BASE_URL } from '@/lib/config';
import type { TokenPair, User } from '@/lib/api/types';

export async function POST(req: Request) {
  let body: unknown;
  try { body = await req.json(); } catch { return NextResponse.json({ title: 'Invalid body' }, { status: 400 }); }

  const upstream = await fetch(`${SERVER_API_BASE_URL}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });

  if (!upstream.ok) {
    const text = await upstream.text();
    return new NextResponse(text || JSON.stringify({ title: 'Login failed' }), {
      status: upstream.status,
      headers: { 'Content-Type': upstream.headers.get('Content-Type') ?? 'application/json' }
    });
  }

  const tokens = (await upstream.json()) as TokenPair;

  let user: User | null = null;
  try {
    const meRes = await fetch(`${SERVER_API_BASE_URL}/api/v1/auth/me`, {
      headers: { Authorization: `Bearer ${tokens.accessToken}` }
    });
    if (meRes.ok) user = (await meRes.json()) as User;
  } catch { /* ignore */ }

  const res = NextResponse.json({ accessToken: tokens.accessToken, user });
  res.cookies.set(REFRESH_COOKIE_NAME, tokens.refreshToken, {
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
    sameSite: 'lax',
    path: '/',
    maxAge: 60 * 60 * 24 * 30
  });
  return res;
}

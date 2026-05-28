import { NextResponse } from 'next/server';
import { REFRESH_COOKIE_NAME } from '@/lib/config';

export async function POST() {
  const res = NextResponse.json({ ok: true });
  res.cookies.set(REFRESH_COOKIE_NAME, '', { maxAge: 0, path: '/' });
  return res;
}

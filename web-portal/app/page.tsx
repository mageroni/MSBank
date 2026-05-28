import { redirect } from 'next/navigation';
import { hasRefreshCookie } from '@/lib/api/server';

export default function RootPage() {
  redirect(hasRefreshCookie() ? '/dashboard' : '/login');
}

'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { LayoutDashboard, ArrowRightLeft, Bell, ShieldCheck } from 'lucide-react';
import { cn } from '@/lib/cn';

const items = [
  { href: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { href: '/transfer', label: 'Transfer', icon: ArrowRightLeft },
  { href: '/notifications', label: 'Notifications', icon: Bell },
  { href: '/settings/security', label: 'Security', icon: ShieldCheck }
];

export function SideNav() {
  const pathname = usePathname();
  return (
    <aside className="hidden w-56 shrink-0 md:block">
      <nav className="flex flex-col gap-1" aria-label="Primary">
        {items.map(({ href, label, icon: Icon }) => {
          const active = pathname === href || pathname.startsWith(href + '/');
          return (
            <Link
              key={href}
              href={href}
              className={cn(
                'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition',
                active
                  ? 'bg-brand-100 text-brand-900 dark:bg-brand-900/40 dark:text-brand-200'
                  : 'text-slate-700 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'
              )}
            >
              <Icon className="h-4 w-4" />
              {label}
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}

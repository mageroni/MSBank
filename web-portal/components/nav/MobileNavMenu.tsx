'use client';

import Link from 'next/link';
import { X } from 'lucide-react';

const items = [
  { href: '/dashboard', label: 'Dashboard' },
  { href: '/transfer', label: 'Transfer' },
  { href: '/notifications', label: 'Notifications' },
  { href: '/settings/security', label: 'Security' }
];

export function MobileNavMenu({ open, onClose }: { open: boolean; onClose: () => void }) {
  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40 bg-black/50 md:hidden" role="presentation" onClick={onClose}>
      <div
        className="absolute left-0 top-0 h-full w-64 bg-white p-4 shadow-xl dark:bg-slate-900"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-label="Mobile navigation"
      >
        <div className="mb-4 flex items-center justify-between">
          <span className="text-sm font-semibold">Menu</span>
          <button onClick={onClose} aria-label="Close menu" className="rounded-md p-1">
            <X className="h-5 w-5" />
          </button>
        </div>
        <nav className="flex flex-col gap-1">
          {items.map(({ href, label }) => (
            <Link
              key={href}
              href={href}
              onClick={onClose}
              className="rounded-md px-3 py-2 text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800"
            >
              {label}
            </Link>
          ))}
        </nav>
      </div>
    </div>
  );
}

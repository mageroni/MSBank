'use client';

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Menu, Moon, Sun, LogOut } from 'lucide-react';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTheme } from '@/lib/useTheme';
import { Logo } from '@/components/ui/Logo';
import { Button } from '@/components/ui/Button';
import { MobileNavMenu } from './MobileNavMenu';

export function TopNav() {
  const { user, logout } = useAuth();
  const { theme, toggle } = useTheme();
  const [mobileOpen, setMobileOpen] = useState(false);

  return (
    <header className="sticky top-0 z-30 border-b border-slate-200 bg-white/80 backdrop-blur dark:border-slate-800 dark:bg-slate-950/80">
      <div className="mx-auto flex h-14 max-w-7xl items-center justify-between gap-4 px-4 md:px-6">
        <div className="flex items-center gap-2">
          <button
            type="button"
            className="rounded-md p-2 md:hidden"
            onClick={() => setMobileOpen(true)}
            aria-label="Open menu"
          >
            <Menu className="h-5 w-5" />
          </button>
          <Link to="/dashboard" className="flex items-center gap-2">
            <Logo className="h-7 w-7" />
            <span className="font-semibold tracking-tight">MS Bank</span>
          </Link>
        </div>
        <div className="flex items-center gap-2">
          {user && (
            <span className="hidden text-sm text-slate-600 sm:inline dark:text-slate-400">
              {user.firstName} {user.lastName}
            </span>
          )}
          <button
            type="button"
            className="rounded-md p-2 hover:bg-slate-100 dark:hover:bg-slate-800"
            aria-label="Toggle theme"
            onClick={toggle}
          >
            {theme === 'dark' ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
          </button>
          <Button variant="ghost" size="sm" onClick={() => void logout()} aria-label="Sign out">
            <LogOut className="h-4 w-4" />
            <span className="hidden sm:inline">Sign out</span>
          </Button>
        </div>
      </div>
      <MobileNavMenu open={mobileOpen} onClose={() => setMobileOpen(false)} />
    </header>
  );
}

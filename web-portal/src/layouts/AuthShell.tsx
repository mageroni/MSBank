import { Outlet } from 'react-router-dom';

export function AuthShell() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-gradient-to-br from-slate-50 to-emerald-50 p-6 dark:from-slate-950 dark:to-slate-900">
      <Outlet />
    </main>
  );
}

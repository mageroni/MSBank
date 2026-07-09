import { Outlet } from 'react-router-dom';
import { TopNav } from '@/components/nav/TopNav';
import { SideNav } from '@/components/nav/SideNav';

export function AppShell() {
  return (
    <div className="flex min-h-screen flex-col bg-slate-50 dark:bg-slate-950">
      <TopNav />
      <div className="mx-auto flex w-full max-w-7xl flex-1 gap-6 px-4 py-6 md:px-6">
        <SideNav />
        <main className="min-w-0 flex-1">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

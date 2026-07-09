import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-slate-50 p-6 text-center dark:bg-slate-950">
      <h1 className="text-4xl font-semibold">Page not found</h1>
      <p className="text-slate-500">The page you requested does not exist.</p>
      <Link
        to="/"
        className="inline-flex h-10 items-center justify-center rounded-md bg-brand-600 px-4 text-sm font-medium text-white transition hover:bg-brand-700"
      >
        Go home
      </Link>
    </main>
  );
}

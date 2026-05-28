import Link from 'next/link';
import { serverFetch, ServerApiError } from '@/lib/api/server';
import type { DashboardPayload } from '@/lib/api/types';
import { Card, CardTitle } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { formatMoney } from '@/lib/format/money';
import { formatDateTime } from '@/lib/format/date';
import { ErrorState, EmptyState } from '@/components/ui/States';
import { BalanceTrendChart } from '@/components/dashboard/BalanceTrendChart';

export const dynamic = 'force-dynamic';

export default async function DashboardPage() {
  let data: DashboardPayload | null = null;
  let errorMessage: string | null = null;
  try {
    data = await serverFetch<DashboardPayload>('/bff/v1/dashboard');
  } catch (e) {
    errorMessage = e instanceof ServerApiError ? e.message : 'Failed to load dashboard';
  }

  if (errorMessage || !data) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">Dashboard</h1>
        <ErrorState message={errorMessage ?? 'No data available.'} />
      </div>
    );
  }

  const primaryCurrency = data.accounts[0]?.currency ?? 'USD';

  return (
    <div className="space-y-6">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Dashboard</h1>
          <p className="text-sm text-slate-500">As of {formatDateTime(data.asOf)}</p>
        </div>
        <Link href="/notifications" className="relative inline-flex items-center gap-2 text-sm text-slate-600 hover:text-slate-900 dark:text-slate-300">
          Notifications
          {data.unreadNotifications > 0 && <Badge tone="info">{data.unreadNotifications}</Badge>}
        </Link>
      </header>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card>
          <CardTitle>Total balance</CardTitle>
          <p className="mt-1 text-3xl font-semibold">
            {formatMoney(data.totalBalance, primaryCurrency)}
          </p>
          <p className="text-xs text-slate-500">Across {data.accounts.length} account(s)</p>
        </Card>
        <Card className="md:col-span-2">
          <CardTitle>Balance trend (30 days)</CardTitle>
          <div className="mt-2 h-40">
            <BalanceTrendChart total={data.totalBalance} />
          </div>
        </Card>
      </div>

      <Card>
        <CardTitle>Accounts</CardTitle>
        {data.accounts.length === 0 ? (
          <EmptyState title="No accounts yet" description="Open an account to get started." className="mt-3" />
        ) : (
          <ul className="mt-3 divide-y divide-slate-200 dark:divide-slate-800">
            {data.accounts.map((a) => (
              <li key={a.id} className="py-3">
                <Link href={`/accounts/${a.id}`} className="flex items-center justify-between gap-4 hover:bg-slate-50 -mx-2 px-2 py-1 rounded dark:hover:bg-slate-800/50">
                  <div>
                    <p className="font-medium">{a.nickname ?? `${a.accountType} • ${a.id.slice(0, 8)}`}</p>
                    <p className="text-xs text-slate-500">
                      {a.accountType} • <Badge tone={a.status === 'ACTIVE' ? 'success' : a.status === 'FROZEN' ? 'warning' : 'neutral'}>{a.status}</Badge>
                    </p>
                  </div>
                  <p className="font-mono text-sm">{formatMoney(a.balance, a.currency)}</p>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card>
        <CardTitle>Recent transfers</CardTitle>
        {data.recentTransfers.length === 0 ? (
          <EmptyState title="No recent transfers" className="mt-3" />
        ) : (
          <ul className="mt-3 divide-y divide-slate-200 dark:divide-slate-800">
            {data.recentTransfers.map((t) => (
              <li key={t.id} className="flex items-center justify-between gap-4 py-3">
                <div>
                  <p className="font-mono text-xs text-slate-500">{t.id.slice(0, 8)}</p>
                  <p className="text-sm">{t.reference ?? 'Transfer'}</p>
                  <p className="text-xs text-slate-500">{formatDateTime(t.createdAt)}</p>
                </div>
                <div className="text-right">
                  <p className="font-mono text-sm">{formatMoney(t.amount, t.currency)}</p>
                  <Badge tone={t.status === 'COMPLETED' ? 'success' : t.status === 'FAILED' ? 'danger' : 'info'}>{t.status}</Badge>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

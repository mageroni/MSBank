import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api/endpoints';
import { ApiError } from '@/lib/api/client';
import { Card, CardTitle } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { formatMoney } from '@/lib/format/money';
import { formatDateTime } from '@/lib/format/date';
import { ErrorState, EmptyState } from '@/components/ui/States';
import { BalanceTrendChart } from '@/components/dashboard/BalanceTrendChart';
import { Skeleton } from '@/components/ui/Spinner';

function DashboardLoading() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-8 w-48" />
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card><Skeleton className="h-20" /></Card>
        <Card className="md:col-span-2"><Skeleton className="h-40" /></Card>
      </div>
      <Card><Skeleton className="h-32" /></Card>
      <Card><Skeleton className="h-32" /></Card>
    </div>
  );
}

export function DashboardPage() {
  const { data, error, isLoading } = useQuery({
    queryKey: ['dashboard'],
    queryFn: api.dashboard.get
  });

  if (isLoading) return <DashboardLoading />;

  if (!data || error) {
    const message = error instanceof ApiError ? error.message : 'Failed to load dashboard';
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">Dashboard</h1>
        <ErrorState message={message} />
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
        <Link to="/notifications" className="relative inline-flex items-center gap-2 text-sm text-slate-600 hover:text-slate-900 dark:text-slate-300">
          Notifications
          {data.unreadNotifications > 0 && <Badge tone="info">{data.unreadNotifications}</Badge>}
        </Link>
      </header>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card>
          <CardTitle>Total balance</CardTitle>
          <p className="mt-1 text-3xl font-semibold">{formatMoney(data.totalBalance, primaryCurrency)}</p>
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
            {data.accounts.map((account) => (
              <li key={account.id} className="py-3">
                <Link to={`/accounts/${account.id}`} className="-mx-2 flex items-center justify-between gap-4 rounded px-2 py-1 hover:bg-slate-50 dark:hover:bg-slate-800/50">
                  <div>
                    <p className="font-medium">{account.nickname ?? `${account.accountType} • ${account.id.slice(0, 8)}`}</p>
                    <p className="text-xs text-slate-500">
                      {account.accountType} • <Badge tone={account.status === 'ACTIVE' ? 'success' : account.status === 'FROZEN' ? 'warning' : 'neutral'}>{account.status}</Badge>
                    </p>
                  </div>
                  <p className="font-mono text-sm">{formatMoney(account.balance, account.currency)}</p>
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
            {data.recentTransfers.map((transfer) => (
              <li key={transfer.id} className="flex items-center justify-between gap-4 py-3">
                <div>
                  <p className="font-mono text-xs text-slate-500">{transfer.id.slice(0, 8)}</p>
                  <p className="text-sm">{transfer.reference ?? 'Transfer'}</p>
                  <p className="text-xs text-slate-500">{formatDateTime(transfer.createdAt)}</p>
                </div>
                <div className="text-right">
                  <p className="font-mono text-sm">{formatMoney(transfer.amount, transfer.currency)}</p>
                  <Badge tone={transfer.status === 'COMPLETED' ? 'success' : transfer.status === 'FAILED' ? 'danger' : 'info'}>{transfer.status}</Badge>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

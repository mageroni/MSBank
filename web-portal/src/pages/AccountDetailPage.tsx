import { Navigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api/endpoints';
import { ApiError } from '@/lib/api/client';
import { Card, CardTitle } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { formatMoney } from '@/lib/format/money';
import { formatDateTime } from '@/lib/format/date';
import { ErrorState } from '@/components/ui/States';
import { Skeleton } from '@/components/ui/Spinner';
import { TransactionHistory } from '@/components/accounts/TransactionHistory';

function AccountDetailLoading() {
  return (
    <div className="space-y-6">
      <Skeleton className="h-8 w-64" />
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card><Skeleton className="h-20" /></Card>
        <Card><Skeleton className="h-20" /></Card>
        <Card><Skeleton className="h-20" /></Card>
      </div>
      <Card><Skeleton className="h-40" /></Card>
    </div>
  );
}

export function AccountDetailPage() {
  const { id } = useParams<{ id: string }>();

  if (!id) return <Navigate to="/404" replace />;

  const { data: account, error, isLoading } = useQuery({
    queryKey: ['account', id],
    queryFn: () => api.accounts.get(id)
  });

  if (isLoading) return <AccountDetailLoading />;

  if (error) {
    if (error instanceof ApiError && error.status === 404) {
      return <Navigate to="/404" replace />;
    }

    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">Account details</h1>
        <ErrorState message={error instanceof Error ? error.message : 'Failed to load account'} />
      </div>
    );
  }

  if (!account) return <Navigate to="/404" replace />;

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">{account.nickname ?? account.accountType}</h1>
        <p className="font-mono text-xs text-slate-500">{account.id}</p>
      </header>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card>
          <CardTitle>Balance</CardTitle>
          <p className="mt-1 text-2xl font-semibold">{formatMoney(account.balance, account.currency)}</p>
          {account.availableBalance !== undefined && (
            <p className="text-xs text-slate-500">Available: {formatMoney(account.availableBalance, account.currency)}</p>
          )}
        </Card>
        <Card>
          <CardTitle>Status</CardTitle>
          <p className="mt-1">
            <Badge tone={account.status === 'ACTIVE' ? 'success' : account.status === 'FROZEN' ? 'warning' : 'neutral'}>
              {account.status}
            </Badge>
          </p>
          <p className="mt-1 text-xs text-slate-500">{account.accountType}</p>
        </Card>
        <Card>
          <CardTitle>Opened</CardTitle>
          <p className="mt-1 text-sm">{formatDateTime(account.createdAt)}</p>
          <p className="text-xs text-slate-500">v{account.version}</p>
        </Card>
      </div>

      <Card>
        <CardTitle>Transaction history</CardTitle>
        <TransactionHistory accountId={account.id} currency={account.currency} />
      </Card>
    </div>
  );
}

import { notFound } from 'next/navigation';
import { serverFetch, ServerApiError } from '@/lib/api/server';
import type { Account } from '@/lib/api/types';
import { Card, CardTitle } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { formatMoney } from '@/lib/format/money';
import { formatDateTime } from '@/lib/format/date';
import { TransactionHistory } from '@/components/accounts/TransactionHistory';

export const dynamic = 'force-dynamic';

export default async function AccountDetailPage({ params }: { params: { id: string } }) {
  let account: Account;
  try {
    account = await serverFetch<Account>(`/api/v1/accounts/${params.id}`);
  } catch (e) {
    if (e instanceof ServerApiError && e.status === 404) notFound();
    throw e;
  }

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold">{account.nickname ?? account.accountType}</h1>
        <p className="text-xs text-slate-500 font-mono">{account.id}</p>
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
          <p className="mt-1"><Badge tone={account.status === 'ACTIVE' ? 'success' : account.status === 'FROZEN' ? 'warning' : 'neutral'}>{account.status}</Badge></p>
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

'use client';

import { useInfiniteQuery } from '@tanstack/react-query';
import { api } from '@/lib/api/endpoints';
import type { Transfer } from '@/lib/api/types';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { Skeleton } from '@/components/ui/Spinner';
import { ErrorState, EmptyState } from '@/components/ui/States';
import { formatMoney } from '@/lib/format/money';
import { formatDateTime } from '@/lib/format/date';

const PAGE_SIZE = 20;

interface Props { accountId: string; currency: string }

export function TransactionHistory({ accountId, currency }: Props) {
  const query = useInfiniteQuery<Transfer[]>({
    queryKey: ['transfers', accountId],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      api.transfers.list({
        accountId,
        limit: PAGE_SIZE,
        to: pageParam as string | undefined
      }),
    getNextPageParam: (last) => {
      if (!last || last.length < PAGE_SIZE) return undefined;
      return last[last.length - 1]?.createdAt;
    }
  });

  if (query.isLoading) {
    return (
      <div className="mt-3 space-y-2">
        {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-12" />)}
      </div>
    );
  }

  if (query.isError) {
    return <ErrorState className="mt-3" message={query.error instanceof Error ? query.error.message : 'Failed to load'} />;
  }

  const items = (query.data?.pages ?? []).flat();
  if (items.length === 0) {
    return <EmptyState className="mt-3" title="No transactions yet" />;
  }

  return (
    <div className="mt-3">
      <ul className="divide-y divide-slate-200 dark:divide-slate-800">
        {items.map((t) => {
          const outgoing = t.sourceAccountId === accountId;
          return (
            <li key={t.id} className="flex items-center justify-between gap-4 py-3">
              <div>
                <p className="font-mono text-xs text-slate-500">{t.id.slice(0, 8)}</p>
                <p className="text-sm">{t.reference ?? (outgoing ? 'Outgoing transfer' : 'Incoming transfer')}</p>
                <p className="text-xs text-slate-500">{formatDateTime(t.createdAt)}</p>
              </div>
              <div className="text-right">
                <p className={`font-mono text-sm ${outgoing ? 'text-red-600' : 'text-emerald-600'}`}>
                  {outgoing ? '-' : '+'}{formatMoney(t.amount, t.currency || currency)}
                </p>
                <Badge tone={t.status === 'COMPLETED' ? 'success' : t.status === 'FAILED' ? 'danger' : 'info'}>{t.status}</Badge>
              </div>
            </li>
          );
        })}
      </ul>
      {query.hasNextPage && (
        <div className="mt-4 text-center">
          <Button
            variant="secondary"
            size="sm"
            onClick={() => void query.fetchNextPage()}
            isLoading={query.isFetchingNextPage}
          >
            Load more
          </Button>
        </div>
      )}
    </div>
  );
}

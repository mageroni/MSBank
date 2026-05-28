'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api/endpoints';
import type { NotificationChannel, NotificationStatus } from '@/lib/api/types';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Skeleton } from '@/components/ui/Spinner';
import { ErrorState, EmptyState } from '@/components/ui/States';
import { formatDateTime } from '@/lib/format/date';

const channels: Array<{ value: NotificationChannel | ''; label: string }> = [
  { value: '', label: 'All channels' },
  { value: 'EMAIL', label: 'Email' },
  { value: 'SMS', label: 'SMS' },
  { value: 'WEBHOOK', label: 'Webhook' }
];
const statuses: Array<{ value: NotificationStatus | ''; label: string }> = [
  { value: '', label: 'All statuses' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'SENT', label: 'Sent' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'DEAD_LETTERED', label: 'Dead-lettered' }
];

export function NotificationList() {
  const [channel, setChannel] = useState<NotificationChannel | ''>('');
  const [status, setStatus] = useState<NotificationStatus | ''>('');

  const query = useQuery({
    queryKey: ['notifications', channel, status],
    queryFn: () => api.notifications.list({
      channel: channel || undefined,
      status: status || undefined,
      limit: 50
    })
  });

  return (
    <Card>
      <div className="mb-4 flex flex-wrap gap-3">
        <select
          aria-label="Filter by channel"
          className="h-9 rounded-md border border-slate-300 bg-white px-2 text-sm dark:border-slate-700 dark:bg-slate-900"
          value={channel}
          onChange={(e) => setChannel(e.target.value as NotificationChannel | '')}
        >
          {channels.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
        </select>
        <select
          aria-label="Filter by status"
          className="h-9 rounded-md border border-slate-300 bg-white px-2 text-sm dark:border-slate-700 dark:bg-slate-900"
          value={status}
          onChange={(e) => setStatus(e.target.value as NotificationStatus | '')}
        >
          {statuses.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
      </div>

      {query.isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-12" />)}
        </div>
      )}
      {query.isError && <ErrorState message={query.error instanceof Error ? query.error.message : 'Failed to load'} />}
      {query.isSuccess && (query.data.length === 0 ? (
        <EmptyState title="No notifications" />
      ) : (
        <ul className="divide-y divide-slate-200 dark:divide-slate-800">
          {query.data.map((n) => (
            <li key={n.id} className="flex items-center justify-between gap-4 py-3">
              <div>
                <p className="font-medium text-sm">{n.subject ?? n.templateKey}</p>
                <p className="text-xs text-slate-500">{n.channel} → {n.to}</p>
                <p className="text-xs text-slate-500">{formatDateTime(n.createdAt)}</p>
              </div>
              <Badge tone={n.status === 'SENT' ? 'success' : n.status === 'FAILED' || n.status === 'DEAD_LETTERED' ? 'danger' : 'info'}>
                {n.status}
              </Badge>
            </li>
          ))}
        </ul>
      ))}
    </Card>
  );
}

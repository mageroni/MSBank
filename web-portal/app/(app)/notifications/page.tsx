import { NotificationList } from '@/components/notifications/NotificationList';

export const dynamic = 'force-dynamic';

export default function NotificationsPage() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Notifications</h1>
      <NotificationList />
    </div>
  );
}

import { Card } from '@/components/ui/Card';
import { Skeleton } from '@/components/ui/Spinner';

export default function Loading() {
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

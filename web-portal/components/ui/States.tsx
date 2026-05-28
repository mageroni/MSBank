import { cn } from '@/lib/cn';

export function EmptyState({ title, description, className }: { title: string; description?: string; className?: string }) {
  return (
    <div className={cn('rounded-lg border border-dashed border-slate-300 p-8 text-center dark:border-slate-700', className)}>
      <p className="font-medium">{title}</p>
      {description && <p className="mt-1 text-sm text-slate-500">{description}</p>}
    </div>
  );
}

export function ErrorState({ message, className }: { message: string; className?: string }) {
  return (
    <div className={cn('rounded-lg border border-red-300 bg-red-50 p-4 text-sm text-red-800 dark:border-red-800 dark:bg-red-900/30 dark:text-red-200', className)} role="alert">
      {message}
    </div>
  );
}

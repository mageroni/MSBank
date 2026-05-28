import { cn } from '@/lib/cn';
import type { HTMLAttributes } from 'react';

type Tone = 'neutral' | 'success' | 'warning' | 'danger' | 'info';

const tones: Record<Tone, string> = {
  neutral: 'bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-200',
  success: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/50 dark:text-emerald-200',
  warning: 'bg-amber-100 text-amber-800 dark:bg-amber-900/50 dark:text-amber-200',
  danger: 'bg-red-100 text-red-800 dark:bg-red-900/50 dark:text-red-200',
  info: 'bg-sky-100 text-sky-800 dark:bg-sky-900/50 dark:text-sky-200'
};

export function Badge({
  tone = 'neutral', className, ...rest
}: HTMLAttributes<HTMLSpanElement> & { tone?: Tone }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
        tones[tone],
        className
      )}
      {...rest}
    />
  );
}

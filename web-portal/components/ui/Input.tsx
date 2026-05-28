'use client';

import { forwardRef, type InputHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  hint?: string;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { label, error, hint, id, className, ...rest }, ref
) {
  const inputId = id ?? rest.name;
  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label htmlFor={inputId} className="text-sm font-medium text-slate-700 dark:text-slate-300">
          {label}
        </label>
      )}
      <input
        ref={ref}
        id={inputId}
        aria-invalid={!!error}
        aria-describedby={error ? `${inputId}-error` : hint ? `${inputId}-hint` : undefined}
        className={cn(
          'h-10 rounded-md border border-slate-300 bg-white px-3 text-sm shadow-sm placeholder:text-slate-400',
          'dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100',
          error && 'border-red-500 focus-visible:ring-red-500',
          className
        )}
        {...rest}
      />
      {error ? (
        <p id={`${inputId}-error`} className="text-xs text-red-600">{error}</p>
      ) : hint ? (
        <p id={`${inputId}-hint`} className="text-xs text-slate-500">{hint}</p>
      ) : null}
    </div>
  );
});

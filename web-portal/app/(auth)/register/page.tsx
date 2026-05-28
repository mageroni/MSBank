'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import Link from 'next/link';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Card } from '@/components/ui/Card';
import { Logo } from '@/components/ui/Logo';
import { ErrorState } from '@/components/ui/States';
import { PUBLIC_API_BASE_URL } from '@/lib/config';

const schema = z.object({
  firstName: z.string().min(1, 'First name is required'),
  lastName: z.string().min(1, 'Last name is required'),
  email: z.string().email('Enter a valid email'),
  password: z.string().min(12, 'Password must be at least 12 characters')
});

type FormValues = z.infer<typeof schema>;

export default function RegisterPage() {
  const router = useRouter();
  const [submitError, setSubmitError] = useState<string | null>(null);
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<FormValues>();

  const onSubmit = handleSubmit(async (values) => {
    setSubmitError(null);
    const parsed = schema.safeParse(values);
    if (!parsed.success) {
      setSubmitError(parsed.error.issues[0]?.message ?? 'Invalid input');
      return;
    }
    try {
      const res = await fetch(`${PUBLIC_API_BASE_URL}/api/v1/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(parsed.data)
      });
      if (!res.ok) {
        const problem = await res.json().catch(() => ({ title: 'Registration failed' }));
        throw new Error(problem.title ?? 'Registration failed');
      }
      router.replace('/login');
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : 'Registration failed');
    }
  });

  return (
    <Card className="w-full max-w-md">
      <div className="mb-6 flex items-center gap-2">
        <Logo className="h-8 w-8" />
        <h1 className="text-xl font-semibold">Create your MS Bank account</h1>
      </div>
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        <div className="grid grid-cols-2 gap-3">
          <Input label="First name" {...register('firstName')} error={errors.firstName?.message} />
          <Input label="Last name" {...register('lastName')} error={errors.lastName?.message} />
        </div>
        <Input label="Email" type="email" autoComplete="email" {...register('email')} error={errors.email?.message} />
        <Input
          label="Password"
          type="password"
          autoComplete="new-password"
          {...register('password')}
          error={errors.password?.message}
          hint="At least 12 characters."
        />
        {submitError && <ErrorState message={submitError} />}
        <Button type="submit" isLoading={isSubmitting}>Create account</Button>
        <p className="text-center text-sm text-slate-500">
          Already have an account?{' '}
          <Link href="/login" className="font-medium text-brand-600 hover:underline">Sign in</Link>
        </p>
      </form>
    </Card>
  );
}

import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { useAuth } from '@/lib/auth/AuthProvider';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Card } from '@/components/ui/Card';
import { Logo } from '@/components/ui/Logo';
import { ErrorState } from '@/components/ui/States';

const schema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
  totpCode: z.string().optional()
});

type FormValues = z.infer<typeof schema>;

export function LoginPage() {
  const navigate = useNavigate();
  const { login } = useAuth();
  const [submitError, setSubmitError] = useState<string | null>(null);
  const { register, handleSubmit, formState } = useForm<FormValues>();

  const onSubmit = handleSubmit(async (values) => {
    setSubmitError(null);
    const parsed = schema.safeParse(values);
    if (!parsed.success) {
      setSubmitError(parsed.error.issues[0]?.message ?? 'Invalid input');
      return;
    }

    try {
      await login(parsed.data.email, parsed.data.password, parsed.data.totpCode);
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : 'Login failed');
    }
  });

  return (
    <Card className="w-full max-w-sm">
      <div className="mb-6 flex items-center gap-2">
        <Logo className="h-8 w-8" />
        <h1 className="text-xl font-semibold">Sign in to MS Bank</h1>
      </div>
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        <Input
          label="Email"
          type="email"
          autoComplete="email"
          {...register('email')}
          data-testid="email"
        />
        <Input
          label="Password"
          type="password"
          autoComplete="current-password"
          {...register('password')}
          data-testid="password"
        />
        <Input
          label="MFA code (if enabled)"
          inputMode="numeric"
          autoComplete="one-time-code"
          {...register('totpCode')}
        />
        {submitError && <ErrorState message={submitError} />}
        <Button type="submit" isLoading={formState.isSubmitting} data-testid="login-submit">
          Sign in
        </Button>
        <p className="text-center text-sm text-slate-500">
          No account?{' '}
          <Link to="/register" className="font-medium text-brand-600 hover:underline">
            Create one
          </Link>
        </p>
      </form>
    </Card>
  );
}

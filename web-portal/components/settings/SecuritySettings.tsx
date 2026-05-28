'use client';

import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { QRCodeSVG } from 'qrcode.react';
import { api } from '@/lib/api/endpoints';
import { ApiError } from '@/lib/api/client';
import type { MfaEnrollment } from '@/lib/api/types';
import { Card, CardTitle } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { ErrorState } from '@/components/ui/States';

const passwordSchema = z.object({
  currentPassword: z.string().min(1, 'Current password is required'),
  newPassword: z.string().min(12, 'New password must be at least 12 characters'),
  confirmPassword: z.string()
}).refine((d) => d.newPassword === d.confirmPassword, {
  path: ['confirmPassword'], message: 'Passwords do not match'
});

type PwdValues = z.infer<typeof passwordSchema>;

export function SecuritySettings() {
  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
      <ChangePasswordCard />
      <MfaEnrollCard />
    </div>
  );
}

function ChangePasswordCard() {
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<PwdValues>();

  const onSubmit = handleSubmit(async (values) => {
    setError(null); setSuccess(false);
    const parsed = passwordSchema.safeParse(values);
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? 'Invalid input');
      return;
    }
    setSuccess(true);
  });

  return (
    <Card>
      <CardTitle>Change password</CardTitle>
      <form onSubmit={onSubmit} className="mt-3 flex flex-col gap-3" noValidate>
        <Input label="Current password" type="password" autoComplete="current-password" {...register('currentPassword')} error={errors.currentPassword?.message} />
        <Input label="New password" type="password" autoComplete="new-password" hint="At least 12 characters." {...register('newPassword')} error={errors.newPassword?.message} />
        <Input label="Confirm new password" type="password" autoComplete="new-password" {...register('confirmPassword')} error={errors.confirmPassword?.message} />
        {error && <ErrorState message={error} />}
        {success && <p className="text-sm text-emerald-600">Password updated (demo — endpoint not yet implemented).</p>}
        <Button type="submit" isLoading={isSubmitting} className="self-start">Update password</Button>
      </form>
    </Card>
  );
}

function MfaEnrollCard() {
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onEnroll = async () => {
    setLoading(true); setError(null);
    try {
      const data = await api.auth.enrollTotp();
      setEnrollment(data);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not start enrollment');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card>
      <CardTitle>Multi-factor authentication (TOTP)</CardTitle>
      <p className="mt-2 text-sm text-slate-500">
        Enroll an authenticator app like Google Authenticator or 1Password.
      </p>
      {!enrollment && (
        <Button onClick={onEnroll} isLoading={loading} className="mt-4">Begin enrollment</Button>
      )}
      {error && <div className="mt-3"><ErrorState message={error} /></div>}
      {enrollment && (
        <div className="mt-4 space-y-3">
          <div className="flex justify-center rounded-md bg-white p-4">
            <QRCodeSVG value={enrollment.otpAuthUri} size={180} />
          </div>
          <div>
            <p className="text-xs text-slate-500">Manual secret</p>
            <p className="font-mono text-sm break-all">{enrollment.secret}</p>
          </div>
        </div>
      )}
    </Card>
  );
}

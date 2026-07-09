import { SecuritySettings } from '@/components/settings/SecuritySettings';

export function SecurityPage() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Security</h1>
      <SecuritySettings />
    </div>
  );
}

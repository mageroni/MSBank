import { TransferWizard } from '@/components/transfers/TransferWizard';

export function TransferPage() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">New transfer</h1>
      <TransferWizard />
    </div>
  );
}

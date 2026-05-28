import { TransferWizard } from '@/components/transfers/TransferWizard';

export const dynamic = 'force-dynamic';

export default function TransferPage() {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">New transfer</h1>
      <TransferWizard />
    </div>
  );
}

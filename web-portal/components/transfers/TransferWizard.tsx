'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';
import { api } from '@/lib/api/endpoints';
import { ApiError } from '@/lib/api/client';
import type { Account, Transfer, TransferStatus } from '@/lib/api/types';
import { TERMINAL_TRANSFER_STATUSES } from '@/lib/api/types';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Badge } from '@/components/ui/Badge';
import { Spinner, Skeleton } from '@/components/ui/Spinner';
import { ErrorState } from '@/components/ui/States';
import { formatMoney, majorToMinor } from '@/lib/format/money';

type Step = 1 | 2 | 3 | 4;

interface WizardState {
  sourceAccountId: string | null;
  destinationAccountId: string;
  amountMajor: string;
  reference: string;
}

const detailsSchema = z.object({
  destinationAccountId: z.string().uuid('Enter a valid destination account UUID'),
  amountMajor: z.string().refine((v) => Number(v) > 0, 'Amount must be greater than zero'),
  reference: z.string().max(140).optional()
});

export function TransferWizard() {
  const accountsQuery = useQuery({
    queryKey: ['accounts'],
    queryFn: () => api.accounts.list('ACTIVE')
  });

  const [step, setStep] = useState<Step>(1);
  const [state, setState] = useState<WizardState>({
    sourceAccountId: null,
    destinationAccountId: '',
    amountMajor: '',
    reference: ''
  });

  const idempotencyKeyRef = useRef<string>('');
  useEffect(() => {
    if (!idempotencyKeyRef.current) idempotencyKeyRef.current = crypto.randomUUID();
  }, []);

  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [transfer, setTransfer] = useState<Transfer | null>(null);

  const sourceAccount = useMemo<Account | undefined>(
    () => accountsQuery.data?.find((a) => a.id === state.sourceAccountId),
    [accountsQuery.data, state.sourceAccountId]
  );

  const submit = async () => {
    if (!sourceAccount) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const amount = majorToMinor(state.amountMajor, sourceAccount.currency);
      const created = await api.transfers.create(
        {
          sourceAccountId: sourceAccount.id,
          destinationAccountId: state.destinationAccountId,
          amount,
          currency: sourceAccount.currency,
          reference: state.reference || undefined
        },
        idempotencyKeyRef.current
      );
      setTransfer(created);
      setStep(4);
    } catch (err) {
      setSubmitError(err instanceof ApiError ? (err.problem?.detail ?? err.message) : 'Transfer failed');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card>
      <Stepper step={step} />
      <div className="mt-6">
        {step === 1 && (
          <Step1
            accountsLoading={accountsQuery.isLoading}
            accounts={accountsQuery.data ?? []}
            value={state.sourceAccountId}
            onChange={(id) => setState((s) => ({ ...s, sourceAccountId: id }))}
            onNext={() => state.sourceAccountId && setStep(2)}
          />
        )}
        {step === 2 && sourceAccount && (
          <Step2
            source={sourceAccount}
            state={state}
            onChange={setState}
            onBack={() => setStep(1)}
            onNext={() => {
              const parsed = detailsSchema.safeParse(state);
              if (!parsed.success) {
                setSubmitError(parsed.error.issues[0]?.message ?? 'Invalid input');
                return;
              }
              setSubmitError(null);
              setStep(3);
            }}
            submitError={submitError}
          />
        )}
        {step === 3 && sourceAccount && (
          <Step3
            source={sourceAccount}
            state={state}
            submitError={submitError}
            submitting={submitting}
            idempotencyKey={idempotencyKeyRef.current}
            onBack={() => setStep(2)}
            onSubmit={submit}
          />
        )}
        {step === 4 && transfer && <Step4Status transfer={transfer} onNew={() => location.reload()} />}
      </div>
    </Card>
  );
}

function Stepper({ step }: { step: Step }) {
  const labels = ['Source', 'Details', 'Review', 'Status'];
  return (
    <ol className="flex items-center gap-2 text-xs" aria-label="Wizard progress">
      {labels.map((label, i) => {
        const n = (i + 1) as Step;
        const active = step === n;
        const done = step > n;
        return (
          <li key={label} className="flex items-center gap-2">
            <span className={`flex h-6 w-6 items-center justify-center rounded-full text-xs font-semibold ${
              done ? 'bg-emerald-600 text-white' :
              active ? 'bg-brand-600 text-white' :
              'bg-slate-200 text-slate-600 dark:bg-slate-800 dark:text-slate-400'
            }`}>{n}</span>
            <span className={active ? 'font-medium' : 'text-slate-500'}>{label}</span>
            {n < 4 && <span className="text-slate-300">/</span>}
          </li>
        );
      })}
    </ol>
  );
}

function Step1({
  accountsLoading, accounts, value, onChange, onNext
}: {
  accountsLoading: boolean;
  accounts: Account[];
  value: string | null;
  onChange: (id: string) => void;
  onNext: () => void;
}) {
  if (accountsLoading) {
    return <div className="space-y-2">{Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-14" />)}</div>;
  }
  if (accounts.length === 0) {
    return <ErrorState message="No active accounts available to transfer from." />;
  }
  return (
    <div className="space-y-3">
      <p className="text-sm font-medium">Select source account</p>
      <ul className="space-y-2">
        {accounts.map((a) => (
          <li key={a.id}>
            <label className={`flex cursor-pointer items-center justify-between gap-4 rounded-md border p-3 ${
              value === a.id
                ? 'border-brand-500 ring-2 ring-brand-200 dark:ring-brand-900'
                : 'border-slate-200 dark:border-slate-800'
            }`}>
              <div className="flex items-center gap-3">
                <input
                  type="radio"
                  name="source"
                  value={a.id}
                  checked={value === a.id}
                  onChange={() => onChange(a.id)}
                  className="h-4 w-4 accent-brand-600"
                />
                <div>
                  <p className="text-sm font-medium">{a.nickname ?? a.accountType}</p>
                  <p className="font-mono text-xs text-slate-500">{a.id.slice(0, 8)}</p>
                </div>
              </div>
              <p className="font-mono text-sm">{formatMoney(a.balance, a.currency)}</p>
            </label>
          </li>
        ))}
      </ul>
      <div className="flex justify-end">
        <Button disabled={!value} onClick={onNext} data-testid="step1-next">Next</Button>
      </div>
    </div>
  );
}

function Step2({
  source, state, onChange, onBack, onNext, submitError
}: {
  source: Account;
  state: WizardState;
  onChange: (updater: (s: WizardState) => WizardState) => void;
  onBack: () => void;
  onNext: () => void;
  submitError: string | null;
}) {
  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-500">From: <span className="font-medium">{source.nickname ?? source.accountType}</span> ({formatMoney(source.balance, source.currency)})</p>
      <Input
        label="Destination account ID"
        placeholder="00000000-0000-0000-0000-000000000000"
        value={state.destinationAccountId}
        onChange={(e) => onChange((s) => ({ ...s, destinationAccountId: e.target.value }))}
        data-testid="destination"
      />
      <Input
        label={`Amount (${source.currency})`}
        type="number"
        step="0.01"
        min="0"
        value={state.amountMajor}
        onChange={(e) => onChange((s) => ({ ...s, amountMajor: e.target.value }))}
        data-testid="amount"
      />
      <Input
        label="Reference (optional)"
        maxLength={140}
        value={state.reference}
        onChange={(e) => onChange((s) => ({ ...s, reference: e.target.value }))}
      />
      {submitError && <ErrorState message={submitError} />}
      <div className="flex justify-between">
        <Button variant="secondary" onClick={onBack}>Back</Button>
        <Button onClick={onNext} data-testid="step2-next">Review</Button>
      </div>
    </div>
  );
}

function Step3({
  source, state, submitError, submitting, idempotencyKey, onBack, onSubmit
}: {
  source: Account;
  state: WizardState;
  submitError: string | null;
  submitting: boolean;
  idempotencyKey: string;
  onBack: () => void;
  onSubmit: () => void;
}) {
  const minor = (() => {
    try { return majorToMinor(state.amountMajor, source.currency); } catch { return 0; }
  })();
  return (
    <div className="space-y-4">
      <dl className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
        <div><dt className="text-slate-500">From</dt><dd className="font-medium">{source.nickname ?? source.accountType} ({source.id.slice(0, 8)})</dd></div>
        <div><dt className="text-slate-500">To</dt><dd className="font-mono">{state.destinationAccountId.slice(0, 8)}…</dd></div>
        <div><dt className="text-slate-500">Amount</dt><dd className="font-mono">{formatMoney(minor, source.currency)}</dd></div>
        <div><dt className="text-slate-500">Reference</dt><dd>{state.reference || '—'}</dd></div>
        <div className="sm:col-span-2"><dt className="text-slate-500">Idempotency-Key</dt><dd className="font-mono text-xs">{idempotencyKey}</dd></div>
      </dl>
      {submitError && <ErrorState message={submitError} />}
      <div className="flex justify-between">
        <Button variant="secondary" onClick={onBack} disabled={submitting}>Back</Button>
        <Button onClick={onSubmit} isLoading={submitting} data-testid="submit-transfer">Submit transfer</Button>
      </div>
    </div>
  );
}

function Step4Status({ transfer, onNew }: { transfer: Transfer; onNew: () => void }) {
  const [status, setStatus] = useState<TransferStatus>(transfer.status);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (TERMINAL_TRANSFER_STATUSES.includes(status)) return;
    let cancelled = false;
    const id = window.setInterval(async () => {
      try {
        const t = await api.transfers.get(transfer.id);
        if (cancelled) return;
        setStatus(t.status);
        if (TERMINAL_TRANSFER_STATUSES.includes(t.status)) {
          window.clearInterval(id);
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Polling error');
      }
    }, 1500);
    return () => { cancelled = true; window.clearInterval(id); };
  }, [transfer.id, status]);

  const tone = status === 'COMPLETED' ? 'success' : status === 'FAILED' || status === 'COMPENSATED' ? 'danger' : 'info';

  return (
    <div className="space-y-4 text-center">
      <p className="text-sm text-slate-500">Transfer ID</p>
      <p className="font-mono text-xs">{transfer.id}</p>
      <div className="flex items-center justify-center gap-2">
        {!TERMINAL_TRANSFER_STATUSES.includes(status) && <Spinner />}
        <Badge tone={tone} data-testid="transfer-status">{status}</Badge>
      </div>
      {error && <ErrorState message={error} />}
      <Button variant="secondary" onClick={onNew}>New transfer</Button>
    </div>
  );
}

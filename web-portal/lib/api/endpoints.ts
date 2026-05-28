import { fetcher } from './client';
import type {
  Account, DashboardPayload, MfaEnrollment, Notification, NotificationChannel,
  NotificationStatus, Transfer, TransferRequest, UUID, User
} from './types';

export const api = {
  auth: {
    me: () => fetcher<User>('/api/v1/auth/me'),
    enrollTotp: () => fetcher<MfaEnrollment>('/api/v1/auth/mfa/totp/enroll', { method: 'POST' })
  },
  accounts: {
    list: (status?: 'ACTIVE' | 'FROZEN' | 'CLOSED') =>
      fetcher<Account[]>('/api/v1/accounts', { query: { status } }),
    get: (id: UUID) => fetcher<Account>(`/api/v1/accounts/${id}`)
  },
  transfers: {
    list: (params: { accountId?: UUID; limit?: number; from?: string; to?: string } = {}) =>
      fetcher<Transfer[]>('/api/v1/transfers', { query: params }),
    get: (id: UUID) => fetcher<Transfer>(`/api/v1/transfers/${id}`),
    create: (req: TransferRequest, idempotencyKey: string) =>
      fetcher<Transfer>('/api/v1/transfers', {
        method: 'POST',
        body: req,
        headers: { 'Idempotency-Key': idempotencyKey }
      })
  },
  notifications: {
    list: (params: { channel?: NotificationChannel; status?: NotificationStatus; limit?: number } = {}) =>
      fetcher<Notification[]>('/api/v1/notifications', { query: params })
  },
  dashboard: {
    get: () => fetcher<DashboardPayload>('/bff/v1/dashboard')
  }
};

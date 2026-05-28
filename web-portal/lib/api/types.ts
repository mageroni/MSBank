export type UUID = string;
export type ISODate = string;

export interface User {
  id: UUID;
  email: string;
  firstName: string;
  lastName: string;
  roles: Array<'CUSTOMER' | 'ADMIN' | 'OPERATOR'>;
  mfaEnabled?: boolean;
  createdAt: ISODate;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
}

export interface LoginRequest {
  email: string;
  password: string;
  totpCode?: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
}

export type AccountType = 'CHECKING' | 'SAVINGS';
export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';

export interface Account {
  id: UUID;
  customerId: UUID;
  accountType: AccountType;
  status: AccountStatus;
  balance: number;
  availableBalance?: number;
  currency: string;
  nickname?: string;
  version: number;
  createdAt: ISODate;
  updatedAt?: ISODate;
}

export type TransferStatus =
  | 'PENDING' | 'RESERVED' | 'DEBITED' | 'CREDITED'
  | 'COMPLETED' | 'FAILED' | 'COMPENSATING' | 'COMPENSATED';

export const TERMINAL_TRANSFER_STATUSES: TransferStatus[] = [
  'COMPLETED', 'FAILED', 'COMPENSATED'
];

export interface Transfer {
  id: UUID;
  idempotencyKey?: UUID;
  sourceAccountId: UUID;
  destinationAccountId: UUID;
  amount: number;
  currency: string;
  reference?: string;
  status: TransferStatus;
  failureReason?: string;
  createdAt: ISODate;
  completedAt?: ISODate;
}

export interface TransferRequest {
  sourceAccountId: UUID;
  destinationAccountId: UUID;
  amount: number;
  currency: string;
  reference?: string;
}

export type NotificationChannel = 'EMAIL' | 'SMS' | 'WEBHOOK';
export type NotificationStatus = 'PENDING' | 'SENT' | 'FAILED' | 'DEAD_LETTERED';

export interface Notification {
  id: UUID;
  userId?: UUID;
  channel: NotificationChannel;
  to: string;
  subject?: string;
  templateKey: string;
  status: NotificationStatus;
  attempts?: number;
  lastError?: string;
  createdAt: ISODate;
  sentAt?: ISODate;
}

export interface DashboardPayload {
  accounts: Account[];
  recentTransfers: Transfer[];
  unreadNotifications: number;
  totalBalance: number;
  asOf: ISODate;
}

export interface MfaEnrollment {
  secret: string;
  otpAuthUri: string;
}

export interface Problem {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  traceId?: string;
}

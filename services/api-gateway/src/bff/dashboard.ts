import type { Request, Response, NextFunction, RequestHandler } from 'express';
import axios, { type AxiosInstance } from 'axios';
import axiosRetry from 'axios-retry';
import { createBreaker } from '../breakers/upstream.js';
import { CORRELATION_HEADER } from '../middleware/correlationId.js';
import { HttpProblem } from '../middleware/error.js';
import type { Metrics } from '../observability/metrics.js';

export interface DashboardDeps {
  accountsBaseUrl: string;
  transactionsBaseUrl: string;
  notificationsBaseUrl: string;
  metrics?: Metrics;
  httpClient?: AxiosInstance;
}

interface Account {
  id: string;
  balance?: number;
  [k: string]: unknown;
}

interface Transfer {
  id: string;
  [k: string]: unknown;
}

interface Notification {
  id: string;
  read?: boolean;
  [k: string]: unknown;
}

function buildClient(): AxiosInstance {
  const client = axios.create({ timeout: 5_000 });
  axiosRetry(client, {
    retries: 2,
    retryDelay: axiosRetry.exponentialDelay,
    retryCondition: (err) => {
      if (axiosRetry.isNetworkOrIdempotentRequestError(err)) return true;
      const status = err.response?.status;
      return typeof status === 'number' && status >= 500 && status < 600;
    },
  });
  return client;
}

function authHeaders(req: Request): Record<string, string> {
  const headers: Record<string, string> = {};
  const auth = req.header('authorization');
  if (auth) headers.authorization = auth;
  if (req.correlationId) headers[CORRELATION_HEADER] = req.correlationId;
  if (req.user) {
    headers['x-user-id'] = req.user.sub;
    if (req.user.email) headers['x-user-email'] = req.user.email;
    if (req.user.roles.length > 0) headers['x-user-roles'] = req.user.roles.join(',');
  }
  return headers;
}

function asArray<T>(data: unknown, key: string): T[] {
  if (Array.isArray(data)) return data as T[];
  if (data && typeof data === 'object') {
    const inner = (data as Record<string, unknown>)[key];
    if (Array.isArray(inner)) return inner as T[];
  }
  return [];
}

export function dashboardRoute(deps: DashboardDeps): RequestHandler {
  const client = deps.httpClient ?? buildClient();

  const fetchAccounts = async (req: Request): Promise<Account[]> => {
    const res = await client.get(`${deps.accountsBaseUrl}/api/v1/accounts`, {
      headers: authHeaders(req),
    });
    return asArray<Account>(res.data, 'accounts');
  };

  const fetchTransfers = async (req: Request): Promise<Transfer[]> => {
    const res = await client.get(`${deps.transactionsBaseUrl}/api/v1/transfers`, {
      headers: authHeaders(req),
      params: { limit: 10 },
    });
    return asArray<Transfer>(res.data, 'transfers');
  };

  const fetchNotifications = async (req: Request): Promise<Notification[]> => {
    const res = await client.get(`${deps.notificationsBaseUrl}/api/v1/notifications`, {
      headers: authHeaders(req),
      params: { limit: 20 },
    });
    return asArray<Notification>(res.data, 'notifications');
  };

  const accountsBreaker = createBreaker(fetchAccounts, { name: 'accounts-bff' }, deps.metrics);
  const transfersBreaker = createBreaker(fetchTransfers, { name: 'transactions-bff' }, deps.metrics);
  const notificationsBreaker = createBreaker(fetchNotifications, { name: 'notifications-bff' }, deps.metrics);

  return async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    try {
      const [accounts, recentTransfers, notifications] = await Promise.all([
        accountsBreaker.fire(req),
        transfersBreaker.fire(req),
        notificationsBreaker.fire(req),
      ]);

      const totalBalance = accounts.reduce<number>((sum, a) => {
        const bal = typeof a.balance === 'number' ? a.balance : 0;
        return sum + bal;
      }, 0);

      const unreadNotifications = notifications.filter((n) => n.read !== true).length;

      res.json({
        accounts,
        recentTransfers,
        unreadNotifications,
        totalBalance,
        asOf: new Date().toISOString(),
      });
    } catch (err) {
      if (err instanceof HttpProblem) return next(err);
      const detail = err instanceof Error ? err.message : 'Failed to assemble dashboard';
      next(new HttpProblem(502, 'Bad Gateway', detail));
    }
  };
}

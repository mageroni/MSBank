import { Router, type Request, type Response, type RequestHandler } from 'express';
import type Redis from 'ioredis';
import axios from 'axios';
import type { Store } from 'express-rate-limit';
import type { AppConfig } from './config.js';
import type { Metrics } from './observability/metrics.js';
import { jwtAuth } from './middleware/auth.js';
import { globalRateLimit, loginRateLimit } from './middleware/rateLimit.js';
import { idempotency } from './middleware/idempotency.js';
import { HttpProblem } from './middleware/error.js';
import { authProxy } from './proxies/authProxy.js';
import { accountsProxy } from './proxies/accountsProxy.js';
import { transactionsProxy } from './proxies/transactionsProxy.js';
import { notificationsProxy } from './proxies/notificationsProxy.js';
import { dashboardRoute } from './bff/dashboard.js';
import type { createRemoteJWKSet } from 'jose';

export interface RouteDeps {
  config: AppConfig;
  redis: Redis;
  metrics: Metrics;
  /** Test override: pre-built JWKS. */
  jwks?: ReturnType<typeof createRemoteJWKSet>;
  /** Test override: factory for rate-limit store (per limiter). */
  rateLimitStoreFactory?: (keyPrefix: string) => Store;
}

const PUBLIC_AUTH_PATHS: ReadonlyArray<string> = [
  '/api/v1/auth/register',
  '/api/v1/auth/login',
  '/api/v1/auth/refresh',
];

export function buildRoutes(deps: RouteDeps): Router {
  const { config, redis, metrics } = deps;
  const router = Router();

  router.get('/healthz', (_req: Request, res: Response) => {
    res.json({ status: 'ok', service: 'api-gateway' });
  });

  router.get('/readyz', async (_req: Request, res: Response) => {
    const checks: Record<string, 'ok' | 'fail'> = {};
    try {
      const pong = await redis.ping();
      checks.redis = pong === 'PONG' ? 'ok' : 'fail';
    } catch {
      checks.redis = 'fail';
    }
    try {
      const r = await axios.get(`${config.upstreams.auth}/healthz`, { timeout: 1_000 });
      checks.auth = r.status >= 200 && r.status < 300 ? 'ok' : 'fail';
    } catch {
      checks.auth = 'fail';
    }
    const ready = Object.values(checks).every((v) => v === 'ok');
    res.status(ready ? 200 : 503).json({ status: ready ? 'ready' : 'not_ready', checks });
  });

  router.get('/metrics', async (_req: Request, res: Response) => {
    res.setHeader('content-type', metrics.registry.contentType);
    res.send(await metrics.registry.metrics());
  });

  router.use('/internal', (_req, _res, next) => {
    next(new HttpProblem(404, 'Not Found', 'Internal routes are not exposed'));
  });

  const global = globalRateLimit({
    redis,
    max: config.rateLimitPerMin,
    keyPrefix: 'rl:global',
    store: deps.rateLimitStoreFactory?.('rl:global'),
  });
  router.use(global);

  const auth: RequestHandler = jwtAuth({
    jwksUri: config.jwt.jwksUri,
    issuer: config.jwt.issuer,
    audience: config.jwt.audience,
    jwks: deps.jwks,
    publicPaths: PUBLIC_AUTH_PATHS,
  });

  const login = loginRateLimit({
    redis,
    max: 10,
    keyPrefix: 'rl:login',
    store: deps.rateLimitStoreFactory?.('rl:login'),
  });
  router.post('/api/v1/auth/login', login);

  const authP = authProxy(config.upstreams.auth, metrics);
  router.use('/api/v1/auth', auth, authP.handler);

  const accountsP = accountsProxy(config.upstreams.accounts, metrics);
  router.use('/api/v1/accounts', auth, accountsP.handler);

  const transactionsP = transactionsProxy(config.upstreams.transactions, metrics);
  const idem = idempotency({ redis });
  router.post('/api/v1/transfers', auth, idem, transactionsP.handler);
  router.use('/api/v1/transfers', auth, transactionsP.handler);

  const notificationsP = notificationsProxy(config.upstreams.notifications, metrics);
  router.use('/api/v1/notifications', auth, notificationsP.handler);

  router.get(
    '/bff/v1/dashboard',
    auth,
    dashboardRoute({
      accountsBaseUrl: config.upstreams.accounts,
      transactionsBaseUrl: config.upstreams.transactions,
      notificationsBaseUrl: config.upstreams.notifications,
      metrics,
    }),
  );

  return router;
}

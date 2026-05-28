import express, { type Express } from 'express';
import helmet from 'helmet';
import cors from 'cors';
import compression from 'compression';
import pinoHttp from 'pino-http';
import { randomUUID } from 'node:crypto';
import type Redis from 'ioredis';
import type { createRemoteJWKSet } from 'jose';
import type { Store } from 'express-rate-limit';
import type { AppConfig } from './config.js';
import { correlationId, CORRELATION_HEADER } from './middleware/correlationId.js';
import { errorHandler, notFoundHandler } from './middleware/error.js';
import { getLogger } from './observability/logger.js';
import { createMetrics, type Metrics } from './observability/metrics.js';
import { buildRoutes } from './routes.js';

export interface AppDeps {
  config: AppConfig;
  redis: Redis;
  metrics?: Metrics;
  jwks?: ReturnType<typeof createRemoteJWKSet>;
  rateLimitStoreFactory?: (keyPrefix: string) => Store;
}

export function createApp(deps: AppDeps): Express {
  const log = getLogger(deps.config.logLevel);
  const metrics = deps.metrics ?? createMetrics();

  const app = express();
  app.disable('x-powered-by');
  // Trust one hop (typical LB / nginx in front). Avoids express-rate-limit's
  // ERR_ERL_PERMISSIVE_TRUST_PROXY when set to `true`.
  app.set('trust proxy', 1);

  app.use(correlationId());

  app.use(
    pinoHttp({
      logger: log,
      genReqId: (req, res) => {
        const id = (req.headers[CORRELATION_HEADER] as string | undefined) ?? randomUUID();
        res.setHeader(CORRELATION_HEADER, id);
        return id;
      },
      customLogLevel: (_req, res, err) => {
        if (err || res.statusCode >= 500) return 'error';
        if (res.statusCode >= 400) return 'warn';
        return 'info';
      },
    }),
  );

  app.use(helmet());
  app.use(
    cors({
      origin: deps.config.corsOrigins.length > 0 ? deps.config.corsOrigins : false,
      credentials: true,
    }),
  );
  app.use(compression());
  app.use(express.json({ limit: '1mb' }));
  app.use(express.urlencoded({ extended: false, limit: '1mb' }));

  app.use(buildRoutes({
    config: deps.config,
    redis: deps.redis,
    metrics,
    jwks: deps.jwks,
    rateLimitStoreFactory: deps.rateLimitStoreFactory,
  }));

  app.use(notFoundHandler());
  app.use(errorHandler());

  return app;
}

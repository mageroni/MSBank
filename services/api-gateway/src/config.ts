import { z } from 'zod';

const EnvSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  LOG_LEVEL: z.string().default('info'),

  GATEWAY_PORT: z.coerce.number().int().nonnegative().default(8080),
  GATEWAY_RATE_LIMIT_PER_MIN: z.coerce.number().int().positive().default(120),
  GATEWAY_CORS_ORIGINS: z.string().default('http://localhost:3000'),

  AUTH_SERVICE_URL: z.string().url(),
  ACCOUNTS_SERVICE_URL: z.string().url(),
  TRANSACTIONS_SERVICE_URL: z.string().url(),
  NOTIFICATIONS_SERVICE_URL: z.string().url(),

  JWT_ISSUER: z.string().url(),
  JWT_AUDIENCE: z.string().min(1),
  JWT_JWKS_URI: z.string().url().optional(),

  REDIS_URL: z.string().min(1),

  OTEL_EXPORTER_OTLP_ENDPOINT: z.string().optional(),
  OTEL_SERVICE_NAME: z.string().default('api-gateway'),
});

export type AppConfig = Readonly<{
  nodeEnv: 'development' | 'test' | 'production';
  logLevel: string;
  port: number;
  rateLimitPerMin: number;
  corsOrigins: string[];
  upstreams: Readonly<{
    auth: string;
    accounts: string;
    transactions: string;
    notifications: string;
  }>;
  jwt: Readonly<{
    issuer: string;
    audience: string;
    jwksUri: string;
  }>;
  redisUrl: string;
  otel: Readonly<{
    endpoint?: string;
    serviceName: string;
  }>;
}>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = EnvSchema.parse(env);
  const issuer = parsed.JWT_ISSUER.replace(/\/+$/, '');
  return Object.freeze({
    nodeEnv: parsed.NODE_ENV,
    logLevel: parsed.LOG_LEVEL,
    port: parsed.GATEWAY_PORT,
    rateLimitPerMin: parsed.GATEWAY_RATE_LIMIT_PER_MIN,
    corsOrigins: parsed.GATEWAY_CORS_ORIGINS.split(',').map((s) => s.trim()).filter(Boolean),
    upstreams: Object.freeze({
      auth: parsed.AUTH_SERVICE_URL.replace(/\/+$/, ''),
      accounts: parsed.ACCOUNTS_SERVICE_URL.replace(/\/+$/, ''),
      transactions: parsed.TRANSACTIONS_SERVICE_URL.replace(/\/+$/, ''),
      notifications: parsed.NOTIFICATIONS_SERVICE_URL.replace(/\/+$/, ''),
    }),
    jwt: Object.freeze({
      issuer,
      audience: parsed.JWT_AUDIENCE,
      jwksUri: parsed.JWT_JWKS_URI ?? `${issuer}/.well-known/jwks.json`,
    }),
    redisUrl: parsed.REDIS_URL,
    otel: Object.freeze({
      endpoint: parsed.OTEL_EXPORTER_OTLP_ENDPOINT,
      serviceName: parsed.OTEL_SERVICE_NAME,
    }),
  });
}

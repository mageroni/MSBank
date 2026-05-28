import { describe, it, expect, beforeAll } from 'vitest';
import request from 'supertest';
import RedisMock from 'ioredis-mock';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { createLocalJWKS } from './helpers/jwks.js';
import { generateTestKeys } from './helpers/tokens.js';
import { memoryRateLimitFactory } from './helpers/rateLimitStore.js';

const env = {
  NODE_ENV: 'test',
  GATEWAY_PORT: '0',
  GATEWAY_RATE_LIMIT_PER_MIN: '3',
  GATEWAY_CORS_ORIGINS: 'http://localhost:3000',
  AUTH_SERVICE_URL: 'http://auth.test',
  ACCOUNTS_SERVICE_URL: 'http://accounts.test',
  TRANSACTIONS_SERVICE_URL: 'http://transactions.test',
  NOTIFICATIONS_SERVICE_URL: 'http://notifications.test',
  JWT_ISSUER: 'https://issuer.test',
  JWT_AUDIENCE: 'msbank',
  REDIS_URL: 'redis://localhost:6379',
};

describe('rate limit', () => {
  let app: ReturnType<typeof createApp>;

  beforeAll(async () => {
    const keys = await generateTestKeys();
    const config = loadConfig(env);
    const redis = new RedisMock();
    app = createApp({
      config,
      redis: redis as unknown as import('ioredis').default,
      jwks: createLocalJWKS([keys.publicJwk]),
      rateLimitStoreFactory: memoryRateLimitFactory(),
    });
  });

  it('returns 429 after threshold is exceeded', async () => {
    let last = 200;
    let lastHeaders: Record<string, string | string[] | undefined> = {};
    for (let i = 0; i < 6; i += 1) {
      const res = await request(app)
        .post('/api/v1/auth/login')
        .send({ email: 'a@b', password: 'x' });
      last = res.status;
      lastHeaders = res.headers;
    }
    if (last !== 429) {
      // eslint-disable-next-line no-console
      console.error('DEBUG headers', lastHeaders);
    }
    expect(last).toBe(429);
  });
});

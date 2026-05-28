import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import request from 'supertest';
import RedisMock from 'ioredis-mock';
import nock from 'nock';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { createLocalJWKS } from './helpers/jwks.js';
import { generateTestKeys, signTestToken } from './helpers/tokens.js';
import { memoryRateLimitFactory } from './helpers/rateLimitStore.js';

const env = {
  NODE_ENV: 'test',
  GATEWAY_PORT: '0',
  GATEWAY_RATE_LIMIT_PER_MIN: '1000',
  GATEWAY_CORS_ORIGINS: 'http://localhost:3000',
  AUTH_SERVICE_URL: 'http://auth.test',
  ACCOUNTS_SERVICE_URL: 'http://accounts.test',
  TRANSACTIONS_SERVICE_URL: 'http://transactions.test',
  NOTIFICATIONS_SERVICE_URL: 'http://notifications.test',
  JWT_ISSUER: 'https://issuer.test',
  JWT_AUDIENCE: 'msbank',
  REDIS_URL: 'redis://localhost:6379',
};

describe('BFF dashboard aggregator', () => {
  let app: ReturnType<typeof createApp>;
  let token: string;

  beforeAll(async () => {
    const keys = await generateTestKeys();
    token = await signTestToken(keys.privateKey, {
      sub: 'user-1',
      iss: 'https://issuer.test',
      aud: 'msbank',
      email: 'u@test',
    });

    const config = loadConfig(env);
    const redis = new RedisMock();
    app = createApp({
      config,
      redis: redis as unknown as import('ioredis').default,
      jwks: createLocalJWKS([keys.publicJwk]),
      rateLimitStoreFactory: memoryRateLimitFactory(),
    });
  });

  afterEach(() => {
    nock.cleanAll();
  });

  afterAll(() => {
    nock.restore();
  });

  it('composes accounts + recent transfers + notifications in parallel', async () => {
    nock('http://accounts.test')
      .get('/api/v1/accounts')
      .reply(200, { accounts: [
        { id: 'a1', balance: 100 },
        { id: 'a2', balance: 250 },
      ] });

    nock('http://transactions.test')
      .get('/api/v1/transfers')
      .query({ limit: 10 })
      .reply(200, { transfers: [{ id: 't1' }, { id: 't2' }] });

    nock('http://notifications.test')
      .get('/api/v1/notifications')
      .query({ limit: 20 })
      .reply(200, { notifications: [
        { id: 'n1', read: false },
        { id: 'n2', read: true },
        { id: 'n3', read: false },
      ] });

    const res = await request(app)
      .get('/bff/v1/dashboard')
      .set('authorization', `Bearer ${token}`);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      accounts: [{ id: 'a1' }, { id: 'a2' }],
      recentTransfers: [{ id: 't1' }, { id: 't2' }],
      unreadNotifications: 2,
      totalBalance: 350,
    });
    expect(typeof res.body.asOf).toBe('string');
  });

  it('returns 502/503 problem+json when an upstream fails', async () => {
    nock('http://accounts.test').get('/api/v1/accounts').times(3).reply(500, 'boom');
    nock('http://transactions.test').get('/api/v1/transfers').query(true).reply(200, []);
    nock('http://notifications.test').get('/api/v1/notifications').query(true).reply(200, []);

    const res = await request(app)
      .get('/bff/v1/dashboard')
      .set('authorization', `Bearer ${token}`);

    expect([502, 503]).toContain(res.status);
    expect(res.headers['content-type']).toMatch(/application\/problem\+json/);
  });
});

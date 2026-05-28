import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import request from 'supertest';
import RedisMock from 'ioredis-mock';
import { SignJWT, generateKeyPair, exportJWK, type KeyLike } from 'jose';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { createLocalJWKS } from './helpers/jwks.js';
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

describe('JWT auth middleware', () => {
  let app: ReturnType<typeof createApp>;
  let privateKey: KeyLike;
  let publicKey: KeyLike;

  beforeAll(async () => {
    const kp = await generateKeyPair('RS256');
    privateKey = kp.privateKey;
    publicKey = kp.publicKey;
    const jwk = await exportJWK(publicKey);
    jwk.kid = 'test-key';
    jwk.alg = 'RS256';
    jwk.use = 'sig';

    const config = loadConfig(env);
    const redis = new RedisMock();
    app = createApp({
      config,
      redis: redis as unknown as import('ioredis').default,
      jwks: createLocalJWKS([jwk]),
      rateLimitStoreFactory: memoryRateLimitFactory(),
    });
  });

  afterAll(() => { /* nothing */ });

  it('rejects requests without a bearer token', async () => {
    const res = await request(app).get('/api/v1/accounts');
    expect(res.status).toBe(401);
    expect(res.headers['content-type']).toMatch(/application\/problem\+json/);
    expect(res.body).toMatchObject({ status: 401, title: 'Unauthorized' });
  });

  it('rejects invalid tokens', async () => {
    const res = await request(app)
      .get('/api/v1/accounts')
      .set('authorization', 'Bearer not-a-jwt');
    expect(res.status).toBe(401);
    expect(res.body.status).toBe(401);
  });

  it('rejects tokens with wrong audience', async () => {
    const token = await new SignJWT({ roles: ['user'] })
      .setProtectedHeader({ alg: 'RS256', kid: 'test-key' })
      .setIssuer('https://issuer.test')
      .setAudience('someone-else')
      .setSubject('user-1')
      .setIssuedAt()
      .setExpirationTime('5m')
      .sign(privateKey);
    const res = await request(app)
      .get('/api/v1/accounts')
      .set('authorization', `Bearer ${token}`);
    expect(res.status).toBe(401);
  });

  it('allows public auth endpoints without token', async () => {
    // login is public; will attempt to proxy and fail (no upstream),
    // but should NOT be 401.
    const res = await request(app)
      .post('/api/v1/auth/login')
      .send({ email: 'x', password: 'y' });
    expect(res.status).not.toBe(401);
  });
});

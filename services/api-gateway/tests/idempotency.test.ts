import { describe, it, expect, beforeEach } from 'vitest';
import express from 'express';
import request from 'supertest';
import RedisMock from 'ioredis-mock';
import { idempotency } from '../src/middleware/idempotency.js';
import { errorHandler } from '../src/middleware/error.js';
import type Redis from 'ioredis';

function buildApp(redis: Redis) {
  const app = express();
  app.use(express.json());
  app.use((req, _res, next) => {
    req.user = { sub: 'user-1', roles: ['user'] };
    next();
  });
  let counter = 0;
  app.post('/transfers', idempotency({ redis }), (_req, res) => {
    counter += 1;
    res.status(201).json({ id: `t-${counter}`, amount: 100 });
  });
  app.use(errorHandler());
  return app;
}

describe('idempotency middleware', () => {
  let redis: Redis;

  beforeEach(() => {
    redis = new RedisMock() as unknown as Redis;
  });

  it('requires Idempotency-Key header', async () => {
    const app = buildApp(redis);
    const res = await request(app).post('/transfers').send({ amount: 100 });
    expect(res.status).toBe(400);
  });

  it('replays the original response for a repeated key', async () => {
    const app = buildApp(redis);
    const first = await request(app)
      .post('/transfers')
      .set('Idempotency-Key', 'abc-123')
      .send({ amount: 100 });
    expect(first.status).toBe(201);
    expect(first.body).toMatchObject({ id: 't-1' });

    const second = await request(app)
      .post('/transfers')
      .set('Idempotency-Key', 'abc-123')
      .send({ amount: 100 });
    expect(second.status).toBe(201);
    expect(second.body).toMatchObject({ id: 't-1' });
    expect(second.headers['idempotent-replay']).toBe('true');
  });

  it('returns 409 when the same key is still in-flight', async () => {
    const app = buildApp(redis);
    await redis.set(
      'idem:user-1:in-flight-key',
      JSON.stringify({ status: 'in_flight' }),
      'EX',
      60,
    );
    const res = await request(app)
      .post('/transfers')
      .set('Idempotency-Key', 'in-flight-key')
      .send({ amount: 50 });
    expect(res.status).toBe(409);
  });
});

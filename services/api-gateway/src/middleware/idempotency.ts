import type { Request, Response, NextFunction, RequestHandler } from 'express';
import type Redis from 'ioredis';
import { HttpProblem } from './error.js';

export interface IdempotencyDeps {
  redis: Redis;
  ttlSeconds?: number;
  keyPrefix?: string;
}

interface StoredResponse {
  status: 'in_flight' | 'completed';
  httpStatus?: number;
  headers?: Record<string, string>;
  body?: unknown;
}

const TTL_DEFAULT = 60 * 60 * 24; // 24h
const PREFIX_DEFAULT = 'idem';

function redisKey(prefix: string, userId: string, key: string): string {
  return `${prefix}:${userId}:${key}`;
}

export function idempotency(deps: IdempotencyDeps): RequestHandler {
  const ttl = deps.ttlSeconds ?? TTL_DEFAULT;
  const prefix = deps.keyPrefix ?? PREFIX_DEFAULT;

  return async (req: Request, res: Response, next: NextFunction): Promise<void> => {
    try {
      if (req.method !== 'POST') return next();

      const idemKey = req.header('idempotency-key');
      if (!idemKey || idemKey.trim().length === 0) {
        throw new HttpProblem(400, 'Bad Request', 'Missing Idempotency-Key header');
      }
      const userId = req.user?.sub;
      if (!userId) {
        throw new HttpProblem(401, 'Unauthorized', 'Missing authenticated user');
      }

      const rkey = redisKey(prefix, userId, idemKey.trim());
      const inFlight: StoredResponse = { status: 'in_flight' };
      const setRes = await deps.redis.set(rkey, JSON.stringify(inFlight), 'EX', ttl, 'NX');

      if (setRes !== 'OK') {
        const raw = await deps.redis.get(rkey);
        if (!raw) {
          throw new HttpProblem(409, 'Conflict', 'Idempotency key in flight');
        }
        let parsed: StoredResponse;
        try {
          parsed = JSON.parse(raw) as StoredResponse;
        } catch {
          throw new HttpProblem(409, 'Conflict', 'Corrupt idempotency record');
        }
        if (parsed.status === 'in_flight') {
          throw new HttpProblem(409, 'Conflict', 'Idempotent request already in flight');
        }
        if (parsed.status === 'completed') {
          if (parsed.headers) {
            for (const [name, value] of Object.entries(parsed.headers)) {
              res.setHeader(name, value);
            }
          }
          res.setHeader('idempotent-replay', 'true');
          res.status(parsed.httpStatus ?? 200).json(parsed.body ?? null);
          return;
        }
      }

      const chunks: Buffer[] = [];
      const originalWrite = res.write.bind(res);
      const originalEnd = res.end.bind(res);

      res.write = ((chunk: unknown, ...rest: unknown[]): boolean => {
        if (chunk) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
        return (originalWrite as (...a: unknown[]) => boolean)(chunk, ...rest);
      }) as typeof res.write;

      res.end = ((chunk?: unknown, ...rest: unknown[]): Response => {
        if (chunk) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
        const buf = Buffer.concat(chunks);
        let body: unknown = buf.toString('utf8');
        const ct = res.getHeader('content-type');
        if (typeof ct === 'string' && ct.includes('application/json')) {
          try {
            body = body === '' ? null : JSON.parse(body as string);
          } catch {
            // keep raw
          }
        }
        const record: StoredResponse = {
          status: 'completed',
          httpStatus: res.statusCode,
          headers: { 'content-type': typeof ct === 'string' ? ct : 'application/json' },
          body,
        };
        deps.redis
          .set(rkey, JSON.stringify(record), 'EX', ttl)
          .catch((e: unknown) => req.log?.warn({ err: e }, 'failed to persist idempotent response'));
        return (originalEnd as (...a: unknown[]) => Response)(chunk, ...rest);
      }) as typeof res.end;

      next();
    } catch (err) {
      next(err);
    }
  };
}

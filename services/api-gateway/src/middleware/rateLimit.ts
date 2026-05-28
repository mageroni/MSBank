import type { Request, Response, NextFunction, RequestHandler } from 'express';
import rateLimit, { type Options, type Store } from 'express-rate-limit';
import RedisStore, { type RedisReply } from 'rate-limit-redis';
import type Redis from 'ioredis';
import { HttpProblem } from './error.js';

export interface RateLimitDeps {
  redis: Redis;
  windowMs?: number;
  max: number;
  keyPrefix: string;
  /** Test hook: pre-built store (skips Redis). */
  store?: Store;
}

function buildStore(deps: RateLimitDeps): Store {
  if (deps.store) return deps.store;
  type RedisLike = {
    call?: (cmd: string, ...args: string[]) => Promise<unknown>;
    sendCommand?: (cmd: string, ...args: string[]) => Promise<unknown>;
  };
  const r = deps.redis as unknown as RedisLike;

  return new RedisStore({
    prefix: `${deps.keyPrefix}:`,
    sendCommand: async (...args: string[]): Promise<RedisReply> => {
      const [cmd, ...rest] = args;
      if (!cmd) throw new Error('rate-limit-redis: empty command');
      if (typeof r.call === 'function') {
        return (await r.call(cmd, ...rest)) as RedisReply;
      }
      if (typeof r.sendCommand === 'function') {
        return (await r.sendCommand(cmd, ...rest)) as RedisReply;
      }
      throw new Error(`rate-limit-redis: redis client has no call/sendCommand for ${cmd}`);
    },
  });
}

function commonOpts(deps: RateLimitDeps): Partial<Options> {
  return {
    windowMs: deps.windowMs ?? 60_000,
    limit: deps.max,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    store: buildStore(deps),
    handler: (_req: Request, _res: Response, next: NextFunction) => {
      next(
        new HttpProblem(
          429,
          'Too Many Requests',
          `Rate limit exceeded (${deps.max}/${(deps.windowMs ?? 60_000) / 1000}s)`,
        ),
      );
    },
  };
}

export function globalRateLimit(deps: RateLimitDeps): RequestHandler {
  return rateLimit(commonOpts(deps));
}

export function loginRateLimit(deps: RateLimitDeps): RequestHandler {
  return rateLimit({
    ...commonOpts(deps),
    keyGenerator: (req: Request): string => `login:${req.ip ?? 'unknown'}`,
  });
}

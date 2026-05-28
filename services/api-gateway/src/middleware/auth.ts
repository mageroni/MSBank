import type { Request, Response, NextFunction, RequestHandler } from 'express';
import { jwtVerify, createRemoteJWKSet, type JWTPayload } from 'jose';
import { HttpProblem } from './error.js';

export interface AuthUser {
  sub: string;
  roles: string[];
  email?: string;
}

declare module 'express-serve-static-core' {
  interface Request {
    user?: AuthUser;
  }
}

export interface AuthConfig {
  jwksUri: string;
  issuer: string;
  audience: string;
  /** Optional pre-built JWKS (for tests). */
  jwks?: ReturnType<typeof createRemoteJWKSet>;
  /** Paths that bypass JWT validation. Compared on req.path. */
  publicPaths?: ReadonlyArray<string>;
}

function extractRoles(payload: JWTPayload): string[] {
  const raw = (payload as Record<string, unknown>)['roles'];
  if (Array.isArray(raw)) {
    return raw.filter((r): r is string => typeof r === 'string');
  }
  if (typeof raw === 'string') return [raw];
  return [];
}

function extractEmail(payload: JWTPayload): string | undefined {
  const raw = (payload as Record<string, unknown>)['email'];
  return typeof raw === 'string' ? raw : undefined;
}

export function jwtAuth(cfg: AuthConfig): RequestHandler {
  const jwks = cfg.jwks ?? createRemoteJWKSet(new URL(cfg.jwksUri), {
    cacheMaxAge: 10 * 60 * 1000,
    cooldownDuration: 30 * 1000,
  });
  const publicPaths = new Set(cfg.publicPaths ?? []);

  return async (req: Request, _res: Response, next: NextFunction): Promise<void> => {
    try {
      const fullPath = (req.originalUrl.split('?')[0] ?? req.path);
      if (publicPaths.has(fullPath) || publicPaths.has(req.path)) {
        return next();
      }
      const header = req.header('authorization');
      if (!header || !/^Bearer\s+/i.test(header)) {
        throw new HttpProblem(401, 'Unauthorized', 'Missing bearer token');
      }
      const token = header.replace(/^Bearer\s+/i, '').trim();
      if (!token) {
        throw new HttpProblem(401, 'Unauthorized', 'Empty bearer token');
      }

      const { payload } = await jwtVerify(token, jwks, {
        issuer: cfg.issuer,
        audience: cfg.audience,
        algorithms: ['RS256'],
      });

      if (typeof payload.sub !== 'string') {
        throw new HttpProblem(401, 'Unauthorized', 'Token missing sub claim');
      }

      req.user = {
        sub: payload.sub,
        roles: extractRoles(payload),
        email: extractEmail(payload),
      };
      next();
    } catch (err) {
      if (err instanceof HttpProblem) return next(err);
      const detail = err instanceof Error ? err.message : 'Invalid token';
      next(new HttpProblem(401, 'Unauthorized', detail));
    }
  };
}

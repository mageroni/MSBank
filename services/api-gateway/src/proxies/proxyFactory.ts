import type { Request, Response, NextFunction, RequestHandler } from 'express';
import { createProxyMiddleware, fixRequestBody, type Options } from 'http-proxy-middleware';
import type CircuitBreaker from 'opossum';
import { CORRELATION_HEADER } from '../middleware/correlationId.js';
import { createBreaker } from '../breakers/upstream.js';
import type { Metrics } from '../observability/metrics.js';
import { HttpProblem } from '../middleware/error.js';

export interface ProxyDeps {
  name: string;
  target: string;
  pathRewrite?: Record<string, string>;
  metrics?: Metrics;
}

type ProxyExecutor = (req: Request, res: Response) => Promise<void>;

function buildProxy(deps: ProxyDeps): RequestHandler {
  const options: Options = {
    target: deps.target,
    changeOrigin: true,
    xfwd: true,
    pathRewrite: deps.pathRewrite,
    proxyTimeout: 5_000,
    timeout: 5_000,
    on: {
      proxyReq: (proxyReq, req) => {
        const r = req as Request;
        // Express router.use() strips the matched prefix from req.url; restore the
        // original full path so upstreams see e.g. /api/v1/auth/register, not /register.
        proxyReq.path = r.originalUrl;
        // express.json() consumes the body stream before the proxy sees it;
        // re-serialize req.body so the upstream receives the full request body.
        fixRequestBody(proxyReq, r);
        const cid = r.correlationId;
        if (cid) proxyReq.setHeader(CORRELATION_HEADER, cid);
        if (r.user) {
          proxyReq.setHeader('x-user-id', r.user.sub);
          if (r.user.email) proxyReq.setHeader('x-user-email', r.user.email);
          if (r.user.roles.length > 0) proxyReq.setHeader('x-user-roles', r.user.roles.join(','));
        }
      },
      proxyRes: (proxyRes, req) => {
        const r = req as Request;
        const startedAt = (r as Request & { _proxyStartedAt?: number })._proxyStartedAt;
        if (deps.metrics && typeof startedAt === 'number') {
          const dur = (Date.now() - startedAt) / 1000;
          deps.metrics.upstreamLatency.observe(
            { upstream: deps.name, method: r.method, status: String(proxyRes.statusCode ?? 0) },
            dur,
          );
        }
      },
    },
  };
  return createProxyMiddleware(options);
}

export function makeProxyHandler(deps: ProxyDeps): {
  handler: RequestHandler;
  breaker: CircuitBreaker<[Request, Response], void>;
} {
  const proxy = buildProxy(deps);

  const executor: ProxyExecutor = (req, res) =>
    new Promise<void>((resolve, reject) => {
      (req as Request & { _proxyStartedAt?: number })._proxyStartedAt = Date.now();
      let settled = false;
      const done = (err?: unknown): void => {
        if (settled) return;
        settled = true;
        if (err) reject(err);
        else resolve();
      };

      res.once('close', () => done());
      res.once('finish', () => done());
      res.once('error', (err) => done(err));

      try {
        proxy(req, res, (err: unknown) => {
          if (err) done(err);
          else done();
        });
      } catch (err) {
        done(err);
      }
    });

  const breaker = createBreaker<[Request, Response], void>(executor, {
    name: deps.name,
    timeoutMs: 5_000,
    errorThresholdPercentage: 50,
    resetTimeoutMs: 10_000,
  }, deps.metrics);

  const handler: RequestHandler = (req: Request, res: Response, next: NextFunction) => {
    breaker
      .fire(req, res)
      .catch((err: unknown) => {
        if (res.headersSent) return;
        if (err instanceof HttpProblem) return next(err);
        const message = err instanceof Error ? err.message : 'Upstream error';
        next(new HttpProblem(502, 'Bad Gateway', message, 'about:blank', { upstream: deps.name }));
      });
  };

  return { handler, breaker };
}

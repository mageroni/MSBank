import type { Request, Response, NextFunction } from 'express';
import { trace } from '@opentelemetry/api';

export class HttpProblem extends Error {
  readonly status: number;
  readonly type: string;
  readonly title: string;
  readonly detail?: string;
  readonly extras?: Record<string, unknown>;

  constructor(
    status: number,
    title: string,
    detail?: string,
    type: string = 'about:blank',
    extras?: Record<string, unknown>,
  ) {
    super(detail ?? title);
    this.status = status;
    this.type = type;
    this.title = title;
    this.detail = detail;
    this.extras = extras;
  }
}

function isHttpProblem(e: unknown): e is HttpProblem {
  return e instanceof HttpProblem;
}

export function notFoundHandler() {
  return (req: Request, _res: Response, next: NextFunction): void => {
    next(new HttpProblem(404, 'Not Found', `No route matches ${req.method} ${req.originalUrl}`));
  };
}

export function errorHandler() {
  return (err: unknown, req: Request, res: Response, _next: NextFunction): void => {
    const traceId = trace.getActiveSpan()?.spanContext().traceId;
    const correlationId = req.correlationId;

    let status = 500;
    let title = 'Internal Server Error';
    let detail: string | undefined;
    let type = 'about:blank';
    let extras: Record<string, unknown> | undefined;

    if (isHttpProblem(err)) {
      status = err.status;
      title = err.title;
      detail = err.detail;
      type = err.type;
      extras = err.extras;
    } else if (err instanceof Error) {
      detail = err.message;
    }

    if (status >= 500) {
      req.log?.error({ err, correlationId, traceId }, 'request failed');
    } else {
      req.log?.warn({ err, correlationId, traceId, status }, 'request rejected');
    }

    res
      .status(status)
      .type('application/problem+json')
      .json({
        type,
        title,
        status,
        detail,
        instance: req.originalUrl,
        correlationId,
        traceId,
        ...(extras ?? {}),
      });
  };
}

import type { Request, Response, NextFunction } from 'express';
import { randomUUID } from 'node:crypto';

export const CORRELATION_HEADER = 'x-correlation-id';

declare module 'express-serve-static-core' {
  interface Request {
    correlationId?: string;
  }
}

export function correlationId() {
  return (req: Request, res: Response, next: NextFunction): void => {
    const incoming = req.header(CORRELATION_HEADER);
    const id = incoming && incoming.trim().length > 0 ? incoming.trim() : randomUUID();
    req.correlationId = id;
    res.setHeader(CORRELATION_HEADER, id);
    next();
  };
}

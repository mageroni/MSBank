import pino from 'pino';

export type Logger = pino.Logger;

let cachedLogger: Logger | undefined;

export function getLogger(level: string = process.env.LOG_LEVEL ?? 'info'): Logger {
  if (!cachedLogger) {
    cachedLogger = pino({
      level,
      base: { service: 'api-gateway' },
      timestamp: pino.stdTimeFunctions.isoTime,
      formatters: {
        level: (label) => ({ level: label }),
      },
      redact: {
        paths: ['req.headers.authorization', 'req.headers.cookie', '*.password', '*.token'],
        censor: '[REDACTED]',
      },
    });
  }
  return cachedLogger;
}

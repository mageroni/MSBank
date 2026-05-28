import { loadConfig } from './config.js';
import { getLogger } from './observability/logger.js';
import { startOtel } from './observability/otel.js';
import { createApp } from './app.js';
import Redis from 'ioredis';

async function main(): Promise<void> {
  const config = loadConfig();
  const log = getLogger(config.logLevel);

  const otel = startOtel(config.otel.serviceName, config.otel.endpoint);

  const redis = new Redis(config.redisUrl, {
    maxRetriesPerRequest: 3,
    lazyConnect: false,
    enableOfflineQueue: true,
  });
  redis.on('error', (err) => log.error({ err }, 'redis error'));

  const app = createApp({ config, redis });
  const server = app.listen(config.port, () => {
    log.info({ port: config.port }, 'api-gateway listening');
  });

  let shuttingDown = false;
  const shutdown = async (signal: NodeJS.Signals): Promise<void> => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info({ signal }, 'shutdown initiated');

    const forceTimer = setTimeout(() => {
      log.warn('forcing exit after 10s');
      process.exit(1);
    }, 10_000);
    forceTimer.unref();

    server.close((err) => {
      if (err) log.warn({ err }, 'http server close error');
    });

    try {
      await redis.quit();
    } catch (err) {
      log.warn({ err }, 'redis quit error');
    }
    await otel.shutdown();
    log.info('shutdown complete');
    process.exit(0);
  };

  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('unhandledRejection', (reason) => log.error({ reason }, 'unhandled rejection'));
  process.on('uncaughtException', (err) => log.fatal({ err }, 'uncaught exception'));
}

main().catch((err: unknown) => {
  // eslint-disable-next-line no-console
  console.error('Fatal startup error:', err);
  process.exit(1);
});

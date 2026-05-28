import { NodeSDK } from '@opentelemetry/sdk-node';
import { getNodeAutoInstrumentations } from '@opentelemetry/auto-instrumentations-node';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-grpc';
import { Resource } from '@opentelemetry/resources';
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions';
import { getLogger } from './logger.js';

export interface OtelHandle {
  shutdown(): Promise<void>;
}

export function startOtel(serviceName: string, endpoint?: string): OtelHandle {
  const log = getLogger();
  if (!endpoint) {
    log.info('OTel disabled (no OTEL_EXPORTER_OTLP_ENDPOINT)');
    return { shutdown: async () => {} };
  }

  const sdk = new NodeSDK({
    resource: new Resource({ [ATTR_SERVICE_NAME]: serviceName }),
    traceExporter: new OTLPTraceExporter({ url: endpoint }),
    instrumentations: [
      getNodeAutoInstrumentations({
        '@opentelemetry/instrumentation-fs': { enabled: false },
      }),
    ],
  });

  try {
    sdk.start();
    log.info({ endpoint }, 'OTel SDK started');
  } catch (err) {
    log.error({ err }, 'Failed to start OTel SDK');
  }

  return {
    shutdown: async () => {
      try {
        await sdk.shutdown();
      } catch (err) {
        log.warn({ err }, 'OTel shutdown error');
      }
    },
  };
}

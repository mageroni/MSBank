import client from 'prom-client';

export interface Metrics {
  registry: client.Registry;
  upstreamLatency: client.Histogram<'upstream' | 'method' | 'status'>;
  circuitBreakerState: client.Gauge<'upstream' | 'state'>;
}

export function createMetrics(): Metrics {
  const registry = new client.Registry();
  client.collectDefaultMetrics({ register: registry });

  const upstreamLatency = new client.Histogram({
    name: 'gateway_upstream_request_duration_seconds',
    help: 'Upstream request latency from the gateway, by service.',
    labelNames: ['upstream', 'method', 'status'] as const,
    buckets: [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10],
    registers: [registry],
  });

  const circuitBreakerState = new client.Gauge({
    name: 'gateway_circuit_breaker_state',
    help: 'Circuit breaker state per upstream (1 = active).',
    labelNames: ['upstream', 'state'] as const,
    registers: [registry],
  });

  return { registry, upstreamLatency, circuitBreakerState };
}

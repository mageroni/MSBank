import CircuitBreaker from 'opossum';
import type { Metrics } from '../observability/metrics.js';
import { HttpProblem } from '../middleware/error.js';

export interface BreakerOptions {
  name: string;
  timeoutMs?: number;
  errorThresholdPercentage?: number;
  resetTimeoutMs?: number;
}

export type AsyncFn<TArgs extends unknown[], TResult> = (...args: TArgs) => Promise<TResult>;

const DEFAULTS = {
  timeoutMs: 5_000,
  errorThresholdPercentage: 50,
  resetTimeoutMs: 10_000,
};

export function createBreaker<TArgs extends unknown[], TResult>(
  fn: AsyncFn<TArgs, TResult>,
  opts: BreakerOptions,
  metrics?: Metrics,
): CircuitBreaker<TArgs, TResult> {
  const breaker = new CircuitBreaker<TArgs, TResult>(fn, {
    name: opts.name,
    timeout: opts.timeoutMs ?? DEFAULTS.timeoutMs,
    errorThresholdPercentage: opts.errorThresholdPercentage ?? DEFAULTS.errorThresholdPercentage,
    resetTimeout: opts.resetTimeoutMs ?? DEFAULTS.resetTimeoutMs,
    rollingCountTimeout: 10_000,
    rollingCountBuckets: 10,
    volumeThreshold: 5,
  });

  breaker.fallback(() => {
    throw new HttpProblem(
      503,
      'Service Unavailable',
      `Upstream "${opts.name}" is temporarily unavailable`,
      'https://msbank.local/problems/upstream-unavailable',
      { upstream: opts.name },
    );
  });

  if (metrics) {
    const setState = (state: 'open' | 'halfOpen' | 'closed') => {
      metrics.circuitBreakerState.set({ upstream: opts.name, state: 'open' }, state === 'open' ? 1 : 0);
      metrics.circuitBreakerState.set({ upstream: opts.name, state: 'halfOpen' }, state === 'halfOpen' ? 1 : 0);
      metrics.circuitBreakerState.set({ upstream: opts.name, state: 'closed' }, state === 'closed' ? 1 : 0);
    };
    breaker.on('open', () => setState('open'));
    breaker.on('halfOpen', () => setState('halfOpen'));
    breaker.on('close', () => setState('closed'));
    setState('closed');
  }

  return breaker;
}

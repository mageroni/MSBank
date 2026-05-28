import { makeProxyHandler } from './proxyFactory.js';
import type { Metrics } from '../observability/metrics.js';

export function transactionsProxy(target: string, metrics?: Metrics) {
  return makeProxyHandler({ name: 'transactions', target, metrics });
}

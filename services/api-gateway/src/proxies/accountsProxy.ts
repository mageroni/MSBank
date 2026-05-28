import { makeProxyHandler } from './proxyFactory.js';
import type { Metrics } from '../observability/metrics.js';

export function accountsProxy(target: string, metrics?: Metrics) {
  return makeProxyHandler({ name: 'accounts', target, metrics });
}

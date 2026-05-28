import { makeProxyHandler } from './proxyFactory.js';
import type { Metrics } from '../observability/metrics.js';

export function authProxy(target: string, metrics?: Metrics) {
  return makeProxyHandler({ name: 'auth', target, metrics });
}

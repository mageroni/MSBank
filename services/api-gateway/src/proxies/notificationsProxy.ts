import { makeProxyHandler } from './proxyFactory.js';
import type { Metrics } from '../observability/metrics.js';

export function notificationsProxy(target: string, metrics?: Metrics) {
  return makeProxyHandler({ name: 'notifications', target, metrics });
}

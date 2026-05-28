import { MemoryStore } from 'express-rate-limit';

export function memoryRateLimitFactory() {
  return (_keyPrefix: string) => new MemoryStore();
}

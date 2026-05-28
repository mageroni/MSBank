import { describe, it, expect } from 'vitest';
import { formatMoney, majorToMinor } from '@/lib/format/money';

describe('formatMoney', () => {
  it('formats USD cents', () => {
    expect(formatMoney(12345, 'USD')).toBe('$123.45');
  });
  it('formats zero', () => {
    expect(formatMoney(0, 'USD')).toBe('$0.00');
  });
  it('handles JPY (zero-decimal)', () => {
    expect(formatMoney(1234, 'JPY')).toContain('1,234');
  });
  it('falls back on invalid currency code', () => {
    expect(formatMoney(100, 'ZZZ')).toMatch(/ZZZ/);
  });
});

describe('majorToMinor', () => {
  it('converts dollars to cents', () => {
    expect(majorToMinor('12.34', 'USD')).toBe(1234);
    expect(majorToMinor(12.34, 'USD')).toBe(1234);
  });
  it('rounds to nearest cent', () => {
    expect(majorToMinor('0.005', 'USD')).toBe(1);
  });
  it('JPY has no minor units', () => {
    expect(majorToMinor('1234', 'JPY')).toBe(1234);
  });
  it('throws on invalid', () => {
    expect(() => majorToMinor('abc', 'USD')).toThrow();
  });
});

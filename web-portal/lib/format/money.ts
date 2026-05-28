export function formatMoney(amountMinorUnits: number, currency: string, locale = 'en-US'): string {
  const fractionDigits = zeroDecimalCurrencies.has(currency.toUpperCase()) ? 0 : 2;
  const divisor = Math.pow(10, fractionDigits);
  const major = amountMinorUnits / divisor;
  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: currency.toUpperCase(),
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits
    }).format(major);
  } catch {
    return `${currency.toUpperCase()} ${major.toFixed(fractionDigits)}`;
  }
}

export function majorToMinor(amountMajor: string | number, currency: string): number {
  const fractionDigits = zeroDecimalCurrencies.has(currency.toUpperCase()) ? 0 : 2;
  const n = typeof amountMajor === 'string' ? Number(amountMajor) : amountMajor;
  if (!Number.isFinite(n)) throw new Error('Invalid amount');
  return Math.round(n * Math.pow(10, fractionDigits));
}

const zeroDecimalCurrencies = new Set([
  'JPY', 'KRW', 'VND', 'CLP', 'XAF', 'XOF', 'XPF', 'BIF', 'DJF', 'GNF', 'KMF', 'PYG', 'RWF', 'UGX', 'VUV'
]);

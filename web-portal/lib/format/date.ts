export function formatDate(iso: string, locale = 'en-US'): string {
  try {
    const d = new Date(iso);
    return new Intl.DateTimeFormat(locale, {
      year: 'numeric', month: 'short', day: '2-digit'
    }).format(d);
  } catch { return iso; }
}

export function formatDateTime(iso: string, locale = 'en-US'): string {
  try {
    const d = new Date(iso);
    return new Intl.DateTimeFormat(locale, {
      year: 'numeric', month: 'short', day: '2-digit',
      hour: '2-digit', minute: '2-digit'
    }).format(d);
  } catch { return iso; }
}

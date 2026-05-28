'use client';

import { useEffect, useState } from 'react';

const STORAGE_KEY = 'msbank-theme';

export function useTheme() {
  const [theme, setThemeState] = useState<'light' | 'dark'>('light');

  useEffect(() => {
    const stored = (typeof window !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null) as 'light' | 'dark' | null;
    const initial: 'light' | 'dark' =
      stored ?? (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    setThemeState(initial);
    document.documentElement.classList.toggle('dark', initial === 'dark');
  }, []);

  const setTheme = (t: 'light' | 'dark') => {
    setThemeState(t);
    document.documentElement.classList.toggle('dark', t === 'dark');
    localStorage.setItem(STORAGE_KEY, t);
  };

  return { theme, setTheme, toggle: () => setTheme(theme === 'dark' ? 'light' : 'dark') };
}

'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { configureClient } from '@/lib/api/client';
import { api } from '@/lib/api/endpoints';
import type { User } from '@/lib/api/types';

interface AuthState {
  user: User | null;
  isLoading: boolean;
  isAuthenticated: boolean;
  login: (email: string, password: string, totpCode?: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const accessTokenRef = useRef<string | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const setAccessToken = useCallback((token: string | null) => {
    accessTokenRef.current = token;
  }, []);

  const handleUnauthorized = useCallback(() => {
    accessTokenRef.current = null;
    setUser(null);
    router.replace('/login');
  }, [router]);

  useEffect(() => {
    configureClient({
      getAccessToken: () => accessTokenRef.current,
      setAccessToken,
      onUnauthorized: handleUnauthorized
    });
  }, [setAccessToken, handleUnauthorized]);

  const refreshUser = useCallback(async () => {
    try {
      const res = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' });
      if (!res.ok) {
        setUser(null);
        return;
      }
      const data = (await res.json()) as { accessToken: string };
      accessTokenRef.current = data.accessToken;
      const me = await api.auth.me();
      setUser(me);
    } catch {
      setUser(null);
    }
  }, []);

  useEffect(() => {
    void refreshUser().finally(() => setIsLoading(false));
  }, [refreshUser]);

  const login = useCallback(async (email: string, password: string, totpCode?: string) => {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ email, password, totpCode })
    });
    if (!res.ok) {
      const problem = await res.json().catch(() => ({ title: 'Login failed' }));
      throw new Error(problem.title ?? 'Login failed');
    }
    const data = (await res.json()) as { accessToken: string; user: User };
    accessTokenRef.current = data.accessToken;
    setUser(data.user);
  }, []);

  const logout = useCallback(async () => {
    try {
      await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
    } finally {
      accessTokenRef.current = null;
      setUser(null);
      router.replace('/login');
    }
  }, [router]);

  const value = useMemo<AuthState>(() => ({
    user, isLoading, isAuthenticated: !!user, login, logout, refreshUser
  }), [user, isLoading, login, logout, refreshUser]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

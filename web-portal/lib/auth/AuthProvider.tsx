'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { configureClient } from '@/lib/api/client';
import { api } from '@/lib/api/endpoints';
import { PUBLIC_API_BASE_URL, REFRESH_TOKEN_STORAGE_KEY } from '@/lib/config';
import type { TokenPair, User } from '@/lib/api/types';

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
  const navigate = useNavigate();
  const accessTokenRef = useRef<string | null>(null);
  const refreshTokenRef = useRef<string | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const setAccessToken = useCallback((token: string | null) => {
    accessTokenRef.current = token;
  }, []);

  const setRefreshToken = useCallback((token: string | null) => {
    refreshTokenRef.current = token;
    if (typeof window !== 'undefined') {
      if (token) {
        window.localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, token);
      } else {
        window.localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
      }
    }
  }, []);

  const handleUnauthorized = useCallback(() => {
    accessTokenRef.current = null;
    setRefreshToken(null);
    setUser(null);
    navigate('/login', { replace: true });
  }, [navigate, setRefreshToken]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    refreshTokenRef.current = window.localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY);
  }, []);

  useEffect(() => {
    configureClient({
      getAccessToken: () => accessTokenRef.current,
      setAccessToken,
      getRefreshToken: () => refreshTokenRef.current,
      setRefreshToken,
      onUnauthorized: handleUnauthorized
    });
  }, [setAccessToken, setRefreshToken, handleUnauthorized]);

  const refreshUser = useCallback(async () => {
    const refreshToken = refreshTokenRef.current;
    if (!refreshToken) {
      setUser(null);
      return;
    }

    try {
      const res = await fetch(`${PUBLIC_API_BASE_URL}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken })
      });
      if (!res.ok) {
        accessTokenRef.current = null;
        setRefreshToken(null);
        setUser(null);
        return;
      }

      const tokens = (await res.json()) as TokenPair;
      accessTokenRef.current = tokens.accessToken;
      setRefreshToken(tokens.refreshToken);
      const me = await api.auth.me();
      setUser(me);
    } catch {
      accessTokenRef.current = null;
      setRefreshToken(null);
      setUser(null);
    }
  }, [setRefreshToken]);

  useEffect(() => {
    void refreshUser().finally(() => setIsLoading(false));
  }, [refreshUser]);

  const login = useCallback(async (email: string, password: string, totpCode?: string) => {
    const res = await fetch(`${PUBLIC_API_BASE_URL}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, totpCode })
    });
    if (!res.ok) {
      const problem = await res.json().catch(() => ({ title: 'Login failed' }));
      throw new Error(problem.title ?? 'Login failed');
    }

    const tokens = (await res.json()) as TokenPair;
    accessTokenRef.current = tokens.accessToken;
    setRefreshToken(tokens.refreshToken);
    const me = await api.auth.me();
    setUser(me);
  }, [setRefreshToken]);

  const logout = useCallback(async () => {
    accessTokenRef.current = null;
    setRefreshToken(null);
    setUser(null);
    navigate('/login', { replace: true });
  }, [navigate, setRefreshToken]);

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

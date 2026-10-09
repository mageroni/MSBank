import { StrictMode } from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '@/lib/auth/AuthProvider';
import { ProtectedRoute } from '@/src/components/ProtectedRoute';
import { REFRESH_TOKEN_STORAGE_KEY } from '@/lib/config';
import type { User } from '@/lib/api/types';

const user: User = {
  id: 'user-1',
  email: 'customer@example.com',
  firstName: 'Customer',
  lastName: 'User',
  roles: ['CUSTOMER'],
  createdAt: '2026-01-01T00:00:00Z'
};

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Unauthorized',
    json: async () => body,
    text: async () => JSON.stringify(body)
  } as Response;
}

function App() {
  return (
    <MemoryRouter initialEntries={['/dashboard']}>
      <AuthProvider>
        <Routes>
          <Route
            path="/dashboard"
            element={(
              <ProtectedRoute>
                <div>Dashboard</div>
              </ProtectedRoute>
            )}
          />
          <Route path="/login" element={<div>Login</div>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('session persistence', () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, 'stored-refresh-token');
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('restores the session once when startup effects run twice', async () => {
    let refreshRequests = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('/api/v1/auth/refresh')) {
        refreshRequests += 1;
        return refreshRequests === 1
          ? jsonResponse({
            accessToken: 'access-token',
            refreshToken: 'rotated-refresh-token',
            tokenType: 'Bearer',
            expiresIn: 900
          })
          : jsonResponse({}, 401);
      }

      return jsonResponse(user);
    });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <StrictMode>
        <App />
      </StrictMode>
    );

    expect(await screen.findByText('Dashboard')).toBeTruthy();
    expect(refreshRequests).toBe(1);
    expect(localStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBe('rotated-refresh-token');
  });
});

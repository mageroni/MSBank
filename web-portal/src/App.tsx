import { Navigate, Route, Routes } from 'react-router-dom';
import { Providers } from './Providers';
import { ProtectedRoute } from './components/ProtectedRoute';
import { AppShell } from './layouts/AppShell';
import { AuthShell } from './layouts/AuthShell';
import { RootPage } from './pages/RootPage';
import { DashboardPage } from './pages/DashboardPage';
import { AccountDetailPage } from './pages/AccountDetailPage';
import { TransferPage } from './pages/TransferPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { SecurityPage } from './pages/SecurityPage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { NotFoundPage } from './pages/NotFoundPage';

export function App() {
  return (
    <Providers>
      <Routes>
        <Route path="/" element={<RootPage />} />
        <Route element={<AuthShell />}>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
        </Route>

        <Route
          element={(
            <ProtectedRoute>
              <AppShell />
            </ProtectedRoute>
          )}
        >
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/accounts/:id" element={<AccountDetailPage />} />
          <Route path="/transfer" element={<TransferPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/settings/security" element={<SecurityPage />} />
        </Route>

        <Route path="/404" element={<NotFoundPage />} />
        <Route path="*" element={<Navigate to="/404" replace />} />
      </Routes>
    </Providers>
  );
}

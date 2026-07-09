import { Navigate } from 'react-router-dom';
import { useAuth } from '@/lib/auth/AuthProvider';
import { Spinner } from '@/components/ui/Spinner';

export function RootPage() {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner className="h-10 w-10" />
      </div>
    );
  }

  return <Navigate to={isAuthenticated ? '/dashboard' : '/login'} replace />;
}

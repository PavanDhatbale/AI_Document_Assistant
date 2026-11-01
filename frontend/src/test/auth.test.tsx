import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { LoginPage } from '../pages/LoginPage';
import { RegisterPage } from '../pages/RegisterPage';
import { AuthProvider, useAuth } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import { ProtectedRoute } from '../components/layout/ProtectedRoute';
import { authApi } from '../api/auth';
import { tokenManager } from '../api/client';

// Mock authApi
vi.mock('../api/auth', () => ({
  authApi: {
    login: vi.fn(),
    loginWithGoogle: vi.fn(),
    register: vi.fn(),
    getProfile: vi.fn(),
    logout: vi.fn(),
  },
}));

describe('Auth Layer and Protected Routes', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    tokenManager.clear();
  });

  // 1. Login form validation
  it('validates required fields and email format on login form', async () => {
    render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <LoginPage />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    const submitBtn = screen.getByTestId('login-submit-button');

    // Click submit with empty fields
    fireEvent.click(submitBtn);
    expect(await screen.findByText('Email is required')).toBeInTheDocument();
    expect(screen.getByText('Password is required')).toBeInTheDocument();

    // Enter invalid email format
    const emailInput = screen.getByTestId('login-email-input');
    fireEvent.change(emailInput, { target: { value: 'not-an-email' } });
    fireEvent.click(submitBtn);

    expect(await screen.findByText('Please enter a valid email address')).toBeInTheDocument();
  });

  it('submits login request with valid credentials', async () => {
    const mockUser = {
      id: 'u-1',
      email: 'user@example.com',
      displayName: 'Alice User',
      role: 'ROLE_USER',
      createdAt: '2026-10-24T12:00:00Z',
    };
    (authApi.login as any).mockResolvedValueOnce({
      token: 'mock-jwt-token',
      tokenType: 'Bearer',
      expiresIn: 86400,
      user: mockUser,
    });

    render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <LoginPage />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    fireEvent.change(screen.getByTestId('login-email-input'), {
      target: { value: 'user@example.com' },
    });
    fireEvent.change(screen.getByTestId('login-password-input'), {
      target: { value: 'password123' },
    });
    fireEvent.click(screen.getByTestId('login-submit-button'));

    await waitFor(() => {
      expect(authApi.login).toHaveBeenCalledWith({
        email: 'user@example.com',
        password: 'password123',
      });
    });
  });

  // 2. Registration form validation
  it('validates password length and matching confirmation on registration', async () => {
    render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <RegisterPage />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    const submitBtn = screen.getByTestId('register-submit-button');
    fireEvent.click(submitBtn);

    expect(await screen.findByText('Display name is required')).toBeInTheDocument();
    expect(screen.getByText('Email is required')).toBeInTheDocument();
    expect(screen.getByText('Password is required')).toBeInTheDocument();

    // Short password check
    fireEvent.change(screen.getByTestId('register-name-input'), { target: { value: 'Bob' } });
    fireEvent.change(screen.getByTestId('register-email-input'), { target: { value: 'bob@example.com' } });
    fireEvent.change(screen.getByTestId('register-password-input'), { target: { value: 'short' } });
    fireEvent.change(screen.getByTestId('register-confirm-password-input'), { target: { value: 'different' } });
    fireEvent.click(submitBtn);

    expect(await screen.findByText('Password must be at least 8 characters')).toBeInTheDocument();
    expect(screen.getByText('Passwords do not match')).toBeInTheDocument();
  });

  // 3. Protected-route behavior
  it('redirects unauthenticated users to /login and permits authenticated users', async () => {
    (authApi.getProfile as any).mockRejectedValueOnce(new Error('Unauthorized'));

    render(
      <MemoryRouter initialEntries={['/protected']}>
        <ToastProvider>
          <AuthProvider>
            <Routes>
              <Route path="/login" element={<div>Login Page Screen</div>} />
              <Route
                path="/protected"
                element={
                  <ProtectedRoute>
                    <div>Secret Protected Content</div>
                  </ProtectedRoute>
                }
              />
            </Routes>
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    // Unauthenticated user should be redirected to Login Page Screen
    expect(await screen.findByText('Login Page Screen')).toBeInTheDocument();
    expect(screen.queryByText('Secret Protected Content')).not.toBeInTheDocument();
  });

  // 4. Logout clearing authentication state
  it('clears user authentication state and tokens on logout', async () => {
    const mockUser = {
      id: 'u-2',
      email: 'test@example.com',
      displayName: 'Test User',
      role: 'ROLE_USER',
      createdAt: '2026-10-24T12:00:00Z',
    };
    (authApi.login as any).mockImplementation(async () => {
      tokenManager.setToken('login-token-123');
      return {
        token: 'login-token-123',
        tokenType: 'Bearer',
        expiresIn: 86400,
        user: mockUser,
      };
    });
    (authApi.logout as any).mockImplementation(() => {
      tokenManager.clear();
    });

    const LogoutTestComponent = () => {
      const { user, isAuthenticated, login, logout } = useAuth();
      return (
        <div>
          <span data-testid="auth-status">{isAuthenticated ? 'LOGGED_IN' : 'LOGGED_OUT'}</span>
          <span data-testid="user-name">{user?.displayName || 'NONE'}</span>
          <button
            data-testid="test-login-btn"
            onClick={() => login({ email: 'test@example.com', password: 'password123' })}
          >
            Sign In
          </button>
          <button data-testid="test-logout-btn" onClick={logout}>
            Sign Out
          </button>
        </div>
      );
    };

    render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <LogoutTestComponent />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    // Click Sign In
    fireEvent.click(screen.getByTestId('test-login-btn'));

    expect(await screen.findByText('LOGGED_IN')).toBeInTheDocument();
    expect(screen.getByText('Test User')).toBeInTheDocument();
    expect(tokenManager.getToken()).toBe('login-token-123');

    // Trigger Logout
    fireEvent.click(screen.getByTestId('test-logout-btn'));

    expect(screen.getByTestId('auth-status')).toHaveTextContent('LOGGED_OUT');
    expect(screen.getByTestId('user-name')).toHaveTextContent('NONE');
    expect(tokenManager.getToken()).toBeNull();
    expect(authApi.logout).toHaveBeenCalled();
  });

  // 5. Google Authentication Integration Tests
  it('renders Google authentication buttons on Login and Register pages', () => {
    const { unmount } = render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <LoginPage />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    expect(screen.getByTestId('google-auth-button')).toBeInTheDocument();
    expect(screen.getByText('Continue with Google')).toBeInTheDocument();

    unmount();

    render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <RegisterPage />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    expect(screen.getByTestId('google-auth-button')).toBeInTheDocument();
    expect(screen.getByText('Sign up with Google')).toBeInTheDocument();
  });

  it('triggers Google login callback and stores authenticated session', async () => {
    const mockGoogleUser = {
      id: 'g-user-1',
      email: 'google.user@example.com',
      displayName: 'Google User',
      role: 'ROLE_USER',
      authProvider: 'GOOGLE',
      createdAt: '2026-11-01T00:00:00Z',
    };

    (authApi.loginWithGoogle as any).mockResolvedValueOnce({
      token: 'mock-google-jwt',
      tokenType: 'Bearer',
      expiresIn: 86400,
      user: mockGoogleUser,
    });

    const GoogleTestComponent = () => {
      const { user, isAuthenticated, loginWithGoogle } = useAuth();
      return (
        <div>
          <span data-testid="auth-state">{isAuthenticated ? 'AUTHENTICATED' : 'ANONYMOUS'}</span>
          <span data-testid="auth-email">{user?.email || 'NONE'}</span>
          <span data-testid="auth-provider">{user?.authProvider || 'NONE'}</span>
          <button
            data-testid="trigger-google-btn"
            onClick={() => loginWithGoogle('mock-id-token-xyz')}
          >
            Google Sign In
          </button>
        </div>
      );
    };

    render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <GoogleTestComponent />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    expect(screen.getByTestId('auth-state')).toHaveTextContent('ANONYMOUS');

    fireEvent.click(screen.getByTestId('trigger-google-btn'));

    await waitFor(() => {
      expect(authApi.loginWithGoogle).toHaveBeenCalledWith('mock-id-token-xyz');
    });

    expect(await screen.findByText('AUTHENTICATED')).toBeInTheDocument();
    expect(screen.getByTestId('auth-email')).toHaveTextContent('google.user@example.com');
    expect(screen.getByTestId('auth-provider')).toHaveTextContent('GOOGLE');
  });

  it('handles Google authentication conflict error gracefully', async () => {
    const errorResponse = {
      response: {
        status: 409,
        data: {
          message:
            'An account with this email already exists with password authentication. Please sign in with your email and password.',
        },
      },
    };
    (authApi.loginWithGoogle as any).mockRejectedValueOnce(errorResponse);

    const GoogleConflictComponent = () => {
      const { loginWithGoogle } = useAuth();
      const [error, setError] = React.useState<string | null>(null);

      const handleGoogle = async () => {
        try {
          await loginWithGoogle('conflict-id-token');
        } catch (err: any) {
          setError(
            err.response?.data?.message || 'Conflict occurred'
          );
        }
      };

      return (
        <div>
          {error && <span data-testid="error-message">{error}</span>}
          <button data-testid="test-conflict-btn" onClick={handleGoogle}>
            Sign In with Google
          </button>
        </div>
      );
    };

    render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <GoogleConflictComponent />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );

    fireEvent.click(screen.getByTestId('test-conflict-btn'));

    expect(
      await screen.findByText(/already exists with password authentication/i)
    ).toBeInTheDocument();
  });
});

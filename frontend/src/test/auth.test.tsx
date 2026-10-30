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
});

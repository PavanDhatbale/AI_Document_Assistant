import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { ApiErrorResponse } from '../types/api';

// Configurable Storage Strategy
// 'session' = tab-scoped sessionStorage (survives page refresh within the same browser tab)
// 'memory' = purely in-memory (safest against XSS, but reset on reload)
const STORAGE_MODE = import.meta.env.VITE_AUTH_STORAGE_MODE || 'session';

class TokenStorage {
  private inMemoryToken: string | null = null;
  private readonly storageKey = 'ai_doc_jwt_token';

  getToken(): string | null {
    if (STORAGE_MODE === 'memory') {
      return this.inMemoryToken;
    }
    try {
      return sessionStorage.getItem(this.storageKey);
    } catch {
      return this.inMemoryToken;
    }
  }

  setToken(token: string | null): void {
    this.inMemoryToken = token;
    if (STORAGE_MODE !== 'memory') {
      try {
        if (token) {
          sessionStorage.setItem(this.storageKey, token);
        } else {
          sessionStorage.removeItem(this.storageKey);
        }
      } catch {
        // Fallback silently if sessionStorage is unavailable/blocked
      }
    }
  }

  clear(): void {
    this.inMemoryToken = null;
    try {
      sessionStorage.removeItem(this.storageKey);
    } catch {
      // Ignore
    }
  }
}

export const tokenManager = new TokenStorage();

// Centralized Axios Instance
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 30000,
});

// Unauthorized listener hook for React context
type UnauthorizedHandler = () => void;
let unauthorizedHandler: UnauthorizedHandler | null = null;

export const setUnauthorizedHandler = (handler: UnauthorizedHandler | null) => {
  unauthorizedHandler = handler;
};

// Request Interceptor: Attach JWT Bearer Token
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = tokenManager.getToken();
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Response Interceptor: Uniform error handling & 401 handling
apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiErrorResponse>) => {
    if (error.response) {
      const status = error.response.status;

      // Handle 401 Unauthorized (session expired or invalid token)
      // Ignore 401 on login and register endpoints to prevent clearing while entering bad credentials
      const requestUrl = error.config?.url || '';
      const isAuthEndpoint = requestUrl.includes('/auth/login') || requestUrl.includes('/auth/register');

      if (status === 401 && !isAuthEndpoint) {
        tokenManager.clear();
        if (unauthorizedHandler) {
          unauthorizedHandler();
        }
      }

      const errorData: ApiErrorResponse = error.response.data || {
        status,
        error: error.response.statusText || 'Error',
        message: 'An error occurred while processing the request',
      };

      return Promise.reject(errorData);
    } else if (error.request) {
      // Network error or backend unreachable
      const networkError: ApiErrorResponse = {
        status: 0,
        error: 'Network Error',
        message: 'Unable to reach backend server. Please verify the service is running.',
      };
      return Promise.reject(networkError);
    }

    const unexpectedError: ApiErrorResponse = {
      status: 500,
      error: 'Client Error',
      message: error.message || 'An unexpected client error occurred',
    };
    return Promise.reject(unexpectedError);
  }
);

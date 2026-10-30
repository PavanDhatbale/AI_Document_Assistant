import { describe, it, expect, vi, beforeEach } from 'vitest';
import { apiClient, tokenManager, setUnauthorizedHandler } from '../api/client';

describe('API Client Layer & Interceptors', () => {
  beforeEach(() => {
    tokenManager.clear();
    setUnauthorizedHandler(null);
  });

  // 5. API client attaching a token correctly
  it('attaches Authorization Bearer header when token is present', async () => {
    tokenManager.setToken('test-bearer-token-12345');

    // Spy on the request interceptor or verify through request config
    const requestConfig = {
      headers: {} as Record<string, string>,
    };

    // Grab request interceptors handlers
    const interceptor = (apiClient.interceptors.request as any).handlers[0];
    const updatedConfig = await interceptor.fulfilled(requestConfig);

    expect(updatedConfig.headers.Authorization).toBe('Bearer test-bearer-token-12345');
  });

  it('omits Authorization header when no token is stored', async () => {
    tokenManager.clear();

    const requestConfig = {
      headers: {} as Record<string, string>,
    };

    const interceptor = (apiClient.interceptors.request as any).handlers[0];
    const updatedConfig = await interceptor.fulfilled(requestConfig);

    expect(updatedConfig.headers.Authorization).toBeUndefined();
  });

  // 6. API error handling for 401 responses
  it('clears token and triggers unauthorized handler on 401 responses', async () => {
    tokenManager.setToken('expired-token');
    const unauthorizedMock = vi.fn();
    setUnauthorizedHandler(unauthorizedMock);

    const mock401Error = {
      config: { url: '/documents' },
      response: {
        status: 401,
        statusText: 'Unauthorized',
        data: {
          status: 401,
          error: 'Unauthorized',
          message: 'Full authentication is required to access this resource',
        },
      },
    };

    const responseInterceptor = (apiClient.interceptors.response as any).handlers[0];

    await expect(responseInterceptor.rejected(mock401Error)).rejects.toEqual({
      status: 401,
      error: 'Unauthorized',
      message: 'Full authentication is required to access this resource',
    });

    expect(tokenManager.getToken()).toBeNull();
    expect(unauthorizedMock).toHaveBeenCalled();
  });

  it('does not trigger unauthorized handler on 401 from login endpoint', async () => {
    const unauthorizedMock = vi.fn();
    setUnauthorizedHandler(unauthorizedMock);

    const mockAuth401 = {
      config: { url: '/auth/login' },
      response: {
        status: 401,
        statusText: 'Unauthorized',
        data: {
          status: 401,
          error: 'Unauthorized',
          message: 'Invalid email or password',
        },
      },
    };

    const responseInterceptor = (apiClient.interceptors.response as any).handlers[0];

    await expect(responseInterceptor.rejected(mockAuth401)).rejects.toEqual({
      status: 401,
      error: 'Unauthorized',
      message: 'Invalid email or password',
    });

    expect(unauthorizedMock).not.toHaveBeenCalled();
  });
});

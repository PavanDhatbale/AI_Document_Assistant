import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { authApi } from '../api/auth';
import { tokenManager, setUnauthorizedHandler } from '../api/client';
import { LoginRequest, RegisterRequest, UserProfileResponse, UpdateProfileRequest } from '../types/auth';

interface AuthContextType {
  user: UserProfileResponse | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (credentials: LoginRequest) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => void;
  refreshProfile: () => Promise<void>;
  updateProfile: (data: UpdateProfileRequest) => Promise<UserProfileResponse>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<UserProfileResponse | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  const logout = useCallback(() => {
    authApi.logout();
    setUser(null);
  }, []);

  const refreshProfile = useCallback(async () => {
    try {
      const profile = await authApi.getProfile();
      setUser(profile);
    } catch {
      logout();
    }
  }, [logout]);

  const updateProfile = async (data: UpdateProfileRequest): Promise<UserProfileResponse> => {
    const updated = await authApi.updateProfile(data);
    setUser(updated);
    return updated;
  };

  useEffect(() => {
    // Register 401 callback for automatic state cleanup
    setUnauthorizedHandler(() => {
      setUser(null);
    });

    const initAuth = async () => {
      const token = tokenManager.getToken();
      if (token) {
        try {
          const profile = await authApi.getProfile();
          setUser(profile);
        } catch {
          tokenManager.clear();
          setUser(null);
        }
      }
      setIsLoading(false);
    };

    initAuth();
  }, []);

  const login = async (credentials: LoginRequest): Promise<void> => {
    const response = await authApi.login(credentials);
    setUser(response.user);
  };

  const register = async (data: RegisterRequest): Promise<void> => {
    const response = await authApi.register(data);
    setUser(response.user);
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: !!user,
        isLoading,
        login,
        register,
        logout,
        refreshProfile,
        updateProfile,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};

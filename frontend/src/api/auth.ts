import { apiClient, tokenManager } from './client';
import {
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  UserProfileResponse,
  UpdateProfileRequest,
  ChangePasswordRequest,
} from '../types/auth';

export const authApi = {
  async login(credentials: LoginRequest): Promise<AuthResponse> {
    const response = await apiClient.post<AuthResponse>('/auth/login', credentials);
    if (response.data.token) {
      tokenManager.setToken(response.data.token);
    }
    return response.data;
  },

  async register(data: RegisterRequest): Promise<AuthResponse> {
    const response = await apiClient.post<AuthResponse>('/auth/register', data);
    if (response.data.token) {
      tokenManager.setToken(response.data.token);
    }
    return response.data;
  },

  async getProfile(): Promise<UserProfileResponse> {
    const response = await apiClient.get<UserProfileResponse>('/auth/me');
    return response.data;
  },

  async updateProfile(data: UpdateProfileRequest): Promise<UserProfileResponse> {
    const response = await apiClient.put<UserProfileResponse>('/users/profile', data);
    return response.data;
  },

  async changePassword(data: ChangePasswordRequest): Promise<{ message: string }> {
    const response = await apiClient.put<{ message: string }>('/users/password', data);
    return response.data;
  },

  logout(): void {
    tokenManager.clear();
  },
};

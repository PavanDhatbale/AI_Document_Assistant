import { apiClient } from './client';
import {
  UserProfileResponse,
  UpdateProfileRequest,
  ChangePasswordRequest,
} from '../types/auth';

export const userApi = {
  async getProfile(): Promise<UserProfileResponse> {
    const response = await apiClient.get<UserProfileResponse>('/users/profile');
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
};

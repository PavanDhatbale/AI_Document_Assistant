export interface User {
  id: string;
  email: string;
  displayName: string;
  phoneNumber?: string | null;
  role: string;
  createdAt: string;
  authProvider?: string;
}

export interface UserProfileResponse {
  id: string;
  email: string;
  displayName: string;
  phoneNumber?: string | null;
  role: string;
  createdAt: string;
  authProvider?: string;
}

export interface AuthResponse {
  token: string;
  tokenType: string;
  expiresIn: number;
  user: UserProfileResponse;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
  phoneNumber?: string;
}

export interface UpdateProfileRequest {
  displayName: string;
  phoneNumber?: string | null;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export interface GoogleAuthRequest {
  idToken: string;
}

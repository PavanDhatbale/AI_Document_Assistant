import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ProfilePage } from '../pages/ProfilePage';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import { authApi } from '../api/auth';
import { userApi } from '../api/user';
import { tokenManager } from '../api/client';

// Mock authApi and userApi
vi.mock('../api/auth', () => ({
  authApi: {
    getProfile: vi.fn(),
    updateProfile: vi.fn(),
    changePassword: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('../api/user', () => ({
  userApi: {
    getProfile: vi.fn(),
    updateProfile: vi.fn(),
    changePassword: vi.fn(),
  },
}));

describe('User Profile and Password Change Tests', () => {
  const mockUser = {
    id: 'user-profile-1',
    email: 'test.user@example.com',
    displayName: 'Test User',
    phoneNumber: '+1-555-0199',
    role: 'ROLE_USER',
    createdAt: '2026-10-24T10:00:00Z',
  };

  beforeEach(() => {
    vi.clearAllMocks();
    tokenManager.clear();
    // Simulate authenticated session by default
    tokenManager.setToken('mock-jwt-token');
    (authApi.getProfile as any).mockResolvedValue(mockUser);
    (userApi.getProfile as any).mockResolvedValue(mockUser);
  });

  const renderProfilePage = async () => {
    const utils = render(
      <MemoryRouter>
        <ToastProvider>
          <AuthProvider>
            <ProfilePage />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    );
    // Wait for initial profile load
    await waitFor(() => {
      expect(screen.getByTestId('profile-card')).toBeInTheDocument();
    });
    return utils;
  };

  it('1. Prepopulates and displays existing user profile information', async () => {
    await renderProfilePage();

    expect(screen.getByTestId('profile-display-name')).toHaveTextContent('Test User');
    expect(screen.getByTestId('profile-email')).toHaveTextContent('test.user@example.com');
    expect(screen.getByTestId('profile-role-badge')).toHaveTextContent('ROLE_USER');
    expect(screen.getByTestId('profile-phone-display')).toHaveTextContent('+1-555-0199');
    expect(screen.getByTestId('edit-profile-button')).toBeInTheDocument();
  });

  it('2. Enters edit mode, validates full name min length, and rejects empty submission', async () => {
    await renderProfilePage();

    // Click edit profile
    fireEvent.click(screen.getByTestId('edit-profile-button'));
    expect(screen.getByTestId('profile-edit-form')).toBeInTheDocument();

    const nameInput = screen.getByTestId('profile-name-input');
    const saveBtn = screen.getByTestId('save-profile-button');

    // Clear name and save
    fireEvent.change(nameInput, { target: { value: '' } });
    fireEvent.click(saveBtn);
    expect(await screen.findByText('Full name is required')).toBeInTheDocument();

    // Single character name
    fireEvent.change(nameInput, { target: { value: 'A' } });
    fireEvent.click(saveBtn);
    expect(await screen.findByText('Full name must be at least 2 characters')).toBeInTheDocument();

    // Email input must be disabled (read-only)
    const emailInput = screen.getByTestId('profile-email-readonly');
    expect(emailInput).toBeDisabled();
  });

  it('3. Successfully saves profile edits and updates displayed state', async () => {
    const updatedUser = {
      ...mockUser,
      displayName: 'Alice Engineer',
      phoneNumber: '+1-555-9999',
    };
    (authApi.updateProfile as any).mockResolvedValueOnce(updatedUser);
    (authApi.getProfile as any).mockResolvedValue(updatedUser);

    await renderProfilePage();

    fireEvent.click(screen.getByTestId('edit-profile-button'));

    fireEvent.change(screen.getByTestId('profile-name-input'), {
      target: { value: 'Alice Engineer' },
    });
    fireEvent.change(screen.getByTestId('profile-phone-input'), {
      target: { value: '+1-555-9999' },
    });

    fireEvent.click(screen.getByTestId('save-profile-button'));

    await waitFor(() => {
      expect(authApi.updateProfile).toHaveBeenCalledWith({
        displayName: 'Alice Engineer',
        phoneNumber: '+1-555-9999',
      });
    });

    // Verify form exits edit mode and reflects updated display name
    await waitFor(() => {
      expect(screen.getByTestId('profile-display-name')).toHaveTextContent('Alice Engineer');
    });
  });

  it('4. Cancels profile edit mode and restores original values', async () => {
    await renderProfilePage();

    fireEvent.click(screen.getByTestId('edit-profile-button'));

    const nameInput = screen.getByTestId('profile-name-input');
    fireEvent.change(nameInput, { target: { value: 'Discarded Changes' } });

    // Click Cancel
    fireEvent.click(screen.getByTestId('cancel-profile-button'));

    // Should return to read-only view and keep original name
    expect(screen.queryByTestId('profile-edit-form')).not.toBeInTheDocument();
    expect(screen.getByTestId('profile-display-name')).toHaveTextContent('Test User');
  });

  it('5. Handles API error when saving profile updates', async () => {
    (authApi.updateProfile as any).mockRejectedValueOnce({
      message: 'Failed to update user profile due to database timeout.',
    });

    await renderProfilePage();

    fireEvent.click(screen.getByTestId('edit-profile-button'));
    fireEvent.change(screen.getByTestId('profile-name-input'), {
      target: { value: 'New Name' },
    });
    fireEvent.click(screen.getByTestId('save-profile-button'));

    await waitFor(() => {
      const errorMatches = screen.getAllByText('Failed to update user profile due to database timeout.');
      expect(errorMatches.length).toBeGreaterThan(0);
    });
    // Form remains open so user does not lose input
    expect(screen.getByTestId('profile-edit-form')).toBeInTheDocument();
  });

  it('6. Validates password change form requirements (required fields, min length, matching confirmation)', async () => {
    await renderProfilePage();

    const submitBtn = screen.getByTestId('submit-password-button');

    // Click submit with empty password fields
    fireEvent.click(submitBtn);
    expect(await screen.findByText('Current password is required')).toBeInTheDocument();
    expect(screen.getByText('New password is required')).toBeInTheDocument();
    expect(screen.getByText('Password confirmation is required')).toBeInTheDocument();

    // New password too short (< 8 chars)
    fireEvent.change(screen.getByTestId('current-password-input'), {
      target: { value: 'ExistingPass123!' },
    });
    fireEvent.change(screen.getByTestId('new-password-input'), {
      target: { value: 'short' },
    });
    fireEvent.change(screen.getByTestId('confirm-password-input'), {
      target: { value: 'short' },
    });
    fireEvent.click(submitBtn);
    expect(await screen.findByText('New password must be at least 8 characters')).toBeInTheDocument();

    // Mismatched confirmation
    fireEvent.change(screen.getByTestId('new-password-input'), {
      target: { value: 'LongEnoughPass123!' },
    });
    fireEvent.change(screen.getByTestId('confirm-password-input'), {
      target: { value: 'DifferentPass456!' },
    });
    fireEvent.click(submitBtn);
    expect(await screen.findByText('New passwords do not match')).toBeInTheDocument();

    // New password same as current
    fireEvent.change(screen.getByTestId('new-password-input'), {
      target: { value: 'ExistingPass123!' },
    });
    fireEvent.change(screen.getByTestId('confirm-password-input'), {
      target: { value: 'ExistingPass123!' },
    });
    fireEvent.click(submitBtn);
    expect(await screen.findByText('New password must be different from current password')).toBeInTheDocument();
  });

  it('7. Submits valid password change and clears password inputs on success', async () => {
    (userApi.changePassword as any).mockResolvedValueOnce({
      message: 'Password changed successfully',
    });

    await renderProfilePage();

    fireEvent.change(screen.getByTestId('current-password-input'), {
      target: { value: 'OldPassword123!' },
    });
    fireEvent.change(screen.getByTestId('new-password-input'), {
      target: { value: 'NewSuperPassword456!' },
    });
    fireEvent.change(screen.getByTestId('confirm-password-input'), {
      target: { value: 'NewSuperPassword456!' },
    });

    fireEvent.click(screen.getByTestId('submit-password-button'));

    await waitFor(() => {
      expect(userApi.changePassword).toHaveBeenCalledWith({
        currentPassword: 'OldPassword123!',
        newPassword: 'NewSuperPassword456!',
        confirmPassword: 'NewSuperPassword456!',
      });
    });

    // Verify success banner is rendered
    expect(await screen.findByTestId('password-success-banner')).toHaveTextContent('Password changed successfully');

    // Password inputs should be cleared
    expect(screen.getByTestId('current-password-input')).toHaveValue('');
    expect(screen.getByTestId('new-password-input')).toHaveValue('');
    expect(screen.getByTestId('confirm-password-input')).toHaveValue('');
  });

  it('8. Handles password change API error (e.g. incorrect current password)', async () => {
    (userApi.changePassword as any).mockRejectedValueOnce({
      message: 'Current password is incorrect',
    });

    await renderProfilePage();

    fireEvent.change(screen.getByTestId('current-password-input'), {
      target: { value: 'WrongPasswordGuess!' },
    });
    fireEvent.change(screen.getByTestId('new-password-input'), {
      target: { value: 'NewSuperPassword456!' },
    });
    fireEvent.change(screen.getByTestId('confirm-password-input'), {
      target: { value: 'NewSuperPassword456!' },
    });

    fireEvent.click(screen.getByTestId('submit-password-button'));

    expect(await screen.findByTestId('password-error-banner')).toHaveTextContent('Current password is incorrect');
  });

  it('9. Resets password form fields and clears errors upon clicking Reset', async () => {
    await renderProfilePage();

    fireEvent.change(screen.getByTestId('current-password-input'), {
      target: { value: 'TypoInPassword' },
    });
    fireEvent.change(screen.getByTestId('new-password-input'), {
      target: { value: 'TypoNew' },
    });

    const resetBtn = screen.getByTestId('reset-password-button');
    fireEvent.click(resetBtn);

    expect(screen.getByTestId('current-password-input')).toHaveValue('');
    expect(screen.getByTestId('new-password-input')).toHaveValue('');
    expect(screen.getByTestId('confirm-password-input')).toHaveValue('');
  });
});

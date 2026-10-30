import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { userApi } from '../api/user';
import { Input } from '../components/common/Input';
import { Button } from '../components/common/Button';
import { Badge } from '../components/common/Badge';
import {
  User,
  Mail,
  Phone,
  Shield,
  Calendar,
  Lock,
  LogOut,
  Save,
  RotateCcw,
  CheckCircle,
  AlertCircle,
  KeyRound,
  Edit2,
  X,
} from 'lucide-react';

export const ProfilePage: React.FC = () => {
  const { user, logout, updateProfile, refreshProfile } = useAuth();
  const { addToast } = useToast();

  // Profile Edit State
  const [isEditingProfile, setIsEditingProfile] = useState(false);
  const [displayName, setDisplayName] = useState(user?.displayName || '');
  const [phoneNumber, setPhoneNumber] = useState(user?.phoneNumber || '');
  const [profileErrors, setProfileErrors] = useState<Record<string, string>>({});
  const [isSavingProfile, setIsSavingProfile] = useState(false);

  // Password Change State
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [passwordErrors, setPasswordErrors] = useState<Record<string, string>>({});
  const [isChangingPassword, setIsChangingPassword] = useState(false);
  const [passwordSuccessMsg, setPasswordSuccessMsg] = useState<string | null>(null);

  // Sync state when user profile updates
  useEffect(() => {
    if (user) {
      setDisplayName(user.displayName || '');
      setPhoneNumber(user.phoneNumber || '');
    }
  }, [user]);

  const formatDate = (isoString?: string): string => {
    if (!isoString) return '—';
    try {
      return new Date(isoString).toLocaleDateString(undefined, {
        month: 'long',
        day: 'numeric',
        year: 'numeric',
      });
    } catch {
      return isoString;
    }
  };

  const storageMode = import.meta.env.VITE_AUTH_STORAGE_MODE || 'session';

  // --- Profile Validation & Submit ---
  const validateProfile = (): boolean => {
    const errs: Record<string, string> = {};
    if (!displayName.trim()) {
      errs.displayName = 'Full name is required';
    } else if (displayName.trim().length < 2) {
      errs.displayName = 'Full name must be at least 2 characters';
    } else if (displayName.trim().length > 100) {
      errs.displayName = 'Full name cannot exceed 100 characters';
    }

    if (phoneNumber && phoneNumber.trim().length > 30) {
      errs.phoneNumber = 'Phone number cannot exceed 30 characters';
    }

    setProfileErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleProfileSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validateProfile()) return;

    setIsSavingProfile(true);
    setProfileErrors({});

    try {
      await updateProfile({
        displayName: displayName.trim(),
        phoneNumber: phoneNumber.trim() || null,
      });
      addToast('Profile updated successfully!', 'success');
      setIsEditingProfile(false);
      await refreshProfile();
    } catch (err: unknown) {
      const apiErr = err as { message?: string; validationErrors?: Record<string, string> };
      if (apiErr.validationErrors) {
        setProfileErrors(apiErr.validationErrors);
      } else {
        const msg = apiErr.message || 'Failed to update profile. Please try again.';
        setProfileErrors({ general: msg });
        addToast(msg, 'error');
      }
    } finally {
      setIsSavingProfile(false);
    }
  };

  const handleCancelProfileEdit = () => {
    setDisplayName(user?.displayName || '');
    setPhoneNumber(user?.phoneNumber || '');
    setProfileErrors({});
    setIsEditingProfile(false);
  };

  // --- Password Change Validation & Submit ---
  const validatePasswordChange = (): boolean => {
    const errs: Record<string, string> = {};

    if (!currentPassword) {
      errs.currentPassword = 'Current password is required';
    }

    if (!newPassword) {
      errs.newPassword = 'New password is required';
    } else if (newPassword.length < 8) {
      errs.newPassword = 'New password must be at least 8 characters';
    } else if (newPassword.length > 100) {
      errs.newPassword = 'New password cannot exceed 100 characters';
    }

    if (!confirmPassword) {
      errs.confirmPassword = 'Password confirmation is required';
    } else if (newPassword !== confirmPassword) {
      errs.confirmPassword = 'New passwords do not match';
    }

    if (currentPassword && newPassword && currentPassword === newPassword) {
      errs.newPassword = 'New password must be different from current password';
    }

    setPasswordErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handlePasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setPasswordSuccessMsg(null);

    if (!validatePasswordChange()) return;

    setIsChangingPassword(true);
    setPasswordErrors({});

    try {
      const response = await userApi.changePassword({
        currentPassword,
        newPassword,
        confirmPassword,
      });

      const successText = response.message || 'Password changed successfully.';
      setPasswordSuccessMsg(successText);
      addToast(successText, 'success');

      // Clear password fields on success
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err: unknown) {
      const apiErr = err as { message?: string; validationErrors?: Record<string, string> };
      if (apiErr.validationErrors) {
        setPasswordErrors(apiErr.validationErrors);
      } else {
        const msg = apiErr.message || 'Failed to change password. Please check your credentials.';
        setPasswordErrors({ general: msg });
        addToast(msg, 'error');
      }
    } finally {
      setIsChangingPassword(false);
    }
  };

  const handleResetPasswordForm = () => {
    setCurrentPassword('');
    setNewPassword('');
    setConfirmPassword('');
    setPasswordErrors({});
    setPasswordSuccessMsg(null);
  };

  return (
    <div className="p-6 sm:p-8 max-w-4xl mx-auto space-y-8">
      {/* Page Header */}
      <div className="pb-6 border-b border-slate-200">
        <h1 className="text-2xl font-bold text-slate-900 tracking-tight flex items-center gap-2">
          <User className="w-6 h-6 text-indigo-600" />
          User Profile & Security
        </h1>
        <p className="text-xs text-slate-500 mt-1">
          Manage your authenticated identity, personal contact information, and security credentials.
        </p>
      </div>

      {/* Profile Overview & Information Card */}
      <div className="p-6 sm:p-8 bg-white rounded-2xl border border-slate-200/80 shadow-2xs space-y-6" data-testid="profile-card">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="flex items-center gap-4">
            <div className="w-16 h-16 rounded-2xl bg-indigo-600 text-white flex items-center justify-center font-bold text-2xl uppercase shadow-md shrink-0">
              {user?.displayName ? user.displayName.charAt(0) : 'U'}
            </div>
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <h2 className="text-lg font-bold text-slate-900" data-testid="profile-display-name">
                  {user?.displayName || 'User'}
                </h2>
                <Badge variant="indigo" size="sm" data-testid="profile-role-badge">
                  {user?.role || 'ROLE_USER'}
                </Badge>
              </div>
              <p className="text-xs text-slate-500 mt-0.5" data-testid="profile-email">
                {user?.email}
              </p>
            </div>
          </div>

          {!isEditingProfile && (
            <Button
              variant="outline"
              size="sm"
              onClick={() => setIsEditingProfile(true)}
              leftIcon={<Edit2 className="w-3.5 h-3.5 text-indigo-600" />}
              data-testid="edit-profile-button"
            >
              Edit Profile
            </Button>
          )}
        </div>

        {/* General Error Banner */}
        {profileErrors.general && (
          <div className="flex items-center gap-2.5 p-3.5 rounded-xl bg-rose-50 border border-rose-200 text-rose-800 text-xs">
            <AlertCircle className="w-4 h-4 shrink-0 text-rose-600" />
            <span className="font-medium">{profileErrors.general}</span>
          </div>
        )}

        {/* Read-Only Information Grid vs Edit Form */}
        {!isEditingProfile ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-4 border-t border-slate-100 text-xs">
            <div className="p-4 rounded-xl bg-slate-50 border border-slate-100 flex items-start gap-3">
              <Mail className="w-4 h-4 text-indigo-600 shrink-0 mt-0.5" />
              <div>
                <span className="text-slate-400 block font-normal">Email Address</span>
                <span className="font-semibold text-slate-800">{user?.email}</span>
                <span className="text-[10px] text-slate-400 block mt-0.5">Primary identity (read-only)</span>
              </div>
            </div>

            <div className="p-4 rounded-xl bg-slate-50 border border-slate-100 flex items-start gap-3">
              <Phone className="w-4 h-4 text-indigo-600 shrink-0 mt-0.5" />
              <div>
                <span className="text-slate-400 block font-normal">Phone Number</span>
                <span className="font-semibold text-slate-800" data-testid="profile-phone-display">
                  {user?.phoneNumber || 'Not provided'}
                </span>
                <span className="text-[10px] text-slate-400 block mt-0.5">Contact phone</span>
              </div>
            </div>

            <div className="p-4 rounded-xl bg-slate-50 border border-slate-100 flex items-start gap-3">
              <Calendar className="w-4 h-4 text-indigo-600 shrink-0 mt-0.5" />
              <div>
                <span className="text-slate-400 block font-normal">Member Since</span>
                <span className="font-semibold text-slate-800">{formatDate(user?.createdAt)}</span>
              </div>
            </div>

            <div className="p-4 rounded-xl bg-slate-50 border border-slate-100 flex items-start gap-3">
              <Shield className="w-4 h-4 text-indigo-600 shrink-0 mt-0.5" />
              <div>
                <span className="text-slate-400 block font-normal">Client Storage Policy</span>
                <span className="font-semibold text-slate-800">
                  {storageMode === 'memory' ? 'In-Memory (Ephemeral)' : 'Session Storage (Tab-Scoped)'}
                </span>
              </div>
            </div>
          </div>
        ) : (
          <form onSubmit={handleProfileSubmit} noValidate className="space-y-4 pt-4 border-t border-slate-100" data-testid="profile-edit-form">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <Input
                label="Full / Display Name"
                type="text"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder="Jane Doe"
                error={profileErrors.displayName}
                leftIcon={<User className="w-4 h-4" />}
                required
                data-testid="profile-name-input"
              />

              <Input
                label="Phone Number (Optional)"
                type="tel"
                value={phoneNumber}
                onChange={(e) => setPhoneNumber(e.target.value)}
                placeholder="+1 (555) 000-0000"
                error={profileErrors.phoneNumber}
                leftIcon={<Phone className="w-4 h-4" />}
                data-testid="profile-phone-input"
              />
            </div>

            {/* Read-Only Email Display */}
            <div>
              <Input
                label="Email Address"
                type="email"
                value={user?.email || ''}
                disabled
                helperText="Email address serves as your primary identity and cannot be modified."
                leftIcon={<Mail className="w-4 h-4" />}
                data-testid="profile-email-readonly"
              />
            </div>

            {/* Edit Action Buttons */}
            <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-100">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={handleCancelProfileEdit}
                disabled={isSavingProfile}
                leftIcon={<X className="w-3.5 h-3.5" />}
                data-testid="cancel-profile-button"
              >
                Cancel
              </Button>

              <Button
                type="submit"
                variant="primary"
                size="sm"
                isLoading={isSavingProfile}
                leftIcon={<Save className="w-3.5 h-3.5" />}
                data-testid="save-profile-button"
              >
                Save Changes
              </Button>
            </div>
          </form>
        )}
      </div>

      {/* Change Password Card */}
      <div className="p-6 sm:p-8 bg-white rounded-2xl border border-slate-200/80 shadow-2xs space-y-6" data-testid="change-password-card">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-amber-50 border border-amber-200 text-amber-600 flex items-center justify-center shrink-0">
            <KeyRound className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-slate-900">Change Password</h2>
            <p className="text-xs text-slate-500 mt-0.5">
              Update your account password with strong credentials (minimum 8 characters).
            </p>
          </div>
        </div>

        {/* Password Success Banner */}
        {passwordSuccessMsg && (
          <div className="flex items-center gap-2.5 p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-xs" data-testid="password-success-banner">
            <CheckCircle className="w-4 h-4 shrink-0 text-emerald-600" />
            <span className="font-medium">{passwordSuccessMsg}</span>
          </div>
        )}

        {/* Password Error Banner */}
        {passwordErrors.general && (
          <div className="flex items-center gap-2.5 p-3.5 rounded-xl bg-rose-50 border border-rose-200 text-rose-800 text-xs" data-testid="password-error-banner">
            <AlertCircle className="w-4 h-4 shrink-0 text-rose-600" />
            <span className="font-medium">{passwordErrors.general}</span>
          </div>
        )}

        <form onSubmit={handlePasswordSubmit} noValidate className="space-y-4 pt-2" data-testid="change-password-form">
          <Input
            label="Current Password"
            type="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            placeholder="Enter your existing password"
            error={passwordErrors.currentPassword}
            leftIcon={<Lock className="w-4 h-4" />}
            required
            autoComplete="current-password"
            data-testid="current-password-input"
          />

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <Input
              label="New Password"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              placeholder="At least 8 characters"
              error={passwordErrors.newPassword}
              leftIcon={<KeyRound className="w-4 h-4" />}
              required
              autoComplete="new-password"
              data-testid="new-password-input"
            />

            <Input
              label="Confirm New Password"
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder="Re-enter new password"
              error={passwordErrors.confirmPassword}
              leftIcon={<KeyRound className="w-4 h-4" />}
              required
              autoComplete="new-password"
              data-testid="confirm-password-input"
            />
          </div>

          <div className="flex items-center justify-between pt-4 border-t border-slate-100 flex-wrap gap-3">
            <div className="text-[11px] text-slate-400">
              Password must be 8–100 characters and differ from your current password.
            </div>

            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={handleResetPasswordForm}
                disabled={isChangingPassword || (!currentPassword && !newPassword && !confirmPassword)}
                leftIcon={<RotateCcw className="w-3.5 h-3.5" />}
                data-testid="reset-password-button"
              >
                Reset
              </Button>

              <Button
                type="submit"
                variant="primary"
                size="sm"
                isLoading={isChangingPassword}
                leftIcon={<Lock className="w-3.5 h-3.5" />}
                data-testid="submit-password-button"
              >
                Update Password
              </Button>
            </div>
          </div>
        </form>
      </div>

      {/* Sign Out Card */}
      <div className="p-6 bg-white rounded-2xl border border-slate-200/80 shadow-2xs flex items-center justify-between flex-wrap gap-4">
        <div>
          <h3 className="text-sm font-bold text-slate-900">Active Session</h3>
          <p className="text-xs text-slate-500 mt-0.5">
            Sign out of your active session on this device.
          </p>
        </div>

        <Button
          variant="danger"
          size="sm"
          onClick={logout}
          leftIcon={<LogOut className="w-4 h-4" />}
          data-testid="profile-logout-button"
        >
          Sign Out
        </Button>
      </div>
    </div>
  );
};

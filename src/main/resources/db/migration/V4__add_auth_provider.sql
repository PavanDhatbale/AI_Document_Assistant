-- ==============================================================================
-- AI Document Assistant - V4 Migration: Add Auth Provider
-- Supports Google Identity OAuth2 and Local Authentication
-- ==============================================================================

ALTER TABLE users ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';

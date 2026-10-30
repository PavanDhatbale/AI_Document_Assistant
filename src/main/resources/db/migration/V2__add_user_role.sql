-- ==============================================================================
-- AI Document Assistant - V2 Migration: Add Role to Users Table
-- Default role is ROLE_USER (least privilege)
-- ==============================================================================

ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'ROLE_USER';

-- Forces a password change after an administrator issues a temporary password.
--
-- Without this, an issued temporary password is a permanent, usable credential
-- that the administrator who generated it also knows. The flag turns that into
-- a one-time hand-off: the account can sign in, but cannot do anything until it
-- chooses a password the administrator never sees.
ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

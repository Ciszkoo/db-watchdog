CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE databases
    ADD COLUMN technical_password_ciphertext BYTEA;

UPDATE databases
SET technical_password_ciphertext = pgp_sym_encrypt(
    technical_password,
    current_setting('app.technical_credentials_key'),
    'cipher-algo=aes256'
)
WHERE technical_password IS NOT NULL;

ALTER TABLE databases
    ALTER COLUMN technical_password_ciphertext SET NOT NULL;

ALTER TABLE databases
    ALTER COLUMN technical_password DROP NOT NULL;

UPDATE databases
SET technical_password = NULL
WHERE technical_password IS NOT NULL;

ALTER TABLE databases
    DROP COLUMN technical_password;

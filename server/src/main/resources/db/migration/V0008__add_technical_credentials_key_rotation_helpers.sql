CREATE OR REPLACE FUNCTION decrypt_technical_password(ciphertext BYTEA)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
DECLARE
    current_key TEXT := current_setting('app.technical_credentials_key', true);
    previous_key TEXT := current_setting('app.previous_technical_credentials_key', true);
BEGIN
    IF current_key IS NOT NULL AND btrim(current_key) <> '' THEN
        BEGIN
            RETURN pgp_sym_decrypt(ciphertext, current_key)::TEXT;
        EXCEPTION
            WHEN OTHERS THEN NULL;
        END;
    END IF;

    IF previous_key IS NOT NULL AND btrim(previous_key) <> '' THEN
        BEGIN
            RETURN pgp_sym_decrypt(ciphertext, previous_key)::TEXT;
        EXCEPTION
            WHEN OTHERS THEN
                RAISE EXCEPTION 'Technical credentials could not be decrypted with current or previous key';
        END;
    END IF;

    RAISE EXCEPTION 'Technical credentials could not be decrypted with current or previous key';
END;
$$;

CREATE OR REPLACE FUNCTION technical_password_needs_rewrap(ciphertext BYTEA)
RETURNS BOOLEAN
LANGUAGE plpgsql
AS $$
DECLARE
    current_key TEXT := current_setting('app.technical_credentials_key', true);
    previous_key TEXT := current_setting('app.previous_technical_credentials_key', true);
BEGIN
    IF current_key IS NOT NULL AND btrim(current_key) <> '' THEN
        BEGIN
            PERFORM pgp_sym_decrypt(ciphertext, current_key)::TEXT;
            RETURN FALSE;
        EXCEPTION
            WHEN OTHERS THEN NULL;
        END;
    END IF;

    IF previous_key IS NOT NULL AND btrim(previous_key) <> '' THEN
        BEGIN
            PERFORM pgp_sym_decrypt(ciphertext, previous_key)::TEXT;
            RETURN TRUE;
        EXCEPTION
            WHEN OTHERS THEN
                RAISE EXCEPTION 'Technical credentials could not be decrypted with current or previous key';
        END;
    END IF;

    RAISE EXCEPTION 'Technical credentials could not be decrypted with current or previous key';
END;
$$;

CREATE OR REPLACE FUNCTION rewrap_technical_password(ciphertext BYTEA)
RETURNS BYTEA
LANGUAGE sql
AS $$
    SELECT pgp_sym_encrypt(
        decrypt_technical_password(ciphertext),
        current_setting('app.technical_credentials_key'),
        'cipher-algo=aes256'
    );
$$;

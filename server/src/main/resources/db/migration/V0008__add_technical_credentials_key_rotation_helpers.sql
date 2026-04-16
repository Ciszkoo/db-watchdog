DO $migration$
DECLARE
    schema_name TEXT := current_schema();
    pgcrypto_schema_name TEXT;
BEGIN
    SELECT namespace.nspname
    INTO pgcrypto_schema_name
    FROM pg_extension extension
             JOIN pg_namespace namespace ON namespace.oid = extension.extnamespace
    WHERE extension.extname = 'pgcrypto';

    IF pgcrypto_schema_name IS NULL THEN
        RAISE EXCEPTION 'pgcrypto extension must be installed before technical credential rotation helpers';
    END IF;

    EXECUTE format(
        $function$
        CREATE OR REPLACE FUNCTION %1$I.decrypt_technical_password(
            ciphertext BYTEA,
            current_key TEXT,
            previous_key TEXT
        )
        RETURNS TEXT
        LANGUAGE plpgsql
        AS $body$
        BEGIN
            IF current_key IS NOT NULL AND btrim(current_key) <> '' THEN
                BEGIN
                    RETURN %2$I.pgp_sym_decrypt(ciphertext, current_key)::TEXT;
                EXCEPTION
                    WHEN OTHERS THEN NULL;
                END;
            END IF;

            IF previous_key IS NOT NULL AND btrim(previous_key) <> '' THEN
                BEGIN
                    RETURN %2$I.pgp_sym_decrypt(ciphertext, previous_key)::TEXT;
                EXCEPTION
                    WHEN OTHERS THEN
                        RAISE EXCEPTION 'Technical credentials could not be decrypted with current or previous key';
                END;
            END IF;

            RAISE EXCEPTION 'Technical credentials could not be decrypted with current or previous key';
        END;
        $body$;
        $function$,
        schema_name,
        pgcrypto_schema_name
    );

    EXECUTE format(
        $function$
        CREATE OR REPLACE FUNCTION %1$I.decrypt_technical_password(ciphertext BYTEA)
        RETURNS TEXT
        LANGUAGE sql
        AS $body$
            SELECT %1$I.decrypt_technical_password(
                ciphertext,
                current_setting('app.technical_credentials_key', true),
                current_setting('app.previous_technical_credentials_key', true)
            );
        $body$;
        $function$,
        schema_name
    );

    EXECUTE format(
        $function$
        CREATE OR REPLACE FUNCTION %1$I.technical_password_needs_rewrap(ciphertext BYTEA)
        RETURNS BOOLEAN
        LANGUAGE plpgsql
        AS $body$
        DECLARE
            current_key TEXT := current_setting('app.technical_credentials_key', true);
            previous_key TEXT := current_setting('app.previous_technical_credentials_key', true);
        BEGIN
            IF current_key IS NOT NULL AND btrim(current_key) <> '' THEN
                BEGIN
                    PERFORM %2$I.pgp_sym_decrypt(ciphertext, current_key)::TEXT;
                    RETURN FALSE;
                EXCEPTION
                    WHEN OTHERS THEN NULL;
                END;
            END IF;

            IF previous_key IS NOT NULL AND btrim(previous_key) <> '' THEN
                BEGIN
                    PERFORM %2$I.pgp_sym_decrypt(ciphertext, previous_key)::TEXT;
                    RETURN TRUE;
                EXCEPTION
                    WHEN OTHERS THEN
                        RAISE EXCEPTION 'Technical credentials could not be decrypted with current or previous key';
                END;
            END IF;

            RAISE EXCEPTION 'Technical credentials could not be decrypted with current or previous key';
        END;
        $body$;
        $function$,
        schema_name,
        pgcrypto_schema_name
    );

    EXECUTE format(
        $function$
        CREATE OR REPLACE FUNCTION %1$I.rewrap_technical_password(ciphertext BYTEA)
        RETURNS BYTEA
        LANGUAGE sql
        AS $body$
            SELECT %2$I.pgp_sym_encrypt(
                %1$I.decrypt_technical_password(ciphertext),
                current_setting('app.technical_credentials_key'),
                'cipher-algo=aes256'
            );
        $body$;
        $function$,
        schema_name,
        pgcrypto_schema_name
    );
END;
$migration$;

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
        RAISE EXCEPTION 'pgcrypto extension must be installed before technical credential encryption helpers';
    END IF;

    EXECUTE format(
        $function$
        CREATE OR REPLACE FUNCTION %1$I.encrypt_technical_password(plaintext TEXT)
        RETURNS BYTEA
        LANGUAGE sql
        AS $body$
            SELECT %2$I.pgp_sym_encrypt(
                plaintext,
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

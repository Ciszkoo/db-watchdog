#!/usr/bin/env bash

psql -v ON_ERROR_STOP=1 --username postgres <<-EOSQL
  CREATE DATABASE db_watchdog;

  CREATE ROLE db_watchdog NOLOGIN;
  GRANT ALL ON DATABASE db_watchdog TO db_watchdog;

  \c db_watchdog
  GRANT ALL ON SCHEMA public TO db_watchdog;

  CREATE USER db_watchdog_1 NOSUPERUSER NOCREATEDB NOINHERIT LOGIN ENCRYPTED PASSWORD '${POSTGRES_PASSWORD}';
  GRANT db_watchdog TO db_watchdog_1;
  ALTER USER db_watchdog_1 SET ROLE db_watchdog;
EOSQL
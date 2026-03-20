#!/usr/bin/env bash

psql -v ON_ERROR_STOP=1 --username postgres <<-EOSQL
  CREATE DATABASE external_db;

  CREATE ROLE my_user NOLOGIN;
  GRANT ALL ON DATABASE external_db TO my_user;

  \c external_db
  GRANT ALL ON SCHEMA public TO my_user;

  CREATE USER proxy_user_1 NOSUPERUSER NOCREATEDB NOINHERIT LOGIN ENCRYPTED PASSWORD 'proxy_pass';
  GRANT my_user TO proxy_user_1;
  ALTER USER proxy_user_1 SET ROLE my_user;
EOSQL
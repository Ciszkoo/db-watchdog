ALTER TABLE databases
    RENAME COLUMN "user" TO technical_user;

ALTER TABLE databases
    RENAME COLUMN password TO technical_password;

ALTER TABLE databases
    RENAME COLUMN "schema" TO database_name;

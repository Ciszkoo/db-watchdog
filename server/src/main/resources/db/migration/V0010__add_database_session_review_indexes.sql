CREATE INDEX database_sessions_user_id_started_at_idx
    ON database_sessions (user_id, started_at DESC, id DESC);

CREATE INDEX database_sessions_database_id_started_at_idx
    ON database_sessions (database_id, started_at DESC, id DESC);

CREATE INDEX users_team_id_idx
    ON users (team_id);

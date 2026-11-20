package com.orchestrator.server.db

/**
 * The database's tables, as a list of migrations. Each runs once, in order, and the database
 * remembers how many have run (SQLite's user_version); never edit one that has been released,
 * add another.
 */
object Schema {
    private val migrations: List<String> = listOf(
        // 1: accounts, sign-ins, settings; devices, and what's waiting for each (sealed: only the device can read it).
        """
        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            username TEXT NOT NULL UNIQUE COLLATE NOCASE,
            display_name TEXT NOT NULL,
            password_hash TEXT,
            role TEXT NOT NULL,
            break_glass INTEGER NOT NULL DEFAULT 0,
            disabled INTEGER NOT NULL DEFAULT 0,
            oidc_issuer TEXT,
            oidc_subject TEXT,
            email TEXT,
            oidc_groups TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            last_login_at INTEGER,
            must_change_password INTEGER NOT NULL DEFAULT 0,
            password_set_by_admin_at INTEGER,
            UNIQUE (oidc_issuer, oidc_subject)
        );
        CREATE TABLE sessions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            last_seen_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL
        );
        CREATE INDEX sessions_user ON sessions(user_id);
        CREATE TABLE settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
        CREATE TABLE user_settings (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            key TEXT NOT NULL,
            value TEXT NOT NULL,
            PRIMARY KEY (user_id, key)
        );
        CREATE TABLE devices (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            session_id TEXT NOT NULL UNIQUE REFERENCES sessions(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            kind TEXT NOT NULL,
            model TEXT NOT NULL DEFAULT '',
            public_key TEXT NOT NULL,
            capabilities TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            last_seen_at INTEGER NOT NULL,
            battery_level INTEGER,
            battery_charging INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX devices_user ON devices(user_id);
        CREATE TABLE messages (
            id TEXT PRIMARY KEY,
            to_device TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            from_device TEXT,
            from_name TEXT NOT NULL DEFAULT '',
            sent_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            collapse_key TEXT,
            sealed TEXT NOT NULL
        );
        CREATE INDEX messages_to ON messages(to_device, sent_at)
        """,
    )

    fun migrate(db: Database) = db.blockingTx {
        val current = long("PRAGMA user_version").toInt()
        require(current <= migrations.size) {
            "This database was made by a newer SuperAwesomeMagicalOrchestrator server (schema $current); update the server"
        }
        migrations.drop(current).forEach { migration ->
            migration.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(::execute)
        }
        execute("PRAGMA user_version = ${migrations.size}")
    }
}

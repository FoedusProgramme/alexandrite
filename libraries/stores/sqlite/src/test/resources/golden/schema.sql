PRAGMA user_version = 7;

CREATE TABLE chat_states (
    plugin TEXT NOT NULL,
    name TEXT NOT NULL,
    agent TEXT NOT NULL,
    chat_id INTEGER NOT NULL REFERENCES chats (id),
    value TEXT NOT NULL,
    PRIMARY KEY (plugin, name, agent, chat_id)
) WITHOUT ROWID;

CREATE TABLE chats (
    id INTEGER PRIMARY KEY,
    address TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL
);

CREATE TABLE conversations (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    agent TEXT NOT NULL,
    chat_id INTEGER NOT NULL REFERENCES chats (id),
    state TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    sealed_at INTEGER,
    successor TEXT REFERENCES conversations (id),
    run_id TEXT,
    parent_turn TEXT REFERENCES turns (id),
    parent_conversation TEXT REFERENCES conversations (id),
    parent_call TEXT,
    root_turn TEXT REFERENCES turns (id),
    root_conversation TEXT REFERENCES conversations (id),
    depth INTEGER NOT NULL,
    forked_from TEXT REFERENCES conversations (id),
    forked_through_entry INTEGER
    , tainted_at INTEGER, taint_reason TEXT);

CREATE INDEX conversations_chat ON conversations (chat_id, agent);

CREATE TABLE current_conversations (
    agent TEXT NOT NULL,
    chat_id INTEGER NOT NULL REFERENCES chats (id),
    kind TEXT NOT NULL,
    conversation TEXT NOT NULL REFERENCES conversations (id),
    PRIMARY KEY (agent, chat_id, kind)
) WITHOUT ROWID;

CREATE TABLE entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation TEXT NOT NULL REFERENCES conversations (id),
    turn TEXT NOT NULL REFERENCES turns (id),
    type TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    format INTEGER NOT NULL,
    body TEXT NOT NULL,
    message_chat_id INTEGER REFERENCES chats (id),
    message_id TEXT
);

CREATE INDEX entries_conversation ON entries (conversation, id);

CREATE INDEX entries_message ON entries (message_chat_id, message_id) WHERE message_id IS NOT NULL;

CREATE INDEX entries_turn ON entries (turn);

CREATE VIRTUAL TABLE entry_grams USING fts4(grams, tokenize = simple);

CREATE TABLE 'entry_grams_content'(docid INTEGER PRIMARY KEY, 'c0grams');

CREATE TABLE 'entry_grams_docsize'(docid INTEGER PRIMARY KEY, size BLOB);

CREATE TABLE 'entry_grams_segdir'(level INTEGER,idx INTEGER,start_block INTEGER,leaves_end_block INTEGER,end_block INTEGER,root BLOB,PRIMARY KEY(level, idx));

CREATE TABLE 'entry_grams_segments'(blockid INTEGER PRIMARY KEY, block BLOB);

CREATE TABLE 'entry_grams_stat'(id INTEGER PRIMARY KEY, value BLOB);

CREATE TABLE entry_media (
    entry_id INTEGER NOT NULL REFERENCES entries (id),
    media_id TEXT NOT NULL REFERENCES media (id),
    PRIMARY KEY (entry_id, media_id)
) WITHOUT ROWID;

CREATE INDEX entry_media_media ON entry_media (media_id);

CREATE TABLE media (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    media_type TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX media_sha256 ON media (sha256);

CREATE TABLE records (
    plugin TEXT NOT NULL,
    collection TEXT NOT NULL,
    record_key TEXT NOT NULL,
    value TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    created_by TEXT,
    updated_at INTEGER NOT NULL,
    updated_by TEXT,
    UNIQUE (plugin, collection, record_key)
);

CREATE TABLE turns (
    id TEXT PRIMARY KEY,
    conversation TEXT NOT NULL REFERENCES conversations (id),
    agent TEXT NOT NULL,
    chat_id INTEGER NOT NULL REFERENCES chats (id),
    kind TEXT NOT NULL,
    actor TEXT,
    run_id TEXT,
    parent_turn TEXT REFERENCES turns (id),
    parent_conversation TEXT REFERENCES conversations (id),
    parent_call TEXT,
    root_turn TEXT REFERENCES turns (id),
    root_conversation TEXT REFERENCES conversations (id),
    depth INTEGER NOT NULL,
    started_at INTEGER NOT NULL,
    ended_at INTEGER,
    outcome TEXT
    , origin_chat_id INTEGER REFERENCES chats (id));

CREATE INDEX turns_conversation ON turns (conversation);

CREATE INDEX turns_unended ON turns (started_at) WHERE ended_at IS NULL;

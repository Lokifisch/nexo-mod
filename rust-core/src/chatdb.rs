//! Persistent chat history: append every received chat line, search it later.
//!
//! Storage is SQLite (bundled — see `Cargo.toml` for why linking the system
//! one is not an option) with an FTS5 full-text index over the message text.
//!
//! Two constraints come from the FFI side and are not negotiable:
//!
//! 1. **Every method takes `&self`.** The registry hands out `Arc<ChatDb>`, and
//!    two Java threads can hold the same handle at once (the tick thread
//!    inserting while the render thread searches). State lives behind a writer
//!    `Mutex` and a reader pool inside this struct — see `sqlite.rs` for why
//!    those are two different things.
//! 2. **`search` runs on a job-pool worker.** It must not touch the JVM, and it
//!    polls `cancel` (twice over: SQLite's progress handler aborts the
//!    statement itself, and the row loop checks between rows) so closing the
//!    search screen actually stops the scan.
//!
//! ## Schema
//!
//! ```sql
//! messages(id INTEGER PRIMARY KEY, ts_millis, server, sender, message)
//! messages_fts USING fts5(message, content='messages', content_rowid='id')
//! ```
//!
//! The FTS table is **external-content** (`content=`), so the message text is
//! stored exactly once, in `messages`; FTS5 keeps only the inverted index and
//! reads the original text back through the content table when it needs it.
//! The alternative — a contentless-plus-duplicate ordinary FTS table — would
//! roughly double the size of the largest thing in the database for no gain,
//! and a chat history is expected to run to hundreds of megabytes over a year.
//! The cost of `content=` is that FTS5 does not see writes to `messages` by
//! itself, so three triggers forward insert/update/delete into it; those are
//! created alongside the tables and are the only reason `messages` is written
//! through anything but a plain `INSERT`.
//!
//! Only `message` is indexed. `server` and `sender` are ordinary columns:
//! they are filter/grouping keys, not text anybody wants stemmed or
//! prefix-matched, and indexing them in FTS would make `sender:` behave like a
//! full-text column filter — which is exactly the syntax the escaper below
//! spends its time neutralising.
//!
//! ## Query escaping
//!
//! The search box takes free text typed by a player, and FTS5's `MATCH` operand
//! is a *query language*, not a string. `foo OR bar`, `sender:x`, `NEAR(a b)`,
//! `"unbalanced`, and a bare `*` are all either syntax errors or queries that
//! do something the player did not ask for. Bound parameters do not help: the
//! parameter is passed to SQLite intact and SQLite then parses it as a query.
//!
//! So [`escape_fts_query`] rewrites the input into a query that can only ever
//! be a conjunction of literal phrases. See its docs for the rules.

use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};

use rusqlite::Connection;

use crate::error::Result;
use crate::jobs::CancelToken;
use crate::payload::{self, PayloadWriter};
use crate::sqlite::{self, ReaderPool};

const SCHEMA_VERSION: i32 = 1;

/// Terms past this are dropped from a search.
///
/// Not a security limit — a 500-term FTS query is not dangerous, it is just
/// slow, and nobody typed 500 terms on purpose. It exists so a paste accident
/// into the search box costs one useless query rather than a stalled worker.
const MAX_QUERY_TERMS: usize = 32;

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS messages (
    id        INTEGER PRIMARY KEY,
    ts_millis INTEGER NOT NULL,
    server    TEXT    NOT NULL,
    sender    TEXT    NOT NULL,
    message   TEXT    NOT NULL
);

-- Every read path here is `ORDER BY ts_millis DESC LIMIT n`, so this index is
-- what turns 'show me the last 50 lines' from a full scan plus a sort into a
-- 50-row walk backwards along the index.
CREATE INDEX IF NOT EXISTS messages_ts ON messages (ts_millis DESC, id DESC);

CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5 (
    message,
    content      = 'messages',
    content_rowid = 'id',
    -- `remove_diacritics 2` folds 'ö' to 'o' correctly (the '1' variant is the
    -- legacy one that mishandles multi-byte sequences). Chat is not ASCII.
    tokenize     = \"unicode61 remove_diacritics 2\"
);

-- External-content FTS does not observe writes to the content table on its
-- own; these three are the whole mechanism.
CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts (rowid, message) VALUES (new.id, new.message);
END;
CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
    INSERT INTO messages_fts (messages_fts, rowid, message)
        VALUES ('delete', old.id, old.message);
END;
CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
    INSERT INTO messages_fts (messages_fts, rowid, message)
        VALUES ('delete', old.id, old.message);
    INSERT INTO messages_fts (rowid, message) VALUES (new.id, new.message);
END;
";

const SQL_INSERT: &str = "INSERT INTO messages (ts_millis, server, sender, message)
                          VALUES (?1, ?2, ?3, ?4)";

const SQL_SEARCH: &str = "SELECT m.ts_millis, m.server, m.sender, m.message
                            FROM messages_fts
                            JOIN messages AS m ON m.id = messages_fts.rowid
                           WHERE messages_fts MATCH ?1
                           ORDER BY m.ts_millis DESC, m.id DESC
                           LIMIT ?2";

const SQL_RECENT: &str = "SELECT ts_millis, server, sender, message
                            FROM messages
                           ORDER BY ts_millis DESC, id DESC
                           LIMIT ?1";

/// One stored chat line. Mirrors the `KIND_CHAT_SEARCH` record layout in
/// `payload.rs`; changing the fields means changing that layout, the encoder
/// below, and the Java reader together.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatRecord {
    /// Wall-clock milliseconds since the epoch, as Java's `System.currentTimeMillis`
    /// reports them. Signed because that is what `jlong` is; a negative value
    /// (pre-1970) is nonsense but is stored rather than rejected, since the only
    /// way to get one is a user with a badly set clock and losing their chat
    /// log over it would be worse.
    pub ts_millis: i64,
    /// Server address the line was seen on, or a marker for singleplayer. The
    /// caller decides the exact convention; storage treats it as an opaque
    /// grouping key.
    pub server: String,
    pub sender: String,
    pub message: String,
}

pub struct ChatDb {
    path: PathBuf,
    /// Serialises writes. SQLite permits exactly one writer regardless, so this
    /// lock costs nothing that WAL was not already going to charge — and it is
    /// held only for the duration of one `INSERT`, never across a search.
    writer: Mutex<Connection>,
    readers: ReaderPool,
}

impl ChatDb {
    /// Opens (or creates) the database at `path`, creating missing parent
    /// directories and the schema.
    pub fn open(path: &Path) -> Result<Self> {
        let writer = sqlite::open_writer(path)?;
        let found = sqlite::check_schema_version(&writer, SCHEMA_VERSION, "chat history")?;
        if found < SCHEMA_VERSION {
            writer.execute_batch(SCHEMA)?;
            sqlite::set_schema_version(&writer, SCHEMA_VERSION)?;
        }
        Ok(Self {
            path: path.to_path_buf(),
            writer: Mutex::new(writer),
            readers: ReaderPool::new(path),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    fn writer(&self) -> MutexGuard<'_, Connection> {
        // Poison recovery, as everywhere else in this crate: a panic caught by
        // `catch_unwind` must not permanently disable chat history for the rest
        // of the session, and there is no multi-statement invariant here that a
        // half-finished write could break — every insert is a single statement
        // in its own implicit transaction.
        self.writer.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Appends one line.
    ///
    /// Called from the client tick thread on every chat packet, so the cost has
    /// to be a WAL append and nothing else: the statement is prepared once and
    /// cached on the connection, and `synchronous=NORMAL` (see `sqlite.rs`)
    /// means the commit does not fsync. No explicit batching — a transaction
    /// spanning several messages would have to be closed by a timer, and a
    /// timer thread that can lose the tail of the log on a crash is a worse
    /// deal than one WAL append per chat line.
    pub fn insert(&self, record: ChatRecord) -> Result<()> {
        let conn = self.writer();
        let mut stmt = conn.prepare_cached(SQL_INSERT)?;
        stmt.execute(rusqlite::params![
            record.ts_millis,
            record.server,
            record.sender,
            record.message,
        ])?;
        Ok(())
    }

    /// Full-text search, newest first, capped at `limit` records.
    ///
    /// An empty query — or one that contains nothing the tokenizer would index,
    /// such as `***` — is **not** an error and does not match nothing: it
    /// returns the most recent `limit` lines. That is what a search screen
    /// wants on first open, and the alternative (an empty list until the player
    /// types) makes a working feature look broken.
    ///
    /// Runs on a worker thread. Cancellation is honoured in two places, because
    /// one is not enough: SQLite's progress handler can abort the statement
    /// while it is still sorting (which is where the time goes), and the row
    /// loop checks between rows for the streaming case.
    pub fn search(
        &self,
        query: &str,
        limit: usize,
        cancel: &CancelToken,
    ) -> Result<Vec<ChatRecord>> {
        if limit == 0 || cancel.is_cancelled() {
            return Ok(Vec::new());
        }
        let limit = limit as i64;
        let fts = escape_fts_query(query);

        let out = self.readers.with(cancel, |conn| {
            let mut stmt = match &fts {
                Some(_) => conn.prepare_cached(SQL_SEARCH)?,
                None => conn.prepare_cached(SQL_RECENT)?,
            };
            let mut rows = match &fts {
                Some(q) => stmt.query(rusqlite::params![q, limit])?,
                None => stmt.query(rusqlite::params![limit])?,
            };

            let mut out = Vec::new();
            while let Some(row) = rows.next()? {
                if cancel.is_cancelled() {
                    return Ok(Vec::new());
                }
                out.push(ChatRecord {
                    ts_millis: row.get(0)?,
                    server: row.get(1)?,
                    sender: row.get(2)?,
                    message: row.get(3)?,
                });
            }
            Ok(out)
        })?;
        // `None` is a cancelled statement, not a failure — see `ReaderPool::with`.
        Ok(out.unwrap_or_default())
    }

    /// Number of stored lines. Not on the FFI surface — it exists for tests and
    /// for anything that later wants to show the player how big their history
    /// has become.
    pub fn count(&self) -> Result<u64> {
        let conn = self.writer();
        let n: i64 = conn.query_row("SELECT COUNT(*) FROM messages", [], |row| row.get(0))?;
        Ok(n.max(0) as u64)
    }
}

/// Rewrites free text into an FTS5 query that can only be a conjunction of
/// literal phrases.
///
/// Returns `None` when the input contains nothing worth searching for, which
/// the caller turns into "show the most recent lines" rather than an error.
///
/// The rules, in order:
///
/// 1. Split on whitespace. At most [`MAX_QUERY_TERMS`] terms survive.
/// 2. A single trailing `*` on a term is kept as FTS5's prefix operator — that
///    is the one piece of query syntax a search box should honour, because
///    `dia*` is what people mean by "starts with".
/// 3. Everything else is wrapped in double quotes, making it an FTS5 *string*:
///    a literal phrase. Inside a string the only character with meaning is `"`,
///    which is escaped by doubling it. `OR`, `NOT`, `NEAR`, `:`, `^`, `-`, `(`,
///    `)` and a `*` anywhere but the end therefore all become ordinary text.
/// 4. A term with no alphanumeric character at all is dropped, because an FTS5
///    string that tokenizes to zero tokens is a syntax error — `""` does not
///    mean "match nothing", it means the query does not parse.
///
/// The result is joined with spaces, which is FTS5's implicit AND. Searching
/// `nether portal` finds lines containing both words, which is what every
/// search box in the world does.
pub fn escape_fts_query(raw: &str) -> Option<String> {
    let mut out = String::with_capacity(raw.len() + 8);
    let mut terms = 0usize;

    for token in raw.split_whitespace() {
        if terms >= MAX_QUERY_TERMS {
            break;
        }
        let (body, prefix) = match token.strip_suffix('*') {
            Some(body) => (body, true),
            None => (token, false),
        };
        if !body.chars().any(char::is_alphanumeric) {
            continue;
        }

        if terms > 0 {
            out.push(' ');
        }
        out.push('"');
        for ch in body.chars() {
            if ch == '"' {
                // Doubling is FTS5's only escape inside a string, and the only
                // reason this function exists: an unescaped quote would end the
                // phrase early and hand the rest of the player's text to the
                // query parser as syntax.
                out.push_str("\"\"");
            } else {
                out.push(ch);
            }
        }
        out.push('"');
        if prefix {
            out.push('*');
        }
        terms += 1;
    }

    (terms > 0).then_some(out)
}

/// Encodes search results into the `jobTake` wire format. Part of the FFI
/// contract rather than of the search implementation.
pub fn encode_search_results(records: &[ChatRecord]) -> Vec<u8> {
    if records.is_empty() {
        // Still tagged with the kind so the Java reader can assert it got the
        // result type it asked for, even when the answer is "nothing".
        return payload::empty(payload::KIND_CHAT_SEARCH);
    }
    let mut w = PayloadWriter::new(payload::KIND_CHAT_SEARCH);
    for r in records {
        w.begin_record();
        w.put_i64(r.ts_millis);
        w.put_str(&r.server);
        w.put_str(&r.sender);
        w.put_str(&r.message);
    }
    w.finish()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sqlite::testutil::TempDir;

    fn db(dir: &TempDir) -> ChatDb {
        ChatDb::open(&dir.file("chat_history.db")).expect("open")
    }

    fn line(db: &ChatDb, ts: i64, sender: &str, message: &str) {
        db.insert(ChatRecord {
            ts_millis: ts,
            server: "play.example.net".into(),
            sender: sender.into(),
            message: message.into(),
        })
        .expect("insert");
    }

    fn search(db: &ChatDb, q: &str) -> Vec<String> {
        db.search(q, 100, &CancelToken::never())
            .expect("search")
            .into_iter()
            .map(|r| r.message)
            .collect()
    }

    #[test]
    fn open_creates_missing_parents() {
        let dir = TempDir::new("chatdb-parents");
        let db = ChatDb::open(&dir.file("nested/deeper/chat.db")).expect("open");
        assert!(db.path().parent().expect("parent").is_dir());
    }

    #[test]
    fn reopening_keeps_the_history() {
        let dir = TempDir::new("chatdb-reopen");
        let path = dir.file("chat_history.db");
        {
            let db = ChatDb::open(&path).expect("open");
            line(&db, 1, "Steve", "diamonds in the deep dark");
        }
        let db = ChatDb::open(&path).expect("reopen");
        assert_eq!(db.count().unwrap(), 1);
        assert_eq!(search(&db, "diamonds").len(), 1);
    }

    #[test]
    fn search_is_newest_first_and_respects_the_limit() {
        let dir = TempDir::new("chatdb-order");
        let db = db(&dir);
        for i in 0..10 {
            line(&db, i, "Steve", &format!("creeper number {i}"));
        }
        let rows = db
            .search("creeper", 3, &CancelToken::never())
            .expect("search");
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[0].ts_millis, 9);
        assert_eq!(rows[2].ts_millis, 7);
    }

    #[test]
    fn multiple_terms_are_an_implicit_and() {
        let dir = TempDir::new("chatdb-and");
        let db = db(&dir);
        line(&db, 1, "a", "nether portal built");
        line(&db, 2, "b", "nether fortress found");
        assert_eq!(search(&db, "nether portal"), vec!["nether portal built"]);
    }

    #[test]
    fn a_trailing_star_is_a_prefix_search() {
        let dir = TempDir::new("chatdb-prefix");
        let db = db(&dir);
        line(&db, 1, "a", "enchanting table ready");
        assert_eq!(search(&db, "enchant*").len(), 1);
        assert_eq!(search(&db, "enchant").len(), 0, "without * it is exact");
    }

    // The point of the escaper. Every one of these is either an FTS5 syntax
    // error or a query that means something other than what was typed, if it
    // reaches MATCH unescaped.
    #[test]
    fn fts_syntax_in_the_query_is_literal_text_not_syntax() {
        let dir = TempDir::new("chatdb-escape");
        let db = db(&dir);
        line(&db, 1, "Steve", r#"he said "hello" and left"#);
        line(&db, 2, "Alex", "OR NOT AND NEAR are just words");
        line(&db, 3, "Herobrine", "price: 5* diamonds (negotiable)");
        line(&db, 4, "Steve", "^caret -dash sender:notavalue");

        for hostile in [
            r#"""#,
            r#""hello""#,
            r#""unbalanced"#,
            "*",
            "**",
            "***",
            "(",
            ")",
            "()",
            "-",
            "^",
            ":",
            "NEAR(",
            "NEAR(a b)",
            "a OR b",
            "sender:Steve",
            "message:*",
            r#"" OR message MATCH ""#,
            "\"\"\"\"\"",
            "{}[]",
            "\u{1F600}",
        ] {
            let got = db.search(hostile, 10, &CancelToken::never());
            assert!(
                got.is_ok(),
                "query {hostile:?} must not produce an FTS5 error: {:?}",
                got.err().map(|e| e.message().to_string())
            );
        }

        // ...and the escaped forms still find the right rows.
        assert_eq!(search(&db, r#""hello""#).len(), 1);
        assert_eq!(search(&db, "sender:Steve").len(), 0, "not a column filter");
        assert_eq!(search(&db, "NEAR").len(), 1, "NEAR is a word here");
        assert_eq!(search(&db, "negotiable").len(), 1);
    }

    #[test]
    fn a_query_with_nothing_searchable_shows_recent_lines() {
        let dir = TempDir::new("chatdb-empty");
        let db = db(&dir);
        for i in 0..5 {
            line(&db, i, "a", &format!("line {i}"));
        }
        // Empty, whitespace, and punctuation-only all mean "I have not typed a
        // search yet", not "match nothing".
        for q in ["", "   ", "***", "-- ()"] {
            let rows = db.search(q, 3, &CancelToken::never()).expect("search");
            assert_eq!(rows.len(), 3, "query {q:?}");
            assert_eq!(rows[0].ts_millis, 4, "still newest first");
        }
    }

    #[test]
    fn escaper_output_is_what_it_claims() {
        assert_eq!(escape_fts_query("foo"), Some(r#""foo""#.into()));
        assert_eq!(escape_fts_query("foo bar"), Some(r#""foo" "bar""#.into()));
        assert_eq!(escape_fts_query("foo*"), Some(r#""foo"*"#.into()));
        assert_eq!(escape_fts_query(r#"a"b"#), Some(r#""a""b""#.into()));
        assert_eq!(escape_fts_query("a OR b"), Some(r#""a" "OR" "b""#.into()));
        assert_eq!(escape_fts_query(""), None);
        assert_eq!(escape_fts_query("*** --- ()"), None);
        // A paste accident costs one bounded query, not a stalled worker.
        let huge = "term ".repeat(500);
        let escaped = escape_fts_query(&huge).expect("some terms");
        assert_eq!(escaped.matches('"').count(), MAX_QUERY_TERMS * 2);
    }

    #[test]
    fn unicode_survives_the_round_trip() {
        let dir = TempDir::new("chatdb-unicode");
        let db = db(&dir);
        line(&db, 1, "Ünicode", "gg 🎉 wp — schöne Grüße");
        assert_eq!(search(&db, "schöne").len(), 1);
        // `remove_diacritics 2` is why this works.
        assert_eq!(search(&db, "schone").len(), 1);
        let stored = &db.search("wp", 10, &CancelToken::never()).expect("search")[0];
        assert_eq!(
            stored.message, "gg 🎉 wp — schöne Grüße",
            "text stored verbatim"
        );
        assert_eq!(stored.sender, "Ünicode");
    }

    #[test]
    fn an_already_cancelled_search_does_no_work() {
        let dir = TempDir::new("chatdb-cancel");
        let db = db(&dir);
        line(&db, 1, "a", "hello");
        assert!(
            db.search("hello", 10, &CancelToken::cancelled())
                .expect("cancelled is not an error")
                .is_empty()
        );
    }

    #[test]
    fn writes_and_reads_run_concurrently() {
        use std::sync::Arc;
        // The reason for the writer-mutex-plus-reader-pool split: this
        // deadlocks or serialises badly if a single connection is shared.
        let dir = TempDir::new("chatdb-concurrent");
        let db = Arc::new(db(&dir));
        for i in 0..200 {
            line(&db, i, "a", "creeper aw man");
        }

        let writer = {
            let db = Arc::clone(&db);
            std::thread::spawn(move || {
                for i in 1000..1200 {
                    line(&db, i, "b", "still writing");
                }
            })
        };
        let reader = {
            let db = Arc::clone(&db);
            std::thread::spawn(move || {
                for _ in 0..50 {
                    db.search("creeper", 100, &CancelToken::never())
                        .expect("search during writes");
                }
            })
        };
        writer.join().expect("writer");
        reader.join().expect("reader");
        assert_eq!(db.count().unwrap(), 400);
    }

    #[test]
    fn a_newer_schema_is_refused_rather_than_misread() {
        let dir = TempDir::new("chatdb-schema");
        let path = dir.file("chat.db");
        {
            let db = ChatDb::open(&path).expect("open");
            let conn = db.writer();
            crate::sqlite::set_schema_version(&conn, SCHEMA_VERSION + 5).expect("bump");
        }
        let err = match ChatDb::open(&path) {
            Err(e) => e,
            Ok(_) => panic!("a database from a newer schema must be refused"),
        };
        assert!(err.message().contains("newer build"), "{}", err.message());
    }

    #[test]
    fn encoding_is_stable_for_the_java_reader() {
        let out = encode_search_results(&[ChatRecord {
            ts_millis: 1,
            server: "s".into(),
            sender: "a".into(),
            message: "hi".into(),
        }]);
        assert_eq!(out[4], payload::KIND_CHAT_SEARCH);
        assert_eq!(u32::from_be_bytes(out[5..9].try_into().unwrap()), 1);
    }
}

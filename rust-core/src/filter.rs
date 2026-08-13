//! Chat filter: user-defined patterns that hide or highlight incoming messages.
//!
//! ## Fail-open, the opposite of `scrub.rs`
//!
//! `filterTest` returning an error reaches Java as `-1`, and the contract is
//! that callers treat anything negative as `ALLOW`. A filter bug that hides
//! messages is a player silently missing chat and blaming the server; a filter
//! bug that shows a message they wanted hidden is a visible annoyance they can
//! report. So every bail-out in `test` returns `Allow`.
//!
//! ## Untrusted patterns
//!
//! The patterns are typed by the player into a settings screen and matched on
//! the network thread, so they are hostile input in the only sense that
//! matters here: a bad one must not be able to freeze the client.
//!
//! Three things make that true.
//!
//! 1. **The `regex` crate does not backtrack.** Match time is linear in the
//!    length of the message regardless of the pattern, so the classic
//!    catastrophic case — `(a+)+b` against a run of `a`s — is not slow here.
//!    It is *the* reason for this dependency; a hand-rolled matcher or a
//!    backtracking engine would make a pasted pattern a denial of service.
//! 2. **Compilation is bounded.** [`SIZE_LIMIT`] caps the compiled program and
//!    [`DFA_SIZE_LIMIT`] caps the lazy DFA cache, so a pattern like
//!    `(?:abcdefghij){20000}` is *rejected at add time* rather than allocating
//!    hundreds of megabytes. `nest_limit` bounds parser recursion.
//! 3. **Everything is counted.** A pattern longer than [`MAX_PATTERN_BYTES`] and
//!    a rule list longer than [`MAX_RULES`] are refused, and a message longer
//!    than [`MAX_MESSAGE_BYTES`] is allowed through unmatched rather than
//!    scanned.
//!
//! Compiling in `add_pattern` rather than at match time is what lets the
//! settings screen show the error next to the field the user just typed into.
//! Deferring it would turn a typo into a filter that silently never matches —
//! which, being a filter, is indistinguishable from one that works.
//!
//! ## Order
//!
//! **Insertion order, first match wins.** No priority by action, no "most
//! specific" heuristic — a rule list is read top to bottom exactly as it is
//! displayed. A consequence worth using: an explicit `Allow` rule placed above
//! a `Hide` rule is a whitelist, because matching it stops the scan.
//!
//! Patterns are **case-insensitive by default**, which is what someone typing
//! `noob` into a chat filter means. A pattern can opt out with the inline flag
//! `(?-i)`.

use std::sync::Mutex;

use regex::{Regex, RegexBuilder};

use crate::error::{Error, Result};

/// Compiled-program cap. The regex crate's default is 10 MB; this is far
/// smaller because these patterns are chat substrings typed by a person, and
/// the difference between "a big legitimate pattern" and "a pattern that eats
/// the heap" is well under it.
const SIZE_LIMIT: usize = 256 * 1024;

/// Lazy-DFA cache cap. Exceeding it does not fail — the engine falls back to a
/// slower but still linear strategy — so this bounds memory rather than
/// behaviour.
const DFA_SIZE_LIMIT: usize = 256 * 1024;

/// Parser recursion bound. The default (250) is generous for a pattern a human
/// typed.
const NEST_LIMIT: u32 = 64;

const MAX_PATTERN_BYTES: usize = 512;

/// A filter list is a settings screen, not a database. Past this, the cost is
/// paid per chat message on the network thread.
const MAX_RULES: usize = 256;

/// Messages longer than this are allowed through without being tested.
///
/// Vanilla caps chat at 256 characters; anything near this came from a mod or a
/// malicious server, and running 256 patterns over a megabyte of text on the
/// network thread is exactly the stall this file is supposed to prevent. Fail
/// open, per the module docs.
const MAX_MESSAGE_BYTES: usize = 16 * 1024;

/// Wire values, mirrored by `NexoNative.FILTER_*`. Append only.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FilterAction {
    Allow = 0,
    Hide = 1,
    Highlight = 2,
}

impl FilterAction {
    pub fn from_i32(v: i32) -> Result<Self> {
        match v {
            0 => Ok(Self::Allow),
            1 => Ok(Self::Hide),
            2 => Ok(Self::Highlight),
            other => Err(Error::new(format!(
                "unknown filter action {other} (expected 0=allow, 1=hide, 2=highlight)"
            ))),
        }
    }

    pub fn as_i32(self) -> i32 {
        self as i32
    }
}

#[derive(Debug, Clone)]
pub struct Rule {
    /// The source text, kept alongside the compiled form because it is what the
    /// settings screen displays and what an error message has to quote.
    pub pattern: String,
    pub action: FilterAction,
    pub matcher: Regex,
}

pub struct ChatFilter {
    rules: Mutex<Vec<Rule>>,
}

impl ChatFilter {
    pub fn new() -> Self {
        Self {
            rules: Mutex::new(Vec::new()),
        }
    }

    /// Compiles `pattern` and appends it to the rule list.
    ///
    /// Returns `Err` — which the FFI turns into `false` plus a message on
    /// `nativeLastError()` — for an empty pattern, one over
    /// [`MAX_PATTERN_BYTES`], a full rule list, or anything the regex compiler
    /// rejects, including a pattern that would exceed [`SIZE_LIMIT`].
    pub fn add_pattern(&self, pattern: &str, action: FilterAction) -> Result<()> {
        if pattern.is_empty() {
            return Err(Error::new("filter pattern is empty"));
        }
        if pattern.len() > MAX_PATTERN_BYTES {
            return Err(Error::new(format!(
                "filter pattern is {} bytes, over the {MAX_PATTERN_BYTES}-byte limit",
                pattern.len()
            )));
        }

        let matcher = RegexBuilder::new(pattern)
            .case_insensitive(true)
            .size_limit(SIZE_LIMIT)
            .dfa_size_limit(DFA_SIZE_LIMIT)
            .nest_limit(NEST_LIMIT)
            .build()
            // The compiler's own message names the offending construct and its
            // offset, which is the whole value of validating here: it can be
            // shown under the text field.
            .map_err(|e| Error::new(format!("invalid filter pattern `{pattern}`: {e}")))?;

        let mut rules = self.lock();
        if rules.len() >= MAX_RULES {
            return Err(Error::new(format!(
                "filter already has the maximum of {MAX_RULES} patterns"
            )));
        }
        rules.push(Rule {
            pattern: pattern.to_string(),
            action,
            matcher,
        });
        Ok(())
    }

    /// First matching rule in insertion order wins; `Allow` if none match.
    ///
    /// Called once per incoming chat message on the network/tick thread. The
    /// rules lock is held for the scan, which is bounded by `MAX_RULES` linear
    /// matches over at most `MAX_MESSAGE_BYTES`; `add_pattern` is a settings
    /// action and never contends with it in practice.
    ///
    /// Never returns `Err`. The signature keeps `Result` because the FFI layer
    /// is written against it and a future rule source (a file, a shared list)
    /// could fail — but as long as this is the body, the fail-open contract is
    /// upheld by construction rather than by remembering to.
    pub fn test(&self, message: &str) -> Result<FilterAction> {
        if message.len() > MAX_MESSAGE_BYTES {
            return Ok(FilterAction::Allow);
        }
        let rules = self.lock();
        for rule in rules.iter() {
            if rule.matcher.is_match(message) {
                return Ok(rule.action);
            }
        }
        Ok(FilterAction::Allow)
    }

    pub fn rule_count(&self) -> usize {
        self.lock().len()
    }

    /// Snapshot for callers that want to display the rules. Cloned rather than
    /// lent so the caller can't hold the lock across a render pass. Cloning a
    /// `Regex` is a reference-count bump, not a recompile.
    pub fn rules(&self) -> Vec<Rule> {
        self.lock().clone()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Vec<Rule>> {
        self.rules.lock().unwrap_or_else(|e| e.into_inner())
    }
}

impl Default for ChatFilter {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    #[test]
    fn action_codes_match_the_ffi_contract() {
        assert_eq!(FilterAction::Allow.as_i32(), 0);
        assert_eq!(FilterAction::Hide.as_i32(), 1);
        assert_eq!(FilterAction::Highlight.as_i32(), 2);
        assert!(FilterAction::from_i32(3).is_err());
        assert!(FilterAction::from_i32(-1).is_err());
    }

    #[test]
    fn empty_patterns_are_rejected() {
        let f = ChatFilter::new();
        assert!(f.add_pattern("", FilterAction::Hide).is_err());
        assert!(f.add_pattern("hello", FilterAction::Hide).is_ok());
        assert_eq!(f.rule_count(), 1);
    }

    #[test]
    fn matching_is_case_insensitive_unless_the_pattern_says_otherwise() {
        let f = ChatFilter::new();
        f.add_pattern("noob", FilterAction::Hide).unwrap();
        assert_eq!(f.test("what a NOOB").unwrap(), FilterAction::Hide);

        let g = ChatFilter::new();
        g.add_pattern("(?-i)noob", FilterAction::Hide).unwrap();
        assert_eq!(g.test("what a NOOB").unwrap(), FilterAction::Allow);
        assert_eq!(g.test("what a noob").unwrap(), FilterAction::Hide);
    }

    #[test]
    fn no_rules_means_allow() {
        assert_eq!(
            ChatFilter::new().test("anything").unwrap(),
            FilterAction::Allow
        );
    }

    #[test]
    fn first_matching_rule_wins_in_insertion_order() {
        let f = ChatFilter::new();
        f.add_pattern("diamond", FilterAction::Highlight).unwrap();
        f.add_pattern("diamond", FilterAction::Hide).unwrap();
        assert_eq!(f.test("found diamond").unwrap(), FilterAction::Highlight);

        // The reverse list gives the reverse answer — order is the whole rule,
        // there is no priority by action hiding behind it.
        let g = ChatFilter::new();
        g.add_pattern("diamond", FilterAction::Hide).unwrap();
        g.add_pattern("diamond", FilterAction::Highlight).unwrap();
        assert_eq!(g.test("found diamond").unwrap(), FilterAction::Hide);
    }

    #[test]
    fn an_allow_rule_placed_first_is_a_whitelist() {
        let f = ChatFilter::new();
        f.add_pattern(r"^\[Nexo\]", FilterAction::Allow).unwrap();
        f.add_pattern("spam", FilterAction::Hide).unwrap();
        assert_eq!(
            f.test("[Nexo] spam filter armed").unwrap(),
            FilterAction::Allow
        );
        assert_eq!(f.test("buy gold spam").unwrap(), FilterAction::Hide);
    }

    #[test]
    fn invalid_regexes_are_rejected_at_add_time_with_a_usable_message() {
        let f = ChatFilter::new();
        for bad in ["[", "(", "a{2,1}", r"\p{NotAScript}", "(?P<>x)", "*", "(?"] {
            let err = f
                .add_pattern(bad, FilterAction::Hide)
                .expect_err("must be rejected");
            assert!(
                err.message().contains(bad),
                "the message has to quote the pattern: {}",
                err.message()
            );
        }
        assert_eq!(f.rule_count(), 0, "nothing may be half-added");
    }

    #[test]
    fn a_pattern_that_would_compile_huge_is_refused_not_allocated() {
        let f = ChatFilter::new();
        // Well inside MAX_PATTERN_BYTES as text; enormous as a compiled program.
        // Without `size_limit` this is where a settings screen turns into an
        // out-of-memory kill.
        let err = f
            .add_pattern("(?:abcdefghij){20000}", FilterAction::Hide)
            .expect_err("size limit must refuse it");
        assert!(err.message().contains("invalid filter pattern"), "{err}");
    }

    #[test]
    fn an_overlong_pattern_is_refused() {
        let f = ChatFilter::new();
        let long = "a".repeat(MAX_PATTERN_BYTES + 1);
        assert!(f.add_pattern(&long, FilterAction::Hide).is_err());
        assert!(
            f.add_pattern(&"a".repeat(MAX_PATTERN_BYTES), FilterAction::Hide)
                .is_ok()
        );
    }

    #[test]
    fn the_rule_list_is_bounded() {
        let f = ChatFilter::new();
        for i in 0..MAX_RULES {
            f.add_pattern(&format!("pattern{i}"), FilterAction::Hide)
                .unwrap();
        }
        let err = f
            .add_pattern("one too many", FilterAction::Hide)
            .expect_err("bounded");
        assert!(err.message().contains("maximum"), "{err}");
        assert_eq!(f.rule_count(), MAX_RULES);
    }

    // The reason `regex` is the dependency. A backtracking engine takes
    // exponential time on this; anything but "instant" here means the crate
    // was swapped for one that backtracks.
    #[test]
    fn the_classic_catastrophic_pattern_is_not_catastrophic() {
        let f = ChatFilter::new();
        f.add_pattern("(a+)+$", FilterAction::Hide).unwrap();
        let hostile = format!("{}b", "a".repeat(64));

        let start = Instant::now();
        let action = f.test(&hostile).unwrap();
        let elapsed = start.elapsed();

        assert_eq!(action, FilterAction::Allow, "it does not match");
        assert!(
            elapsed < Duration::from_secs(2),
            "took {elapsed:?} — that is a backtracking engine, not `regex`"
        );
    }

    #[test]
    fn a_full_rule_list_against_a_long_message_stays_quick() {
        let f = ChatFilter::new();
        for i in 0..MAX_RULES {
            f.add_pattern(&format!("(needle{i}|haystack{i})+x*"), FilterAction::Hide)
                .unwrap();
        }
        let message = "z".repeat(MAX_MESSAGE_BYTES - 1);
        let start = Instant::now();
        assert_eq!(f.test(&message).unwrap(), FilterAction::Allow);
        assert!(
            start.elapsed() < Duration::from_secs(2),
            "worst case is per-message on the network thread"
        );
    }

    #[test]
    fn an_absurd_message_is_allowed_rather_than_scanned() {
        let f = ChatFilter::new();
        f.add_pattern("spam", FilterAction::Hide).unwrap();
        let huge = format!("spam{}", "x".repeat(MAX_MESSAGE_BYTES));
        // Fail-open: it *would* have matched, and it is still allowed. Chat
        // that silently vanishes is the failure mode this policy exists to
        // avoid.
        assert_eq!(f.test(&huge).unwrap(), FilterAction::Allow);
    }

    #[test]
    fn unicode_patterns_work_on_unicode_chat() {
        let f = ChatFilter::new();
        f.add_pattern("grüße", FilterAction::Highlight).unwrap();
        assert_eq!(f.test("schöne GRÜSSE").unwrap(), FilterAction::Allow);
        assert_eq!(f.test("schöne Grüße").unwrap(), FilterAction::Highlight);
    }

    #[test]
    fn rules_snapshot_keeps_the_source_text() {
        let f = ChatFilter::new();
        f.add_pattern(r"\bnoob\b", FilterAction::Hide).unwrap();
        let rules = f.rules();
        assert_eq!(rules.len(), 1);
        assert_eq!(rules[0].pattern, r"\bnoob\b");
        assert_eq!(rules[0].action, FilterAction::Hide);
    }

    #[test]
    fn two_threads_may_share_one_filter() {
        use std::sync::Arc;
        let f = Arc::new(ChatFilter::new());
        f.add_pattern("spam", FilterAction::Hide).unwrap();

        let adder = {
            let f = Arc::clone(&f);
            std::thread::spawn(move || {
                for i in 0..100 {
                    let _ = f.add_pattern(&format!("rule{i}"), FilterAction::Highlight);
                }
            })
        };
        let tester = {
            let f = Arc::clone(&f);
            std::thread::spawn(move || {
                for _ in 0..1000 {
                    assert_eq!(f.test("buy gold spam now").unwrap(), FilterAction::Hide);
                }
            })
        };
        adder.join().unwrap();
        tester.join().unwrap();
        assert_eq!(f.rule_count(), 101);
    }
}

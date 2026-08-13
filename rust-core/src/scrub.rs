//! Log scrubber: strips things out of a log line that shouldn't leave the
//! machine — session tokens, paths carrying the OS username, IP addresses and
//! the server the player is on — before it gets pasted into a bug report or a
//! Discord channel.
//!
//! ## Fail-closed
//!
//! `scrub` returning `Err` reaches Java as `null`, and the contract is that
//! `null` means **do not publish this line**. A scrubber that returned the
//! input unchanged on failure would be worse than no scrubber at all: the
//! caller would believe the line was cleaned and paste a session token into a
//! public issue tracker. Nothing in here "helpfully" falls back to the input.
//!
//! ## What it looks for, and why that list
//!
//! In rough order of how bad it is to leak:
//!
//! 1. **Session and access tokens.** A Minecraft access token in a `latest.log`
//!    that someone drops into a support thread is an account takeover, not an
//!    inconvenience — it authenticates as the player until it expires. Covered:
//!    JWTs (`eyJ…`, which is what Minecraft Services issues), MSA compact
//!    access tokens (`Ew…`) and refresh tokens (`M.C…_…`), the vanilla launcher's
//!    `--accessToken` argument, `Authorization:` headers, and the generic
//!    `something_token = value` shape.
//! 2. **Home directory paths**, which carry the OS account name and often a
//!    real name. Normalised to `~` rather than removed, so the rest of the path
//!    still reads.
//! 3. **IP addresses**, v4 and v6.
//! 4. **Server addresses**, which say where the player plays.
//!
//! Player UUIDs and usernames are deliberately **not** touched: both are
//! public (Mojang's own API maps one to the other for anyone who asks), and
//! removing them makes a log useless for the multiplayer bugs it gets pasted
//! for. Coordinates are also out of scope here — that is what the mod's
//! position-obscuring suite is for.
//!
//! ## Cost
//!
//! This runs once per log line, so the overwhelmingly common case — a line
//! with nothing sensitive in it — must not pay for a dozen regex scans. The
//! rules are compiled once for the whole process into a [`RegexSet`], which
//! answers "which of these fourteen patterns could match" in a single pass, and
//! only the patterns it names are then run individually. A line that matches
//! nothing costs one `RegexSet` pass and one `String` allocation for the
//! return value.
//!
//! ## Over-redaction is the safe direction
//!
//! Some rules will occasionally hit something innocent — a four-component
//! version number looks exactly like an IPv4 address, and there is no way to
//! tell from the text. When a rule is ambiguous this file redacts. A cosmetic
//! `<redacted:ip>` where `0.15.11.0` used to be costs a reader a moment of
//! confusion; the other kind of mistake costs an account.

use std::net::IpAddr;
use std::sync::LazyLock;

use regex::{Captures, Regex, RegexSet};

use crate::error::{Error, Result};

/// Longer than this and the line is not a log line — it is a serialised blob, a
/// stack of concatenated messages, or a bug. Fourteen regex passes over a
/// multi-megabyte "line" on the thread that produced it is a visible stall, and
/// the fail-closed policy makes refusing it the correct answer rather than a
/// cop-out: the caller drops the line instead of publishing something
/// unscanned.
const MAX_LINE_BYTES: usize = 256 * 1024;

const TOKEN: &str = "<redacted:token>";
const SECRET: &str = "<redacted>";
const IP: &str = "<redacted:ip>";
const SERVER: &str = "<redacted:server>";

/// Last labels that look like a TLD but are file extensions.
///
/// This exists for exactly one line, and it is the most common line in any
/// Minecraft crash report:
///
/// ```text
/// at net.minecraft.client.main.Main.main(Main.java:187)
/// ```
///
/// `Main.java:187` is `host.tld:port` as far as a regex is concerned. Without
/// this list the host:port rule would redact every frame of every stack trace,
/// which would make the scrubbed log useless for the thing it is being pasted
/// to debug.
const NOT_A_TLD: &[&str] = &[
    "java",
    "kt",
    "kts",
    "scala",
    "class",
    "jar",
    "json",
    "json5",
    "txt",
    "log",
    "gz",
    "zip",
    "png",
    "jpg",
    "jpeg",
    "nbt",
    "mca",
    "dat",
    "toml",
    "yml",
    "yaml",
    "properties",
    "cfg",
    "conf",
    "xml",
    "html",
    "css",
    "js",
    "ts",
    "rs",
    "so",
    "dll",
    "dylib",
    "md",
    "sh",
    "bat",
    "lock",
    "mcmeta",
    "ogg",
    "wav",
    "fsh",
    "vsh",
    "glsl",
    "mixins",
    "accesswidener",
    "refmap",
];

/// What a rule does with the text it matched.
enum Action {
    /// A `$`-template expanded against the captures. `${1}` and friends keep
    /// the parts worth keeping — the key name in `accessToken=…`, the `--flag`
    /// in a command line — so the reader can still see *what* was removed.
    ///
    /// Owned rather than `&'static str` so the marker constants above can be
    /// interpolated into it. The table is built once, inside a `LazyLock`, so
    /// the allocations are per-process; the alternative was writing
    /// `<redacted>` out by hand in four places and hoping nobody changed one.
    Template(String),
    /// The whole match, but only if it parses as an IP address that isn't
    /// loopback or unspecified. The regexes below are deliberately loose;
    /// `IpAddr::from_str` is the actual test, which is what keeps a log
    /// timestamp like `15:04:23` from being read as a truncated IPv6.
    IpAddress,
    /// `host:port`, unless the last label is a file extension (see
    /// [`NOT_A_TLD`]) or the port is out of range.
    HostPort,
}

/// The rule table. **Order is significant**: each rule runs over the output of
/// the previous one, and the specific rules come before the general ones so
/// that `token:eyJ…` is recognised as a JWT before the generic `token: value`
/// rule gets to it.
///
/// One invariant makes the [`RegexSet`] prescreen sound: every replacement this
/// table produces (`<redacted…>`, `~`) is inert — it contains nothing that any
/// rule in the table could match. So a rule the prescreen did not name cannot
/// become matchable because an earlier rule ran, and skipping it is safe.
type RuleSpec = (&'static str, Action);

fn rule_specs() -> Vec<RuleSpec> {
    vec![
        // --- Tokens -------------------------------------------------------
        //
        // A JWT: three (or two, for an unsigned one) base64url segments, the
        // first of which starts with `eyJ` because it is `{"` base64'd. This is
        // what Minecraft Services hands out and what ends up in
        // `(Session ID is token:…)`.
        (
            r"eyJ[A-Za-z0-9_-]{10,}(?:\.[A-Za-z0-9_-]{6,}){1,2}",
            Action::Template(TOKEN.to_string()),
        ),
        // MSA compact access token. Not a JWT — a long opaque base64 blob that
        // starts `Ew`. The 60-character floor is what keeps it from matching an
        // ordinary word.
        (
            r"\bEw[A-Za-z0-9+/=]{60,}",
            Action::Template(TOKEN.to_string()),
        ),
        // MSA refresh token, e.g. `M.C516_BAY.0.U.-Cq0…`. Worth more than the
        // access token: it mints new ones.
        (
            r"\bM\.[A-Za-z]\d*_[A-Za-z0-9]{1,8}\.[A-Za-z0-9!*+\-._~/=]{20,}",
            Action::Template(TOKEN.to_string()),
        ),
        // Legacy `token:<id>:<profile>` session string.
        (
            r"\btoken:[0-9a-fA-F]{8,}:[0-9a-fA-F]{16,}\b",
            Action::Template(TOKEN.to_string()),
        ),
        // The launcher's own command line, which shows up in crash reports and
        // in `ps` output people screenshot.
        //
        // Every "value" pattern from here down refuses to start with `<`. That
        // is what keeps a general rule from re-redacting a specific rule's
        // output: without it, `token:<redacted:token>` becomes
        // `token:<redacted>` and the log stops saying *what kind* of thing was
        // removed, which is the only thing the marker was for.
        (
            r"(?i)(--(?:accessToken|session|clientToken|xuid|password)[=\s]+)[^\s<]\S*",
            Action::Template(format!("${{1}}{SECRET}")),
        ),
        (
            r"(?i)(\bauthorization\s*[:=]\s*)(?:bearer\s+|basic\s+)?[^\s<]\S*",
            Action::Template(format!("${{1}}{SECRET}")),
        ),
        // The generic `key = value` shape. The key must literally end in
        // token/secret/password/apikey — an earlier draft accepted a bare `id`
        // and cheerfully redacted `entity id: 42`.
        (
            r#"(?i)((?:(?:access|refresh|client|session|auth|api|xbox|minecraft|mc)[_-]?)?(?:token|secret|password|passwd|apikey|api_key)\s*[:=]\s*)(?:bearer\s+)?["']?[^\s"',;)\]}<]{6,}"#,
            Action::Template(format!("${{1}}{SECRET}")),
        ),
        // --- Paths --------------------------------------------------------
        //
        // `/home/lokifisch/.minecraft/…` → `~/.minecraft/…`. The trailing class
        // excludes the separators and quoting characters a path would end at,
        // so the username is taken and nothing else.
        (
            r#"(?:/home/|/Users/|/var/home/)[^/\\\s:'"<>|*?]+"#,
            Action::Template("~".to_string()),
        ),
        // `C:\Users\Loki\…`, `C:/Users/Loki/…`, and the `C:\\Users\\Loki` form
        // that appears once a path has been through a JSON encoder.
        (
            r#"(?i)[A-Za-z]:[\\/]{1,2}Users[\\/]{1,2}[^/\\\s:'"<>|*?]+"#,
            Action::Template("~".to_string()),
        ),
        // --- Addresses ----------------------------------------------------
        (r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b", Action::IpAddress),
        // Loose IPv6 candidate: anything with at least two colons and only hex
        // between them. `IpAddr::from_str` does the real filtering — this
        // pattern happily matches `15:04:23`, which then fails to parse and is
        // left alone.
        (
            r"[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}",
            Action::IpAddress,
        ),
        // Vanilla logs `Connecting to hypixel.net, 25565`. Context-driven, so
        // it catches a bare hostname that the host:port rule below cannot.
        (
            r"(?i)(\b(?:connect(?:ing|ed)? to|logging in to|resolving)\s+)[^\s,;<]+",
            Action::Template(format!("${{1}}{SERVER}")),
        ),
        (
            r"(?i)(\bserver[ _-]?address\s*[:=]\s*)[^\s,;<]+",
            Action::Template(format!("${{1}}{SERVER}")),
        ),
        // A bare `mc.example.net:25565` anywhere in the line.
        (
            r"\b((?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+([A-Za-z][A-Za-z0-9-]{1,23})):(\d{2,5})\b",
            Action::HostPort,
        ),
    ]
}

struct Rule {
    re: Regex,
    action: Action,
}

struct RuleSet {
    /// Answers "which rules could possibly match" in one pass over the line.
    /// On a normal log line the answer is "none", and that is the whole point.
    prescreen: RegexSet,
    rules: Vec<Rule>,
}

impl RuleSet {
    fn build() -> std::result::Result<Self, regex::Error> {
        let specs = rule_specs();
        let prescreen = RegexSet::new(specs.iter().map(|(p, _)| *p))?;
        let rules = specs
            .into_iter()
            .map(|(p, action)| {
                Ok(Rule {
                    re: Regex::new(p)?,
                    action,
                })
            })
            .collect::<std::result::Result<Vec<_>, regex::Error>>()?;
        Ok(Self { prescreen, rules })
    }
}

/// Compiled once per process, not once per handle.
///
/// The obvious place for these is inside `Scrubber`, and the module skeleton
/// suggested it — but the rules are constant, so a per-handle copy would
/// recompile fourteen regexes every time a screen opened for no behavioural
/// difference at all. The handle still exists (Java needs something to own and
/// close), it just holds nothing.
///
/// `None` means the table itself failed to compile, which is a bug in this
/// file rather than anything a caller did. It is still handled instead of
/// unwrapped, because the alternative is a panic on the log-writing path — and
/// `scrub` then fails closed, so a broken rule table publishes nothing rather
/// than publishing everything.
static RULES: LazyLock<Option<RuleSet>> = LazyLock::new(|| RuleSet::build().ok());

pub struct Scrubber {
    _private: (),
}

impl Scrubber {
    pub fn new() -> Self {
        Self { _private: () }
    }

    /// Returns the line with sensitive spans replaced.
    pub fn scrub(&self, line: &str) -> Result<String> {
        let rules = RULES
            .as_ref()
            .ok_or_else(|| Error::new("log scrubber rule table failed to compile"))?;

        if line.len() > MAX_LINE_BYTES {
            return Err(Error::new(format!(
                "line is {} bytes, over the {MAX_LINE_BYTES}-byte scrubber limit; refusing to publish it unscanned",
                line.len()
            )));
        }

        let hits = rules.prescreen.matches(line);
        if !hits.matched_any() {
            return Ok(line.to_string());
        }

        let mut current = line.to_string();
        for index in hits.iter() {
            let rule = &rules.rules[index];
            current = apply(rule, &current);
        }
        Ok(current)
    }
}

fn apply(rule: &Rule, text: &str) -> String {
    match &rule.action {
        Action::Template(t) => rule.re.replace_all(text, t.as_str()).into_owned(),
        Action::IpAddress => rule
            .re
            .replace_all(text, |caps: &Captures| {
                let whole = &caps[0];
                if is_sensitive_ip(whole) {
                    IP.to_string()
                } else {
                    whole.to_string()
                }
            })
            .into_owned(),
        Action::HostPort => rule
            .re
            .replace_all(text, |caps: &Captures| {
                let whole = &caps[0];
                let host = &caps[1];
                let last_label = caps[2].to_ascii_lowercase();
                let port: u32 = caps[3].parse().unwrap_or(0);
                if NOT_A_TLD.contains(&last_label.as_str()) || !(1..=65535).contains(&port) {
                    return whole.to_string();
                }
                let _ = host;
                format!("{SERVER}:{port}")
            })
            .into_owned(),
    }
}

/// Whether an IP-shaped candidate is (a) actually an IP address and (b) one
/// worth hiding.
///
/// Loopback, `0.0.0.0` and the IPv4 broadcast address are kept: none of them
/// says anything about where the player is, and `127.0.0.1` in particular is
/// load-bearing information when the bug being reported is about the LAN
/// tunnel. Everything else — including RFC1918 addresses, which are exactly
/// what a home network leaks — is replaced.
fn is_sensitive_ip(candidate: &str) -> bool {
    match candidate.parse::<IpAddr>() {
        Ok(IpAddr::V4(v4)) => !(v4.is_loopback() || v4.is_unspecified() || v4.is_broadcast()),
        Ok(IpAddr::V6(v6)) => !(v6.is_loopback() || v6.is_unspecified()),
        Err(_) => false,
    }
}

impl Default for Scrubber {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scrub(line: &str) -> String {
        Scrubber::new().scrub(line).expect("scrub")
    }

    fn assert_clean(line: &str, must_not_contain: &[&str]) {
        let out = scrub(line);
        for needle in must_not_contain {
            assert!(
                !out.contains(needle),
                "{needle:?} survived scrubbing\n  in: {line}\n out: {out}"
            );
        }
    }

    #[test]
    fn the_rule_table_compiles() {
        // If this fails every other test in here reports a fail-closed error
        // instead of the real problem, so it is worth asserting on its own.
        assert!(RULES.is_some());
    }

    // Vanilla prints the access token into latest.log on startup. This is the
    // single most important line this file exists for.
    #[test]
    fn a_session_token_never_survives() {
        let jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IlN0ZXZlIn0.dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        let line = format!("[15:04:23] [main/INFO]: (Session ID is token:{jwt})");
        let out = scrub(&line);
        assert!(!out.contains(jwt), "{out}");
        assert!(out.contains(TOKEN), "{out}");
        // The surrounding line is still readable.
        assert!(out.contains("[main/INFO]"), "{out}");
    }

    #[test]
    fn launcher_arguments_lose_their_values_but_keep_their_names() {
        let line = "[15:04:23] [main/INFO]: args: --username Steve --uuid 069a79f4-44e9-4726-a5be-fca90e38aaf5 --accessToken eyJhbGciOiJub25lIn0.eyJ4dWlkIjoiMjUzNSJ9 --userType msa";
        let out = scrub(line);
        assert!(!out.contains("eyJ"), "{out}");
        assert!(
            out.contains("--accessToken"),
            "the flag itself is useful: {out}"
        );
        assert!(out.contains("Steve"), "usernames are public: {out}");
        assert!(out.contains("069a79f4"), "UUIDs are public: {out}");
    }

    #[test]
    fn msa_tokens_are_recognised_even_though_they_are_not_jwts() {
        let access = format!(
            "EwAoA+pvBAAUKods{}",
            "8j5eqZLBM1BsNn0YR7ehsF/qbQAA".repeat(3)
        );
        let refresh = "M.C516_BAY.0.U.-Cq0aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789abcdef";
        assert_clean(&format!("token = {access}"), &[&access]);
        assert_clean(&format!("refresh_token: {refresh}"), &[refresh]);
    }

    #[test]
    fn generic_secret_assignments_are_masked() {
        for line in [
            "accessToken=abcdefghijklmnop",
            "access_token: abcdefghijklmnop",
            "clientSecret = 'abcdefghijklmnop'",
            "password=hunter2hunter2",
            "Authorization: Bearer abcdefghijklmnop",
            "minecraft_token   =   abcdefghijklmnop",
        ] {
            let out = scrub(line);
            assert!(
                !out.contains("abcdefghijklmnop") && !out.contains("hunter2hunter2"),
                "{line}  ->  {out}"
            );
            assert!(out.contains(SECRET), "{line}  ->  {out}");
        }
    }

    #[test]
    fn an_entity_id_is_not_a_secret() {
        // The `key = value` rule used to accept a bare `id`. It does not now,
        // and this is the line that made that obvious.
        let out = scrub("[Render thread/DEBUG]: entity id: 1234567 chunk id: 8901234");
        assert!(out.contains("1234567"), "{out}");
        assert!(out.contains("8901234"), "{out}");
    }

    #[test]
    fn home_paths_become_tilde_on_both_platforms() {
        assert_eq!(
            scrub("[main/INFO]: Loading /home/lokifisch/.minecraft/config/nexomod.json"),
            "[main/INFO]: Loading ~/.minecraft/config/nexomod.json"
        );
        assert_eq!(
            scrub(r"Reading C:\Users\Loki Fisch\AppData\Roaming\.minecraft\logs\latest.log"),
            r"Reading ~ Fisch\AppData\Roaming\.minecraft\logs\latest.log"
        );
        assert_eq!(
            scrub(r#"{\"dir\":\"C:\\Users\\Loki\\.minecraft\"}"#),
            r#"{\"dir\":\"~\\.minecraft\"}"#
        );
        assert_eq!(
            scrub("open(/Users/loki/Library/Application Support/minecraft)"),
            "open(~/Library/Application Support/minecraft)"
        );
    }

    #[test]
    fn ip_addresses_go_but_loopback_stays() {
        let out = scrub("[Netty Client IO/INFO]: Connected to 192.168.1.9:25565 via 10.0.0.4");
        assert!(!out.contains("192.168.1.9"), "{out}");
        assert!(!out.contains("10.0.0.4"), "{out}");
        assert!(scrub("bound to 127.0.0.1:25565").contains("127.0.0.1"));
        assert!(scrub("listening on 0.0.0.0").contains("0.0.0.0"));
    }

    #[test]
    fn ipv6_goes_but_log_timestamps_do_not() {
        let out = scrub("[15:04:23] [main/INFO]: peer fe80::1c2d:3e4f:5a6b:7c8d port 25565");
        assert!(out.contains("[15:04:23]"), "timestamp mangled: {out}");
        assert!(!out.contains("fe80::"), "{out}");
        let full = scrub("addr 2001:0db8:0000:0000:0000:ff00:0042:8329 ok");
        assert!(!full.contains("2001:0db8"), "{full}");
        // `::1` is loopback and stays, like its v4 counterpart.
        assert!(scrub("bound ::1 done").contains("::1"));
    }

    // If this ever fails, a scrubbed crash report stops being a crash report.
    #[test]
    fn stack_traces_survive_intact() {
        let trace = "\tat net.minecraft.client.main.Main.main(Main.java:187) ~[minecraft.jar:?]";
        assert_eq!(scrub(trace), trace);
        let mixin = "\tat net.fabricmc.loader.impl.launch.knot.KnotClient.main(KnotClient.java:23)";
        assert_eq!(scrub(mixin), mixin);
        let plain = "[15:04:23] [Render thread/INFO]: Loaded 7 recipes for minecraft:crafting";
        assert_eq!(scrub(plain), plain);
    }

    #[test]
    fn server_addresses_are_masked_in_both_forms() {
        let ctx = scrub("[main/INFO]: Connecting to mc.example.net, 25565");
        assert!(!ctx.contains("mc.example.net"), "{ctx}");
        let bare = scrub("[Netty/INFO]: handshake with play.example.net:25565 ok");
        assert!(!bare.contains("play.example.net"), "{bare}");
        assert!(bare.contains("25565"), "the port is not the secret: {bare}");
    }

    #[test]
    fn an_ordinary_line_is_returned_unchanged() {
        // The prescreen path: no rule matched, so nothing ran.
        for line in [
            "[15:04:23] [Render thread/INFO]: Starting Minecraft 26.1.2",
            "[15:04:24] [main/WARN]: Mod nexomod uses deprecated API",
            "",
            "OpenAL initialized on device Built-in Audio Analog Stereo",
        ] {
            assert_eq!(scrub(line), line);
        }
    }

    #[test]
    fn an_oversized_line_fails_closed() {
        let huge = "x".repeat(MAX_LINE_BYTES + 1);
        let err = Scrubber::new()
            .scrub(&huge)
            .expect_err("must not be published unscanned");
        assert!(err.message().contains("refusing to publish"), "{err}");
        // ...and the boundary itself is fine.
        assert!(Scrubber::new().scrub(&"x".repeat(MAX_LINE_BYTES)).is_ok());
    }

    #[test]
    fn several_rules_can_fire_on_one_line() {
        let line = "[15:04:23] [main/INFO]: /home/loki/.minecraft launched --accessToken eyJhbGciOiJub25lIn0.eyJhIjoxfQ against 192.168.1.9:25565";
        assert_clean(line, &["/home/loki", "eyJhbGciOiJub25lIn0", "192.168.1.9"]);
        let out = scrub(line);
        assert!(
            out.contains('~') && out.contains(TOKEN) && out.contains(IP),
            "{out}"
        );
    }
}

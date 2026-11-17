//! Talks to GitHub: ref advertisement and push over the Git smart-HTTP protocol,
//! plus two small REST calls to read the current tree of the target branch.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, Cursor, Read};
use std::path::Path;
use std::time::Duration;

use base64::Engine as _;
use serde::Deserialize;

use crate::error::{Error, Result};
use crate::pktline::{self, Pkt};
use crate::progress::{check_cancel, Rep};
use crate::types::EngineRequest;

const UA: &str = concat!("git/2.45.0 gitpush/", env!("CARGO_PKG_VERSION"));

pub struct Advert {
    pub refs: HashMap<String, String>,
}

impl Advert {
    pub fn has_heads(&self) -> bool {
        self.refs.keys().any(|k| k.starts_with("refs/heads/"))
    }
}

#[derive(Deserialize)]
pub struct RemoteEntry {
    pub path: String,
    pub mode: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub sha: String,
    #[serde(default)]
    pub size: Option<u64>,
}

pub struct ReceiveResult {
    pub ok: bool,
    pub reason: String,
    pub messages: Vec<String>,
}

pub struct Remote {
    agent: ureq::Agent,
    git_url: String,
    api_url: String,
    auth_basic: String,
    auth_bearer: String,
}

fn valid_slug(s: &str) -> bool {
    !s.is_empty()
        && s != "."
        && s != ".."
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.')
}

fn snippet(s: &str) -> String {
    let t: String = s.chars().take(300).collect();
    t.trim().to_string()
}

fn map_err(e: ureq::Error, what: &str) -> Error {
    match e {
        ureq::Error::Status(code, resp) => {
            let body = resp.into_string().unwrap_or_default();
            let lower = body.to_lowercase();
            match code {
                401 => Error::new(
                    "AUTH_FAILED",
                    "GitHub rejected the credentials. Sign in again or check your token.",
                ),
                403 | 429 if lower.contains("rate limit") || code == 429 => Error::new(
                    "RATE_LIMITED",
                    "GitHub rate limit reached. Wait a few minutes and try again.",
                ),
                403 => Error::new(
                    "FORBIDDEN",
                    "Access denied. The token needs write access to this repository (scopes: repo, workflow).",
                ),
                404 => Error::new(
                    "NOT_FOUND",
                    "Repository not found, or the token cannot access it.",
                ),
                _ => Error::new(
                    "HTTP",
                    format!("Could not {}: HTTP {} {}", what, code, snippet(&body)),
                ),
            }
        }
        ureq::Error::Transport(t) => {
            if check_cancel().is_err() {
                Error::cancelled()
            } else {
                Error::new("NETWORK", format!("Could not {}: {}", what, t))
            }
        }
    }
}

impl Remote {
    pub fn new(req: &EngineRequest) -> Result<Self> {
        if !valid_slug(&req.owner) {
            return Err(Error::new("INVALID_REPO", "Invalid repository owner"));
        }
        if !valid_slug(&req.repo) {
            return Err(Error::new("INVALID_REPO", "Invalid repository name"));
        }
        if req.token.is_empty() {
            return Err(Error::new("AUTH_REQUIRED", "You are not signed in"));
        }
        let git_base = req
            .git_base
            .clone()
            .unwrap_or_else(|| "https://github.com".to_string());
        let api_base = req
            .api_base
            .clone()
            .unwrap_or_else(|| "https://api.github.com".to_string());
        let creds = base64::engine::general_purpose::STANDARD
            .encode(format!("x-access-token:{}", req.token));
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(20))
            .timeout_read(Duration::from_secs(300))
            .timeout_write(Duration::from_secs(600))
            .user_agent(UA)
            .build();
        Ok(Remote {
            agent,
            git_url: format!(
                "{}/{}/{}.git",
                git_base.trim_end_matches('/'),
                req.owner,
                req.repo
            ),
            api_url: format!(
                "{}/repos/{}/{}",
                api_base.trim_end_matches('/'),
                req.owner,
                req.repo
            ),
            auth_basic: format!("Basic {}", creds),
            auth_bearer: format!("Bearer {}", req.token),
        })
    }

    pub fn advertise(&self) -> Result<Advert> {
        let url = format!("{}/info/refs?service=git-receive-pack", self.git_url);
        let resp = self
            .agent
            .get(&url)
            .set("Authorization", &self.auth_basic)
            .set("Accept", "*/*")
            .call()
            .map_err(|e| map_err(e, "contact GitHub"))?;
        let mut body = Vec::new();
        resp.into_reader()
            .take(64 * 1024 * 1024)
            .read_to_end(&mut body)?;
        parse_advert(&body)
    }

    fn rest_get(&self, path: &str) -> Result<ureq::Response> {
        let url = format!("{}{}", self.api_url, path);
        self.agent
            .get(&url)
            .set("Authorization", &self.auth_bearer)
            .set("Accept", "application/vnd.github+json")
            .set("X-GitHub-Api-Version", "2022-11-28")
            .call()
            .map_err(|e| map_err(e, "read the repository"))
    }

    /// Returns the tree id of a commit.
    pub fn commit_tree(&self, commit_sha: &str) -> Result<String> {
        #[derive(Deserialize)]
        struct T {
            sha: String,
        }
        #[derive(Deserialize)]
        struct C {
            tree: T,
        }
        let c: C = self
            .rest_get(&format!("/git/commits/{}", commit_sha))?
            .into_json()?;
        Ok(c.tree.sha)
    }

    pub fn tree_recursive(&self, tree_sha: &str) -> Result<Vec<RemoteEntry>> {
        #[derive(Deserialize)]
        struct R {
            tree: Vec<RemoteEntry>,
            #[serde(default)]
            truncated: bool,
        }
        let r: R = self
            .rest_get(&format!("/git/trees/{}?recursive=1", tree_sha))?
            .into_json()?;
        if r.truncated {
            return Err(Error::new(
                "REMOTE_TREE_TRUNCATED",
                "This branch has too many files to compare in one request (over 100,000 entries). \
                 Push to a new branch or into a smaller repository.",
            ));
        }
        Ok(r.tree)
    }

    /// Sends `cmd` (pkt-line command + flush) followed by the pack file.
    pub fn receive_pack(
        &self,
        cmd: &[u8],
        pack: &Path,
        pack_len: u64,
        rep: &Rep,
    ) -> Result<ReceiveResult> {
        let total = cmd.len() as u64 + pack_len;
        let file = File::open(pack)?;
        let body = ProgressRead {
            inner: Cursor::new(cmd.to_vec()).chain(file),
            sent: 0,
            total,
            rep: rep.clone(),
        };
        let url = format!("{}/git-receive-pack", self.git_url);
        let resp = self
            .agent
            .post(&url)
            .set("Authorization", &self.auth_basic)
            .set("Content-Type", "application/x-git-receive-pack-request")
            .set("Accept", "application/x-git-receive-pack-result")
            .set("Content-Length", &total.to_string())
            .send(body)
            .map_err(|e| map_err(e, "upload to GitHub"))?;
        let mut out = Vec::new();
        resp.into_reader()
            .take(16 * 1024 * 1024)
            .read_to_end(&mut out)?;
        rep.report("upload", total, total, "", true);
        parse_receive(&out)
    }
}

struct ProgressRead<R: Read> {
    inner: R,
    sent: u64,
    total: u64,
    rep: Rep,
}

impl<R: Read> Read for ProgressRead<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if check_cancel().is_err() {
            return Err(io::Error::new(io::ErrorKind::Other, "cancelled"));
        }
        let n = self.inner.read(buf)?;
        self.sent += n as u64;
        self.rep.report("upload", self.sent, self.total, "", false);
        Ok(n)
    }
}

fn parse_advert(body: &[u8]) -> Result<Advert> {
    let pkts = pktline::parse(body)?;
    let mut refs = HashMap::new();
    let mut saw_service = false;
    for p in pkts {
        if let Pkt::Data(d) = p {
            let text = String::from_utf8_lossy(d);
            let line = text.trim_end_matches('\n');
            if line.starts_with("# service=") {
                saw_service = true;
                continue;
            }
            if let Some(rest) = line.strip_prefix("ERR ") {
                return Err(Error::new("REMOTE_ERROR", rest.to_string()));
            }
            let main = line.split('\0').next().unwrap_or("");
            let mut it = main.splitn(2, ' ');
            let (sha, name) = match (it.next(), it.next()) {
                (Some(s), Some(n)) => (s, n),
                _ => continue,
            };
            if name == "capabilities^{}" || name.ends_with("^{}") {
                continue;
            }
            if sha.len() == 40 {
                refs.insert(name.to_string(), sha.to_string());
            }
        }
    }
    if !saw_service {
        return Err(Error::new(
            "PROTOCOL",
            "Unexpected response from GitHub (not a Git service)",
        ));
    }
    Ok(Advert { refs })
}

fn push_messages(dst: &mut Vec<String>, raw: &[u8]) {
    let s = String::from_utf8_lossy(raw);
    for part in s.split(|c| c == '\n' || c == '\r') {
        let t = part.trim();
        if t.is_empty() || t.contains("% (") {
            continue;
        }
        dst.push(t.to_string());
    }
}

fn line_of(d: &[u8]) -> String {
    String::from_utf8_lossy(d).trim_end_matches('\n').to_string()
}

fn parse_receive(body: &[u8]) -> Result<ReceiveResult> {
    let outer = pktline::parse(body)?;
    let mut inner: Vec<u8> = Vec::new();
    let mut messages: Vec<String> = Vec::new();
    let mut direct: Vec<String> = Vec::new();
    let mut fatal = String::new();
    let mut sideband = false;

    for p in &outer {
        if let Pkt::Data(d) = p {
            if d.is_empty() {
                continue;
            }
            match d[0] {
                1 => {
                    sideband = true;
                    inner.extend_from_slice(&d[1..]);
                }
                2 => {
                    sideband = true;
                    push_messages(&mut messages, &d[1..]);
                }
                3 => {
                    sideband = true;
                    fatal.push_str(&String::from_utf8_lossy(&d[1..]));
                }
                _ => direct.push(line_of(d)),
            }
        }
    }

    let lines: Vec<String> = if sideband {
        let mut v = Vec::new();
        for p in pktline::parse(&inner)? {
            if let Pkt::Data(d) = p {
                v.push(line_of(d));
            }
        }
        v
    } else {
        direct
    };

    let mut unpack_ok = false;
    let mut ref_ok = false;
    let mut reason = String::new();
    for l in &lines {
        if let Some(r) = l.strip_prefix("unpack ") {
            if r == "ok" {
                unpack_ok = true;
            } else {
                reason = format!("GitHub could not unpack the data: {}", r);
            }
        } else if l.starts_with("ok ") {
            ref_ok = true;
        } else if let Some(r) = l.strip_prefix("ng ") {
            reason = r.splitn(2, ' ').nth(1).unwrap_or(r).to_string();
        }
    }
    if reason.is_empty() && !fatal.trim().is_empty() {
        reason = fatal.trim().to_string();
    }
    let ok = unpack_ok && ref_ok;
    if !ok {
        if reason.is_empty() {
            reason = "GitHub did not accept the push".to_string();
        }
        let extra: Vec<&String> = messages.iter().rev().take(6).collect::<Vec<_>>();
        if !extra.is_empty() {
            let joined: Vec<String> = extra.into_iter().rev().cloned().collect();
            reason = format!("{}\n{}", reason, joined.join("\n"));
        }
    }
    Ok(ReceiveResult {
        ok,
        reason,
        messages,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_advertisement() {
        let mut body = pktline::encode(b"# service=git-receive-pack\n");
        body.extend_from_slice(b"0000");
        let sha = "ce013625030ba8dba906f756967f9e9ca394464a";
        body.extend_from_slice(&pktline::encode(
            format!("{} refs/heads/main\0report-status delete-refs\n", sha).as_bytes(),
        ));
        body.extend_from_slice(b"0000");
        let adv = parse_advert(&body).unwrap();
        assert_eq!(adv.refs.get("refs/heads/main").map(|s| s.as_str()), Some(sha));
        assert!(adv.has_heads());
    }

    #[test]
    fn empty_repo_advertisement() {
        let mut body = pktline::encode(b"# service=git-receive-pack\n");
        body.extend_from_slice(b"0000");
        body.extend_from_slice(&pktline::encode(
            format!("{} capabilities^{{}}\0report-status\n", crate::gitobj::ZERO_HEX).as_bytes(),
        ));
        body.extend_from_slice(b"0000");
        let adv = parse_advert(&body).unwrap();
        assert!(!adv.has_heads());
    }

    #[test]
    fn parses_sideband_success() {
        let mut inner = pktline::encode(b"unpack ok\n");
        inner.extend_from_slice(&pktline::encode(b"ok refs/heads/main\n"));
        inner.extend_from_slice(b"0000");
        let mut chan1 = vec![1u8];
        chan1.extend_from_slice(&inner);
        let mut body = pktline::encode(&chan1);
        body.extend_from_slice(b"0000");
        let r = parse_receive(&body).unwrap();
        assert!(r.ok);
    }

    #[test]
    fn parses_rejection() {
        let mut inner = pktline::encode(b"unpack ok\n");
        inner.extend_from_slice(&pktline::encode(b"ng refs/heads/main protected branch\n"));
        inner.extend_from_slice(b"0000");
        let mut chan1 = vec![1u8];
        chan1.extend_from_slice(&inner);
        let mut body = pktline::encode(&chan1);
        body.extend_from_slice(b"0000");
        let r = parse_receive(&body).unwrap();
        assert!(!r.ok);
        assert!(r.reason.contains("protected branch"));
    }
}

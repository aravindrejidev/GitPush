//! Orchestrates a push: scan -> read remote -> diff -> build trees -> pack -> send.

use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::{Error, Result};
use crate::gitobj::{self, FileEntry, Oid};
use crate::pack::{self, BlobSrc};
use crate::pktline;
use crate::progress::{check_cancel, Rep};
use crate::remote::Remote;
use crate::scan::{self, LocalFile};
use crate::types::*;

const LIST_CAP: usize = 2000;
const SKIP_CAP: usize = 300;

struct Prepared {
    local: Vec<LocalFile>,
    skipped: Vec<Skipped>,
    parent: Option<Oid>,
    branch_exists: bool,
    repo_empty: bool,
    new_root: Oid,
    new_trees: Vec<(Oid, Vec<u8>)>,
    new_blobs: Vec<usize>,
    added: Vec<(usize, String)>,
    modified: Vec<(usize, String)>,
    deleted: Vec<(String, u64)>,
    unchanged: usize,
}

impl Prepared {
    fn changed(&self) -> bool {
        !(self.added.is_empty() && self.modified.is_empty() && self.deleted.is_empty())
    }
}

fn make_pool(threads: usize) -> Result<rayon::ThreadPool> {
    rayon::ThreadPoolBuilder::new()
        .num_threads(threads.clamp(1, 16))
        .build()
        .map_err(|e| Error::new("INTERNAL", e.to_string()))
}

fn normalize_subdir(s: &str) -> Result<String> {
    let mut parts: Vec<&str> = Vec::new();
    for c in s.split('/') {
        let c = c.trim();
        if c.is_empty() || c == "." {
            continue;
        }
        if c == ".." || c.eq_ignore_ascii_case(".git") || c.contains('\0') || c.contains('\\') {
            return Err(Error::new(
                "INVALID_PATH",
                format!("Invalid folder name in the target path: {}", c),
            ));
        }
        parts.push(c);
    }
    Ok(parts.join("/"))
}

fn validate_branch(b: &str) -> Result<()> {
    let bad = b.is_empty()
        || b == "@"
        || b.starts_with('/')
        || b.ends_with('/')
        || b.ends_with('.')
        || b.ends_with(".lock")
        || b.contains("..")
        || b.contains("//")
        || b.contains("@{")
        || b.chars()
            .any(|c| c.is_whitespace() || c.is_control() || "~^:?*[\\".contains(c));
    if bad {
        Err(Error::new(
            "INVALID_BRANCH",
            format!("'{}' is not a valid branch name", b),
        ))
    } else {
        Ok(())
    }
}

fn join(prefix: &str, rel: &str) -> String {
    if prefix.is_empty() {
        rel.to_string()
    } else {
        format!("{}/{}", prefix, rel)
    }
}

fn mode_for(exec: bool) -> String {
    String::from(if exec { "100755" } else { "100644" })
}

fn parse_oid(s: &str) -> Result<Oid> {
    gitobj::from_hex(s).ok_or_else(|| Error::new("PROTOCOL", format!("Invalid object id: {}", s)))
}

/// Inserts a file, removing anything that would collide with it
/// (a file where a directory is needed, or a directory where a file goes).
fn insert_with_conflicts(map: &mut BTreeMap<String, FileEntry>, path: String, entry: FileEntry) {
    let mut acc = String::new();
    let comps: Vec<&str> = path.split('/').collect();
    for c in &comps[..comps.len().saturating_sub(1)] {
        if !acc.is_empty() {
            acc.push('/');
        }
        acc.push_str(c);
        map.remove(&acc);
    }
    let lo = format!("{}/", path);
    let hi = format!("{}0", path); // '0' is the byte after '/'
    let doomed: Vec<String> = map.range(lo..hi).map(|(k, _)| k.clone()).collect();
    for k in doomed {
        map.remove(&k);
    }
    map.insert(path, entry);
}

fn prepare(req: &EngineRequest, remote: &Remote, pool: &rayon::ThreadPool, rep: &Rep) -> Result<Prepared> {
    validate_branch(&req.branch)?;
    let subdir = normalize_subdir(&req.target_subdir)?;

    let (local, skipped) = scan::scan(req, pool, rep)?;
    check_cancel()?;

    rep.report("remote", 0, 0, "Contacting GitHub", true);
    let adv = remote.advertise()?;
    check_cancel()?;

    let mut parent: Option<Oid> = None;
    let mut branch_exists = false;
    let mut repo_empty = false;
    let branch_ref = format!("refs/heads/{}", req.branch);
    if let Some(sha) = adv.refs.get(&branch_ref) {
        parent = Some(parse_oid(sha)?);
        branch_exists = true;
    } else {
        let base = req.base_branch.as_deref().filter(|b| !b.is_empty());
        match base.and_then(|b| adv.refs.get(&format!("refs/heads/{}", b))) {
            Some(sha) => parent = Some(parse_oid(sha)?),
            None => {
                if adv.has_heads() {
                    let which = base.unwrap_or(req.branch.as_str());
                    return Err(Error::new(
                        "BRANCH_NOT_FOUND",
                        format!("Branch '{}' was not found in the repository", which),
                    ));
                }
                repo_empty = true;
            }
        }
    }

    let mut remote_files: BTreeMap<String, FileEntry> = BTreeMap::new();
    let mut known_trees: HashSet<Oid> = HashSet::new();
    let mut known_blobs: HashSet<Oid> = HashSet::new();
    if let Some(p) = parent {
        rep.report("remote", 0, 0, "Reading the current tree", true);
        let tree_sha = remote.commit_tree(&gitobj::to_hex(&p))?;
        known_trees.insert(parse_oid(&tree_sha)?);
        let entries = remote.tree_recursive(&tree_sha)?;
        for e in entries {
            let oid = match gitobj::from_hex(&e.sha) {
                Some(o) => o,
                None => continue,
            };
            match e.kind.as_str() {
                "tree" => {
                    known_trees.insert(oid);
                }
                "blob" => {
                    known_blobs.insert(oid);
                    remote_files.insert(
                        e.path,
                        FileEntry {
                            mode: e.mode,
                            oid,
                            size: e.size.unwrap_or(0),
                        },
                    );
                }
                "commit" => {
                    remote_files.insert(
                        e.path,
                        FileEntry {
                            mode: e.mode,
                            oid,
                            size: 0,
                        },
                    );
                }
                _ => {}
            }
        }
    }
    check_cancel()?;

    rep.report("diff", 0, 0, "Comparing with the repository", true);
    let mut final_files = remote_files.clone();
    let mut added: Vec<(usize, String)> = Vec::new();
    let mut modified: Vec<(usize, String)> = Vec::new();
    let mut unchanged = 0usize;
    let mut keep: HashSet<String> = HashSet::with_capacity(local.len() + skipped.len());

    for (i, lf) in local.iter().enumerate() {
        let full = join(&subdir, &lf.rel);
        keep.insert(full.clone());
        match remote_files.get(&full) {
            Some(r) if r.oid == lf.oid && r.mode != "160000" => {
                unchanged += 1;
            }
            Some(r) => {
                // Staged copies lose Unix mode bits, so existing files keep their remote mode.
                let mode = if r.mode == "100644" || r.mode == "100755" {
                    r.mode.clone()
                } else {
                    mode_for(lf.exec)
                };
                modified.push((i, full.clone()));
                final_files.insert(
                    full,
                    FileEntry {
                        mode,
                        oid: lf.oid,
                        size: lf.size,
                    },
                );
            }
            None => {
                added.push((i, full.clone()));
                insert_with_conflicts(
                    &mut final_files,
                    full,
                    FileEntry {
                        mode: mode_for(lf.exec),
                        oid: lf.oid,
                        size: lf.size,
                    },
                );
            }
        }
    }

    // Files we could not read or that were too large must never be treated as "missing".
    for s in &skipped {
        if s.reason == "too_large" || s.reason.starts_with("unreadable") {
            keep.insert(join(&subdir, &s.path));
        }
    }

    let mut deleted: Vec<(String, u64)> = Vec::new();
    if req.delete_missing {
        let prefix = if subdir.is_empty() {
            String::new()
        } else {
            format!("{}/", subdir)
        };
        for (path, e) in remote_files.range(prefix.clone()..) {
            if !prefix.is_empty() && !path.starts_with(&prefix) {
                break;
            }
            if !keep.contains(path) {
                deleted.push((path.clone(), e.size));
            }
        }
        for (p, _) in &deleted {
            final_files.remove(p);
        }
    }

    let built = gitobj::build_trees(&final_files);
    let new_trees: Vec<(Oid, Vec<u8>)> = built
        .trees
        .into_iter()
        .filter(|(oid, _)| !known_trees.contains(oid))
        .collect();

    let mut seen: HashSet<Oid> = HashSet::new();
    let mut new_blobs: Vec<usize> = Vec::new();
    for (i, _) in added.iter().chain(modified.iter()) {
        let oid = local[*i].oid;
        if !known_blobs.contains(&oid) && seen.insert(oid) {
            new_blobs.push(*i);
        }
    }

    Ok(Prepared {
        local,
        skipped,
        parent,
        branch_exists,
        repo_empty,
        new_root: built.root,
        new_trees,
        new_blobs,
        added,
        modified,
        deleted,
        unchanged,
    })
}

fn capped_changes(items: &[(usize, String)], local: &[LocalFile]) -> Vec<Change> {
    let mut v: Vec<Change> = items
        .iter()
        .map(|(i, p)| Change {
            path: p.clone(),
            size: local[*i].size,
        })
        .collect();
    v.sort_by(|a, b| a.path.cmp(&b.path));
    v.truncate(LIST_CAP);
    v
}

pub fn plan(req: &EngineRequest, rep: Rep) -> Result<PlanOut> {
    let pool = make_pool(req.threads)?;
    let remote = Remote::new(req)?;
    let p = prepare(req, &remote, &pool, &rep)?;

    let mut deleted: Vec<Change> = p
        .deleted
        .iter()
        .map(|(path, size)| Change {
            path: path.clone(),
            size: *size,
        })
        .collect();
    deleted.sort_by(|a, b| a.path.cmp(&b.path));
    deleted.truncate(LIST_CAP);

    let upload_bytes: u64 = p.new_blobs.iter().map(|&i| p.local[i].size).sum();
    let local_bytes: u64 = p.local.iter().map(|f| f.size).sum();
    let mut skipped = p.skipped.clone();
    let skipped_count = skipped.len();
    skipped.truncate(SKIP_CAP);

    Ok(PlanOut {
        added: capped_changes(&p.added, &p.local),
        modified: capped_changes(&p.modified, &p.local),
        deleted,
        added_count: p.added.len(),
        modified_count: p.modified.len(),
        deleted_count: p.deleted.len(),
        unchanged_count: p.unchanged,
        skipped,
        skipped_count,
        upload_bytes,
        local_files: p.local.len(),
        local_bytes,
        parent_sha: p.parent.as_ref().map(gitobj::to_hex),
        branch_exists: p.branch_exists,
        repo_empty: p.repo_empty,
    })
}

struct TempFile(PathBuf);

impl Drop for TempFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

fn clean_ident(s: &str, fallback: &str) -> String {
    let t: String = s
        .chars()
        .filter(|c| !matches!(c, '<' | '>' | '\n' | '\r' | '\0'))
        .collect();
    let t = t.trim().to_string();
    if t.is_empty() {
        fallback.to_string()
    } else {
        t
    }
}

pub fn push(req: &EngineRequest, rep: Rep) -> Result<PushOut> {
    let pool = make_pool(req.threads)?;
    let remote = Remote::new(req)?;
    let p = prepare(req, &remote, &pool, &rep)?;

    if !p.changed() {
        return Ok(PushOut {
            status: "up_to_date".into(),
            commit_sha: None,
            branch: req.branch.clone(),
            added: 0,
            modified: 0,
            deleted: 0,
            bytes_sent: 0,
            remote_messages: vec![],
        });
    }

    let ts = if req.timestamp > 0 {
        req.timestamp
    } else {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0)
    };
    let tz = if req.tz_offset.len() == 5 { req.tz_offset.as_str() } else { "+0000" };
    let name = clean_ident(&req.author_name, "GitPush");
    let email = clean_ident(&req.author_email, "gitpush@users.noreply.github.com");
    let sig = format!("{} <{}> {} {}", name, email, ts, tz);
    let message = if req.message.trim().is_empty() {
        "Update via GitPush".to_string()
    } else {
        req.message.clone()
    };
    let commit_bytes = gitobj::encode_commit(&p.new_root, p.parent.as_ref(), &sig, &sig, &message);
    let commit_oid = gitobj::hash_object("commit", &commit_bytes);

    let temp_dir = if req.temp_dir.is_empty() {
        std::env::temp_dir()
    } else {
        PathBuf::from(&req.temp_dir)
    };
    fs::create_dir_all(&temp_dir)?;
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    let pack_path = temp_dir.join(format!("push-{}-{}.pack", std::process::id(), nanos));
    let _cleanup = TempFile(pack_path.clone());

    let blobs: Vec<BlobSrc> = p
        .new_blobs
        .iter()
        .map(|&i| BlobSrc {
            path: p.local[i].abs.as_path(),
            size: p.local[i].size,
            oid: p.local[i].oid,
        })
        .collect();
    let pack_len = pack::write_pack(&pack_path, &commit_bytes, &p.new_trees, &blobs, &pool, &rep)?;
    check_cancel()?;

    let old_hex = match (&p.parent, p.branch_exists) {
        (Some(o), true) => gitobj::to_hex(o),
        _ => gitobj::ZERO_HEX.to_string(),
    };
    let line = format!(
        "{} {} refs/heads/{}\0 report-status side-band-64k agent=gitpush/{}",
        old_hex,
        gitobj::to_hex(&commit_oid),
        req.branch,
        env!("CARGO_PKG_VERSION")
    );
    let mut cmd = pktline::encode(line.as_bytes());
    cmd.extend_from_slice(b"0000");

    rep.report("upload", 0, pack_len, "", true);
    let res = remote.receive_pack(&cmd, &pack_path, pack_len, &rep)?;
    if !res.ok {
        return Err(Error::new("PUSH_REJECTED", res.reason));
    }

    Ok(PushOut {
        status: "pushed".into(),
        commit_sha: Some(gitobj::to_hex(&commit_oid)),
        branch: req.branch.clone(),
        added: p.added.len(),
        modified: p.modified.len(),
        deleted: p.deleted.len(),
        bytes_sent: pack_len,
        remote_messages: res.messages,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(b: &[u8]) -> FileEntry {
        FileEntry {
            mode: "100644".into(),
            oid: gitobj::hash_object("blob", b),
            size: b.len() as u64,
        }
    }

    #[test]
    fn subdir_normalization() {
        assert_eq!(normalize_subdir("/a//b/./c/").unwrap(), "a/b/c");
        assert!(normalize_subdir("a/../b").is_err());
        assert!(normalize_subdir(".git/x").is_err());
        assert_eq!(normalize_subdir("").unwrap(), "");
    }

    #[test]
    fn branch_validation() {
        assert!(validate_branch("feature/new-ui").is_ok());
        assert!(validate_branch("bad name").is_err());
        assert!(validate_branch("a..b").is_err());
        assert!(validate_branch("x.lock").is_err());
    }

    #[test]
    fn file_replaces_directory() {
        let mut m = BTreeMap::new();
        m.insert("a/b.txt".to_string(), entry(b"1"));
        m.insert("a/c/d.txt".to_string(), entry(b"2"));
        m.insert("z.txt".to_string(), entry(b"3"));
        insert_with_conflicts(&mut m, "a".to_string(), entry(b"4"));
        assert!(m.contains_key("a"));
        assert!(!m.contains_key("a/b.txt"));
        assert!(!m.contains_key("a/c/d.txt"));
        assert!(m.contains_key("z.txt"));
    }

    #[test]
    fn directory_replaces_file() {
        let mut m = BTreeMap::new();
        m.insert("a".to_string(), entry(b"1"));
        insert_with_conflicts(&mut m, "a/b.txt".to_string(), entry(b"2"));
        assert!(!m.contains_key("a"));
        assert!(m.contains_key("a/b.txt"));
    }
}

//! Walks the staged folder, honours .gitignore, and hashes files in parallel.

use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

use ignore::gitignore::GitignoreBuilder;
use ignore::WalkBuilder;
use rayon::prelude::*;

use crate::error::{Error, Result};
use crate::gitobj::{self, Oid};
use crate::progress::{check_cancel, Rep};
use crate::types::{EngineRequest, Skipped};

pub struct LocalFile {
    pub rel: String,
    pub abs: PathBuf,
    pub size: u64,
    pub oid: Oid,
    pub exec: bool,
}

struct Candidate {
    rel: String,
    abs: PathBuf,
    size: u64,
    exec: bool,
}

/// Git refuses these path components.
fn valid_component(c: &str) -> bool {
    !(c.is_empty() || c == "." || c == ".." || c.eq_ignore_ascii_case(".git") || c.contains('\0'))
}

#[cfg(unix)]
fn is_exec(meta: &std::fs::Metadata) -> bool {
    use std::os::unix::fs::PermissionsExt;
    meta.permissions().mode() & 0o111 != 0
}

#[cfg(not(unix))]
fn is_exec(_: &std::fs::Metadata) -> bool {
    false
}

pub fn scan(
    req: &EngineRequest,
    pool: &rayon::ThreadPool,
    rep: &Rep,
) -> Result<(Vec<LocalFile>, Vec<Skipped>)> {
    let root = PathBuf::from(&req.stage_dir);
    if !root.is_dir() {
        return Err(Error::new("NO_SOURCE", "The staged source folder is missing"));
    }

    let mut gb = GitignoreBuilder::new(&root);
    for line in &req.extra_ignores {
        let l = line.trim();
        if l.is_empty() || l.starts_with('#') {
            continue;
        }
        let _ = gb.add_line(None, l);
    }
    let extra = gb
        .build()
        .map_err(|e| Error::new("SCAN", format!("Invalid ignore pattern: {}", e)))?;

    let mut wb = WalkBuilder::new(&root);
    wb.hidden(false)
        .parents(false)
        .ignore(false)
        .git_global(false)
        .git_exclude(false)
        .git_ignore(req.respect_gitignore)
        .require_git(false)
        .follow_links(false);
    wb.filter_entry(move |e| {
        if e.depth() == 0 {
            return true;
        }
        if e.file_name() == ".git" {
            return false;
        }
        let is_dir = e.file_type().map_or(false, |t| t.is_dir());
        !extra
            .matched_path_or_any_parents(e.path(), is_dir)
            .is_ignore()
    });

    let mut skipped: Vec<Skipped> = Vec::new();
    let mut cands: Vec<Candidate> = Vec::new();
    let mut seen = 0u64;

    for result in wb.build() {
        check_cancel()?;
        let entry = match result {
            Ok(e) => e,
            Err(_) => continue,
        };
        if entry.depth() == 0 {
            continue;
        }
        let is_file = entry.file_type().map_or(false, |t| t.is_file());
        if !is_file {
            continue; // directories and symlinks
        }
        let path = entry.path();
        let rel_path = match path.strip_prefix(&root) {
            Ok(r) => r,
            Err(_) => continue,
        };
        let mut parts: Vec<&str> = Vec::new();
        let mut bad: Option<&str> = None;
        for comp in rel_path.components() {
            match comp.as_os_str().to_str() {
                Some(s) if valid_component(s) => parts.push(s),
                Some(_) => {
                    bad = Some("invalid_name");
                    break;
                }
                None => {
                    bad = Some("non_utf8_name");
                    break;
                }
            }
        }
        if let Some(reason) = bad {
            skipped.push(Skipped {
                path: rel_path.to_string_lossy().to_string(),
                reason: reason.to_string(),
            });
            continue;
        }
        let rel = parts.join("/");
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(_) => {
                skipped.push(Skipped {
                    path: rel,
                    reason: "unreadable".into(),
                });
                continue;
            }
        };
        let size = meta.len();
        if size > req.max_file_bytes {
            skipped.push(Skipped {
                path: rel,
                reason: "too_large".into(),
            });
            continue;
        }
        cands.push(Candidate {
            rel,
            abs: path.to_path_buf(),
            size,
            exec: is_exec(&meta),
        });
        seen += 1;
        if seen % 256 == 0 {
            rep.report("scan", seen, 0, "", false);
        }
    }
    rep.report("scan", seen, 0, "", true);

    let total_bytes: u64 = cands.iter().map(|c| c.size).sum();
    let done = AtomicU64::new(0);

    let results: Vec<std::result::Result<LocalFile, Skipped>> = pool.install(|| {
        cands
            .par_iter()
            .map(|c| {
                if check_cancel().is_err() {
                    return Err(Skipped {
                        path: c.rel.clone(),
                        reason: "cancelled".into(),
                    });
                }
                match gitobj::hash_blob_file(&c.abs, c.size) {
                    Ok(oid) => {
                        let d = done.fetch_add(c.size, Ordering::Relaxed) + c.size;
                        rep.report("hash", d, total_bytes, &c.rel, false);
                        Ok(LocalFile {
                            rel: c.rel.clone(),
                            abs: c.abs.clone(),
                            size: c.size,
                            oid,
                            exec: c.exec,
                        })
                    }
                    Err(e) => Err(Skipped {
                        path: c.rel.clone(),
                        reason: format!("unreadable: {}", e.message),
                    }),
                }
            })
            .collect()
    });
    check_cancel()?;

    let mut files = Vec::with_capacity(results.len());
    for r in results {
        match r {
            Ok(f) => files.push(f),
            Err(s) => skipped.push(s),
        }
    }
    files.sort_by(|a, b| a.rel.cmp(&b.rel));
    rep.report("hash", total_bytes, total_bytes, "", true);
    Ok((files, skipped))
}

//! Safe ZIP extraction with optional single-root-folder stripping.

use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Write};
use std::path::Path;

use crate::error::{Error, Result};
use crate::progress::{check_cancel, Rep};
use crate::types::{ExtractOut, Skipped};

const MAX_TOTAL: u64 = 8 * 1024 * 1024 * 1024;

/// Splits an archive path into safe components. Returns None for traversal attempts.
fn clean_components(raw: &str) -> Option<Vec<String>> {
    let mut out = Vec::new();
    for c in raw.replace('\\', "/").split('/') {
        let c = c.trim();
        if c.is_empty() || c == "." {
            continue;
        }
        if c == ".." || c.contains('\0') {
            return None;
        }
        out.push(c.to_string());
    }
    Some(out)
}

fn skippable(c: &[String]) -> bool {
    c.iter().any(|p| p == "__MACOSX" || p.eq_ignore_ascii_case(".git"))
        || c.last().map_or(false, |l| l == ".DS_Store")
}

struct Item {
    comps: Vec<String>,
    is_dir: bool,
    size: u64,
}

pub fn extract(
    file: File,
    dest: &Path,
    strip_root: bool,
    max_file_bytes: u64,
    rep: &Rep,
) -> Result<ExtractOut> {
    let mut archive = zip::ZipArchive::new(BufReader::with_capacity(128 * 1024, file))?;
    let n = archive.len();

    // Pass 1: read the central directory only.
    let mut items: Vec<Option<Item>> = Vec::with_capacity(n);
    let mut total: u64 = 0;
    for i in 0..n {
        let f = archive.by_index_raw(i)?;
        let raw = f.name().to_string();
        let is_dir = f.is_dir() || raw.ends_with('/');
        let size = f.size();
        drop(f);
        match clean_components(&raw) {
            Some(c) if !c.is_empty() && !skippable(&c) => {
                total = total.saturating_add(size);
                items.push(Some(Item {
                    comps: c,
                    is_dir,
                    size,
                }));
            }
            _ => items.push(None),
        }
    }
    if total > MAX_TOTAL {
        return Err(Error::new(
            "ZIP_TOO_LARGE",
            "The archive expands to more than 8 GB",
        ));
    }

    // GitHub-style archives wrap everything in one folder; detect and drop it.
    let mut strip: Option<String> = None;
    if strip_root {
        let mut first: Option<&str> = None;
        let mut ok = true;
        let mut nested = false;
        for it in items.iter().flatten() {
            let head = it.comps[0].as_str();
            match first {
                None => first = Some(head),
                Some(f) if f == head => {}
                _ => {
                    ok = false;
                    break;
                }
            }
            if it.comps.len() > 1 {
                nested = true;
            } else if !it.is_dir {
                ok = false;
                break;
            }
        }
        if ok && nested {
            strip = first.map(|s| s.to_string());
        }
    }

    fs::create_dir_all(dest)?;
    let mut done: u64 = 0;
    let mut files: u64 = 0;
    let mut skipped: Vec<Skipped> = Vec::new();

    for i in 0..n {
        check_cancel()?;
        let item = match items[i].take() {
            Some(it) => it,
            None => continue,
        };
        let mut comps = item.comps;
        if let Some(root) = &strip {
            if comps.first() == Some(root) {
                comps.remove(0);
            }
        }
        if comps.is_empty() {
            continue;
        }
        let rel = comps.join("/");
        let out_path = dest.join(&rel);

        if item.is_dir {
            fs::create_dir_all(&out_path)?;
            continue;
        }
        if item.size > max_file_bytes {
            skipped.push(Skipped {
                path: rel,
                reason: "too_large".into(),
            });
            continue;
        }

        let mut zf = archive.by_index(i)?;
        let mode = zf.unix_mode();
        if let Some(m) = mode {
            if m & 0o170000 == 0o120000 {
                skipped.push(Skipped {
                    path: rel,
                    reason: "symlink".into(),
                });
                continue;
            }
        }
        if let Some(parent) = out_path.parent() {
            fs::create_dir_all(parent)?;
        }
        let out = File::create(&out_path)?;
        let mut w = BufWriter::with_capacity(256 * 1024, out);
        let copied = io::copy(&mut zf, &mut w)?;
        w.flush()?;
        drop(w);

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let exec = mode.map_or(false, |m| m & 0o111 != 0);
            let _ = fs::set_permissions(
                &out_path,
                fs::Permissions::from_mode(if exec { 0o755 } else { 0o644 }),
            );
        }

        done += copied;
        files += 1;
        rep.report("extract", done, total, &rel, false);
    }
    rep.report("extract", done, total, "", true);

    Ok(ExtractOut {
        files,
        bytes: done,
        stripped_root: strip,
        skipped,
    })
}

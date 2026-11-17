//! Git object encoding: blob hashing, tree building, commit serialization.

use std::collections::BTreeMap;
use std::io::Read;
use std::path::Path;

use sha1::{Digest, Sha1};

use crate::error::{Error, Result};

pub type Oid = [u8; 20];

pub const ZERO_HEX: &str = "0000000000000000000000000000000000000000";

pub fn to_hex(oid: &Oid) -> String {
    const H: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(40);
    for b in oid {
        s.push(H[(b >> 4) as usize] as char);
        s.push(H[(b & 15) as usize] as char);
    }
    s
}

fn hexval(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

pub fn from_hex(s: &str) -> Option<Oid> {
    let b = s.as_bytes();
    if b.len() != 40 {
        return None;
    }
    let mut out = [0u8; 20];
    for i in 0..20 {
        out[i] = (hexval(b[2 * i])? << 4) | hexval(b[2 * i + 1])?;
    }
    Some(out)
}

pub fn finish(h: Sha1) -> Oid {
    let mut out = [0u8; 20];
    out.copy_from_slice(&h.finalize());
    out
}

pub fn hash_object(kind: &str, data: &[u8]) -> Oid {
    let mut h = Sha1::new();
    h.update(format!("{} {}\0", kind, data.len()).as_bytes());
    h.update(data);
    finish(h)
}

/// Streams the file and returns its git blob id. Fails if the size changed
/// while reading (so the id always matches what gets packed later).
pub fn hash_blob_file(path: &Path, size: u64) -> Result<Oid> {
    let mut f = std::fs::File::open(path)?;
    let mut h = Sha1::new();
    h.update(format!("blob {}\0", size).as_bytes());
    let mut buf = vec![0u8; (size as usize).clamp(1, 256 * 1024)];
    let mut total = 0u64;
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
        total += n as u64;
    }
    if total != size {
        return Err(Error::new(
            "FILE_CHANGED",
            format!("{} changed while it was being read", path.display()),
        ));
    }
    Ok(finish(h))
}

#[derive(Clone, Debug)]
pub struct FileEntry {
    pub mode: String,
    pub oid: Oid,
    pub size: u64,
}

struct TreeEntry<'a> {
    name: &'a str,
    mode: &'a str,
    oid: Oid,
    is_tree: bool,
}

fn encode_tree(entries: &mut Vec<TreeEntry>) -> Vec<u8> {
    // Git sorts entries bytewise, comparing directories as if their name ended in '/'.
    entries.sort_by(|a, b| {
        let mut ka = a.name.as_bytes().to_vec();
        let mut kb = b.name.as_bytes().to_vec();
        if a.is_tree {
            ka.push(b'/');
        }
        if b.is_tree {
            kb.push(b'/');
        }
        ka.cmp(&kb)
    });
    let mut out = Vec::new();
    for e in entries.iter() {
        out.extend_from_slice(e.mode.as_bytes());
        out.push(b' ');
        out.extend_from_slice(e.name.as_bytes());
        out.push(0);
        out.extend_from_slice(&e.oid);
    }
    out
}

#[derive(Default)]
struct Dir {
    files: BTreeMap<String, FileEntry>,
    dirs: BTreeMap<String, Dir>,
}

pub struct BuiltTrees {
    pub root: Oid,
    /// Every tree object of the new snapshot (callers drop the ones the remote already has).
    pub trees: Vec<(Oid, Vec<u8>)>,
}

fn write_dir(dir: &Dir, out: &mut Vec<(Oid, Vec<u8>)>) -> Option<Oid> {
    let mut sub: Vec<(&String, Oid)> = Vec::new();
    for (name, d) in &dir.dirs {
        if let Some(oid) = write_dir(d, out) {
            sub.push((name, oid));
        }
    }
    let mut entries: Vec<TreeEntry> = Vec::with_capacity(dir.files.len() + sub.len());
    for (name, oid) in sub {
        entries.push(TreeEntry {
            name: name.as_str(),
            mode: "40000",
            oid,
            is_tree: true,
        });
    }
    for (name, f) in &dir.files {
        entries.push(TreeEntry {
            name: name.as_str(),
            mode: f.mode.as_str(),
            oid: f.oid,
            is_tree: false,
        });
    }
    if entries.is_empty() {
        return None; // git does not track empty directories
    }
    let bytes = encode_tree(&mut entries);
    let oid = hash_object("tree", &bytes);
    out.push((oid, bytes));
    Some(oid)
}

/// Builds the full tree hierarchy for a flat `path -> entry` map.
pub fn build_trees(files: &BTreeMap<String, FileEntry>) -> BuiltTrees {
    let mut root = Dir::default();
    for (path, entry) in files {
        let mut parts: Vec<&str> = path.split('/').collect();
        let file_name = match parts.pop() {
            Some(n) => n,
            None => continue,
        };
        let mut cur = &mut root;
        for p in parts {
            cur = cur.dirs.entry(p.to_string()).or_default();
        }
        cur.files.insert(file_name.to_string(), entry.clone());
    }
    let mut trees = Vec::new();
    let root_oid = match write_dir(&root, &mut trees) {
        Some(o) => o,
        None => {
            let o = hash_object("tree", b"");
            trees.push((o, Vec::new()));
            o
        }
    };
    BuiltTrees {
        root: root_oid,
        trees,
    }
}

pub fn encode_commit(
    tree: &Oid,
    parent: Option<&Oid>,
    author: &str,
    committer: &str,
    message: &str,
) -> Vec<u8> {
    let mut s = String::new();
    s.push_str(&format!("tree {}\n", to_hex(tree)));
    if let Some(p) = parent {
        s.push_str(&format!("parent {}\n", to_hex(p)));
    }
    s.push_str(&format!("author {}\n", author));
    s.push_str(&format!("committer {}\n\n", committer));
    s.push_str(message.trim_end_matches('\n'));
    s.push('\n');
    s.into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_blob_hash() {
        assert_eq!(
            to_hex(&hash_object("blob", b"hello\n")),
            "ce013625030ba8dba906f756967f9e9ca394464a"
        );
    }

    #[test]
    fn empty_tree_hash() {
        assert_eq!(
            to_hex(&hash_object("tree", b"")),
            "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
        );
    }

    #[test]
    fn hex_roundtrip() {
        let h = "ce013625030ba8dba906f756967f9e9ca394464a";
        assert_eq!(to_hex(&from_hex(h).unwrap()), h);
        assert!(from_hex("zz").is_none());
    }

    #[test]
    fn tree_sort_order_matches_git() {
        let oid = hash_object("blob", b"x");
        let mut m = BTreeMap::new();
        for p in ["a.txt", "a-b", "a/inner.txt", "b"] {
            m.insert(
                p.to_string(),
                FileEntry {
                    mode: "100644".into(),
                    oid,
                    size: 1,
                },
            );
        }
        let built = build_trees(&m);
        let (_, root_bytes) = built.trees.last().unwrap();
        let text = String::from_utf8_lossy(root_bytes).to_string();
        let ia = text.find("a-b").unwrap();
        let idot = text.find("a.txt").unwrap();
        let idir = text.find("40000 a").unwrap();
        assert!(ia < idot && idot < idir);
    }

    #[test]
    fn commit_layout() {
        let t = hash_object("tree", b"");
        let c = encode_commit(&t, None, "A <a@b> 1 +0000", "A <a@b> 1 +0000", "msg");
        let s = String::from_utf8(c).unwrap();
        assert!(s.starts_with("tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\nauthor A"));
        assert!(s.ends_with("\n\nmsg\n"));
    }
}

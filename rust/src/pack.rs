//! Writes a Git packfile (version 2, undeltified) to disk.
//! Blobs are compressed in parallel in bounded batches, then streamed in order.

use std::fs::File;
use std::io::{self, BufWriter, Read, Write};
use std::path::Path;

use flate2::write::ZlibEncoder;
use flate2::Compression;
use rayon::prelude::*;
use sha1::{Digest, Sha1};

use crate::error::{Error, Result};
use crate::gitobj::{self, Oid};
use crate::progress::{check_cancel, Rep};

const T_COMMIT: u8 = 1;
const T_TREE: u8 = 2;
const T_BLOB: u8 = 3;
const BATCH_BYTES: u64 = 32 * 1024 * 1024;
const BATCH_FILES: usize = 256;

pub struct BlobSrc<'a> {
    pub path: &'a Path,
    pub size: u64,
    pub oid: Oid,
}

struct HashWriter<W: Write> {
    inner: W,
    hasher: Sha1,
    written: u64,
}

impl<W: Write> Write for HashWriter<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let n = self.inner.write(buf)?;
        self.hasher.update(&buf[..n]);
        self.written += n as u64;
        Ok(n)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

fn write_header<W: Write>(w: &mut W, typ: u8, size: u64) -> io::Result<()> {
    let mut first = (typ << 4) | (size & 0x0f) as u8;
    let mut rest = size >> 4;
    let mut out = [0u8; 12];
    let mut n = 0usize;
    if rest > 0 {
        first |= 0x80;
    }
    out[n] = first;
    n += 1;
    while rest > 0 {
        let mut b = (rest & 0x7f) as u8;
        rest >>= 7;
        if rest > 0 {
            b |= 0x80;
        }
        out[n] = b;
        n += 1;
    }
    w.write_all(&out[..n])
}

fn deflate(data: &[u8]) -> io::Result<Vec<u8>> {
    let mut e = ZlibEncoder::new(
        Vec::with_capacity(data.len() / 2 + 64),
        Compression::fast(),
    );
    e.write_all(data)?;
    e.finish()
}

fn write_small<W: Write>(w: &mut W, typ: u8, data: &[u8]) -> io::Result<()> {
    write_header(w, typ, data.len() as u64)?;
    w.write_all(&deflate(data)?)
}

/// Compresses a blob while re-hashing it, so a file that changed on disk after
/// the scan can never produce a corrupt pack.
fn compress_blob(b: &BlobSrc) -> Result<Vec<u8>> {
    let mut f = File::open(b.path)?;
    let mut enc = ZlibEncoder::new(
        Vec::with_capacity((b.size as usize / 2).min(8 * 1024 * 1024) + 64),
        Compression::fast(),
    );
    let mut hasher = Sha1::new();
    hasher.update(format!("blob {}\0", b.size).as_bytes());
    let mut buf = vec![0u8; (b.size as usize).clamp(1, 256 * 1024)];
    let mut total = 0u64;
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        total += n as u64;
        hasher.update(&buf[..n]);
        enc.write_all(&buf[..n])?;
    }
    if total != b.size || gitobj::finish(hasher) != b.oid {
        return Err(Error::new(
            "FILE_CHANGED",
            format!("{} changed while it was being packed", b.path.display()),
        ));
    }
    Ok(enc.finish()?)
}

/// Returns the total pack size in bytes.
pub fn write_pack(
    path: &Path,
    commit: &[u8],
    trees: &[(Oid, Vec<u8>)],
    blobs: &[BlobSrc],
    pool: &rayon::ThreadPool,
    rep: &Rep,
) -> Result<u64> {
    let file = File::create(path)?;
    let mut w = HashWriter {
        inner: BufWriter::with_capacity(512 * 1024, file),
        hasher: Sha1::new(),
        written: 0,
    };
    let count = 1 + trees.len() + blobs.len();
    w.write_all(b"PACK")?;
    w.write_all(&2u32.to_be_bytes())?;
    w.write_all(&(count as u32).to_be_bytes())?;

    write_small(&mut w, T_COMMIT, commit)?;
    for (_, data) in trees {
        write_small(&mut w, T_TREE, data)?;
    }

    let total: u64 = blobs.iter().map(|b| b.size).sum();
    let mut done: u64 = 0;
    let mut i = 0usize;
    while i < blobs.len() {
        check_cancel()?;
        let mut j = i;
        let mut budget = 0u64;
        while j < blobs.len() && (j == i || (budget + blobs[j].size <= BATCH_BYTES && j - i < BATCH_FILES)) {
            budget += blobs[j].size;
            j += 1;
        }
        let batch = &blobs[i..j];
        let compressed: Vec<Vec<u8>> = pool.install(|| {
            batch
                .par_iter()
                .map(|b| compress_blob(b))
                .collect::<Result<Vec<_>>>()
        })?;
        for (b, data) in batch.iter().zip(compressed.iter()) {
            write_header(&mut w, T_BLOB, b.size)?;
            w.write_all(data)?;
            done += b.size;
            rep.report("pack", done, total, "", false);
        }
        i = j;
    }
    rep.report("pack", total, total, "", true);

    let digest = w.hasher.clone().finalize();
    let written = w.written;
    let mut inner = w.inner;
    inner.write_all(&digest)?;
    inner.flush()?;
    Ok(written + 20)
}

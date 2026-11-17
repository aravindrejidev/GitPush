//! Git pkt-line framing helpers.

use crate::error::{Error, Result};

pub enum Pkt<'a> {
    Flush,
    Delim,
    Data(&'a [u8]),
}

pub fn encode(data: &[u8]) -> Vec<u8> {
    let mut out = format!("{:04x}", data.len() + 4).into_bytes();
    out.extend_from_slice(data);
    out
}

pub fn parse(buf: &[u8]) -> Result<Vec<Pkt<'_>>> {
    let mut out = Vec::new();
    let mut pos = 0usize;
    while pos < buf.len() {
        if pos + 4 > buf.len() {
            return Err(Error::new("PROTOCOL", "Truncated pkt-line header"));
        }
        let hex = std::str::from_utf8(&buf[pos..pos + 4])
            .map_err(|_| Error::new("PROTOCOL", "Invalid pkt-line header"))?;
        let len = usize::from_str_radix(hex, 16)
            .map_err(|_| Error::new("PROTOCOL", "Invalid pkt-line length"))?;
        match len {
            0 => {
                out.push(Pkt::Flush);
                pos += 4;
            }
            1 => {
                out.push(Pkt::Delim);
                pos += 4;
            }
            2 => {
                out.push(Pkt::Flush);
                pos += 4;
            }
            3 => return Err(Error::new("PROTOCOL", "Invalid pkt-line length")),
            _ => {
                if pos + len > buf.len() {
                    return Err(Error::new("PROTOCOL", "Truncated pkt-line body"));
                }
                out.push(Pkt::Data(&buf[pos + 4..pos + len]));
                pos += len;
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_prefix() {
        assert_eq!(encode(b"hello\n"), b"000ahello\n".to_vec());
    }

    #[test]
    fn parse_mixed() {
        let mut v = encode(b"abc");
        v.extend_from_slice(b"0000");
        v.extend_from_slice(&encode(b"x"));
        let p = parse(&v).unwrap();
        assert_eq!(p.len(), 3);
        assert!(matches!(p[0], Pkt::Data(d) if d == b"abc"));
        assert!(matches!(p[1], Pkt::Flush));
    }

    #[test]
    fn rejects_truncated() {
        assert!(parse(b"00ffabc").is_err());
    }
}

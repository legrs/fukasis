// SPDX-License-Identifier: MIT
//! アプリが書き出す TIFF (stacked.tif / darked.tif) の読み書き.
//! 対応するのは無圧縮・1 チャンネル・ストリップ形式だけ (OpenCV が 32bit float を書き出すときの形式).

use crate::image::Image;

struct Reader<'a> {
    bytes: &'a [u8],
    little: bool,
}

impl Reader<'_> {
    fn slice(&self, offset: usize, len: usize) -> Result<&[u8], String> {
        offset
            .checked_add(len)
            .and_then(|end| self.bytes.get(offset..end))
            .ok_or_else(|| "TIFF が途中で切れています".to_string())
    }

    fn u16(&self, offset: usize) -> Result<u16, String> {
        let b: [u8; 2] = self.slice(offset, 2)?.try_into().unwrap();
        Ok(if self.little {
            u16::from_le_bytes(b)
        } else {
            u16::from_be_bytes(b)
        })
    }

    fn u32(&self, offset: usize) -> Result<u32, String> {
        let b: [u8; 4] = self.slice(offset, 4)?.try_into().unwrap();
        Ok(if self.little {
            u32::from_le_bytes(b)
        } else {
            u32::from_be_bytes(b)
        })
    }

    /// IFD の 1 項目 (12 バイト) の値を整数の列として読む
    fn entry_values(&self, entry: usize) -> Result<Vec<u64>, String> {
        let kind = self.u16(entry + 2)?;
        let count = self.u32(entry + 4)? as usize;
        let size = match kind {
            1 | 6 => 1,
            3 | 8 => 2,
            4 | 9 => 4,
            _ => return Err(format!("TIFF のタグの型 {} には対応していません", kind)),
        };
        // 4 バイトに収まる値は項目の中に直接入っている
        let start = if size * count <= 4 {
            entry + 8
        } else {
            self.u32(entry + 8)? as usize
        };
        (0..count)
            .map(|i| {
                let at = start + i * size;
                Ok(match size {
                    1 => self.slice(at, 1)?[0] as u64,
                    2 => self.u16(at)? as u64,
                    _ => self.u32(at)? as u64,
                })
            })
            .collect()
    }
}

pub fn decode(bytes: &[u8]) -> Result<Image, String> {
    let little = match bytes.get(0..2) {
        Some(b"II") => true,
        Some(b"MM") => false,
        _ => return Err("TIFF ではありません".to_string()),
    };
    let r = Reader { bytes, little };
    match r.u16(2)? {
        42 => {}
        43 => return Err("BigTIFF には対応していません".to_string()),
        _ => return Err("TIFF ではありません".to_string()),
    }
    let ifd = r.u32(4)? as usize;
    let entries = r.u16(ifd)? as usize;

    let (mut width, mut height) = (0usize, 0usize);
    let (mut bits, mut compression, mut samples, mut format) = (1u64, 1u64, 1u64, 1u64);
    let mut rows_per_strip = u64::MAX;
    let (mut offsets, mut counts) = (Vec::new(), Vec::new());
    for i in 0..entries {
        let entry = ifd + 2 + i * 12;
        let tag = r.u16(entry)?;
        // 使わないタグは型を問わず読み飛ばす
        if ![256, 257, 258, 259, 273, 277, 278, 279, 322, 339].contains(&tag) {
            continue;
        }
        let values = r.entry_values(entry)?;
        let first = values.first().copied().unwrap_or(0);
        match tag {
            256 => width = first as usize,
            257 => height = first as usize,
            258 => bits = first,
            259 => compression = first,
            273 => offsets = values,
            277 => samples = first,
            278 => rows_per_strip = first,
            279 => counts = values,
            322 => return Err("タイル形式の TIFF には対応していません".to_string()),
            339 => format = first,
            _ => {}
        }
    }
    if width == 0 || height == 0 {
        return Err("TIFF に画像の大きさが書かれていません".to_string());
    }
    if compression != 1 {
        return Err(format!(
            "圧縮された TIFF (compression={}) には対応していません。無圧縮で保存し直してください",
            compression
        ));
    }
    if samples != 1 {
        return Err(format!(
            "{} チャンネルの TIFF には対応していません (1 チャンネルのみ)",
            samples
        ));
    }
    let bytes_per_sample = (bits / 8) as usize;
    let convert: fn(&[u8], bool) -> f32 = match (bits, format) {
        (8, 1) => |b, _| b[0] as f32,
        (16, 1) => |b, le| {
            (if le {
                u16::from_le_bytes([b[0], b[1]])
            } else {
                u16::from_be_bytes([b[0], b[1]])
            }) as f32
        },
        (16, 2) => |b, le| {
            (if le {
                i16::from_le_bytes([b[0], b[1]])
            } else {
                i16::from_be_bytes([b[0], b[1]])
            }) as f32
        },
        (32, 1) => |b, le| {
            let a = [b[0], b[1], b[2], b[3]];
            (if le {
                u32::from_le_bytes(a)
            } else {
                u32::from_be_bytes(a)
            }) as f32
        },
        (32, 3) => |b, le| {
            let a = [b[0], b[1], b[2], b[3]];
            if le {
                f32::from_le_bytes(a)
            } else {
                f32::from_be_bytes(a)
            }
        },
        (64, 3) => |b, le| {
            let a: [u8; 8] = b[0..8].try_into().unwrap();
            (if le {
                f64::from_le_bytes(a)
            } else {
                f64::from_be_bytes(a)
            }) as f32
        },
        _ => {
            return Err(format!(
                "{} bit (sample format {}) の TIFF には対応していません",
                bits, format
            ))
        }
    };

    let rows_per_strip = (rows_per_strip.min(height as u64) as usize).max(1);
    let strips = height.div_ceil(rows_per_strip);
    if offsets.len() < strips {
        return Err("TIFF のストリップの数が足りません".to_string());
    }
    let _ = counts; // 大きさは画像の寸法から決まるので, StripByteCounts は使わない

    let mut data = Vec::with_capacity(width * height);
    for (s, &offset) in offsets.iter().enumerate().take(strips) {
        let rows = rows_per_strip.min(height - s * rows_per_strip);
        let raw = r.slice(offset as usize, rows * width * bytes_per_sample)?;
        data.extend(
            raw.chunks_exact(bytes_per_sample)
                .map(|b| convert(b, little)),
        );
    }
    Ok(Image {
        width,
        height,
        data,
    })
}

/// 32bit float・無圧縮・1 ストリップの TIFF として書き出す (リトルエンディアン)
pub fn encode(img: &Image) -> Vec<u8> {
    let data_len = img.data.len() * 4;
    let ifd_offset = 8 + data_len + (data_len % 2);
    let mut out = Vec::with_capacity(ifd_offset + 2 + 10 * 12 + 4);
    out.extend_from_slice(b"II");
    out.extend_from_slice(&42u16.to_le_bytes());
    out.extend_from_slice(&(ifd_offset as u32).to_le_bytes());
    for v in &img.data {
        out.extend_from_slice(&v.to_le_bytes());
    }
    if data_len % 2 == 1 {
        out.push(0);
    }

    const SHORT: u16 = 3;
    const LONG: u16 = 4;
    // タグは番号の昇順に並べる決まり
    let entries: [(u16, u16, u32); 10] = [
        (256, LONG, img.width as u32),  // ImageWidth
        (257, LONG, img.height as u32), // ImageLength
        (258, SHORT, 32),               // BitsPerSample
        (259, SHORT, 1),                // Compression: なし
        (262, SHORT, 1),                // PhotometricInterpretation: BlackIsZero
        (273, LONG, 8),                 // StripOffsets
        (277, SHORT, 1),                // SamplesPerPixel
        (278, LONG, img.height as u32), // RowsPerStrip
        (279, LONG, data_len as u32),   // StripByteCounts
        (339, SHORT, 3),                // SampleFormat: IEEE float
    ];
    out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
    for (tag, kind, value) in entries {
        out.extend_from_slice(&tag.to_le_bytes());
        out.extend_from_slice(&kind.to_le_bytes());
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&value.to_le_bytes());
    }
    out.extend_from_slice(&0u32.to_le_bytes());
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let img = Image {
            width: 3,
            height: 2,
            data: vec![0.0, -1.5, 2.25, 1e6, f32::MIN_POSITIVE, 1023.0],
        };
        assert_eq!(decode(&encode(&img)).unwrap(), img);
    }

    #[test]
    fn rejects_non_tiff() {
        assert!(decode(b"not a tiff").is_err());
        assert!(decode(&[]).is_err());
    }
}

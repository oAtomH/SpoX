//! Downloads album covers (cached) and turns them into chunky pixel art with a small palette.

use crate::config;
use crate::net;
use crate::state::Ev;
use eframe::egui;
use sha2::Digest;
use std::io::Read;
use std::sync::mpsc::Sender;

/// `pixels`: output size. `colors`: palette size for pixel mode (0 = keep all colours, "sharp" mode).
pub fn request(url: String, pixels: u32, colors: usize, ev: Sender<Ev>, ctx: egui::Context) {
    let u2 = url.clone();
    request_with(url, pixels, colors, move |img| {
        let _ = ev.send(Ev::Art { url: u2, img });
        ctx.request_repaint();
    });
}

/// Same as `request`, but hands the finished image to `done`.
pub fn request_with(url: String, pixels: u32, colors: usize, done: impl FnOnce(egui::ColorImage) + Send + 'static) {
    std::thread::spawn(move || {
        let hash = sha2::Sha256::digest(url.as_bytes());
        let name: String = hash.iter().take(10).map(|b| format!("{:02x}", b)).collect();
        let path = config::cache_dir().join("art").join(name);
        let bytes = std::fs::read(&path).ok().or_else(|| {
            let r = net::agent().get(&url).call().ok()?;
            let mut b = Vec::new();
            r.into_reader().take(8 << 20).read_to_end(&mut b).ok()?;
            let _ = std::fs::create_dir_all(path.parent()?);
            let _ = std::fs::write(&path, &b);
            Some(b)
        });
        let Some(bytes) = bytes else { return };
        let Ok(img) = image::load_from_memory(&bytes) else { return };
        let px = if colors == 0 {
            image::imageops::resize(&img.to_rgba8(), pixels, pixels, image::imageops::FilterType::Triangle).into_raw()
        } else {
            pixelate(&img.to_rgba8(), pixels, colors)
        };
        let ci = egui::ColorImage::from_rgba_unmultiplied([pixels as usize, pixels as usize], &px);
        done(ci);
    });
}

/// Real pixel art instead of a blurry thumbnail:
/// 1. find a small palette with k-means,
/// 2. each output pixel takes the colour that covers most of its block (a vote, never an average),
///    so red next to white stays red and white, never pink.
pub fn pixelate(src: &image::RgbaImage, out: u32, k: usize) -> Vec<u8> {
    let sub = 4u32;
    let work = image::imageops::resize(src, out * sub, out * sub, image::imageops::FilterType::Triangle);
    let pts: Vec<[f32; 3]> = work.pixels().map(|p| [p[0] as f32, p[1] as f32, p[2] as f32]).collect();
    let k = k.clamp(2, 32).min(pts.len());

    // k-means++ style deterministic seeding: start from the mean, then repeatedly add the farthest point
    let mut cents: Vec<[f32; 3]> = Vec::with_capacity(k);
    let mean = pts.iter().fold([0.0; 3], |a, p| [a[0] + p[0], a[1] + p[1], a[2] + p[2]]);
    let n = pts.len() as f32;
    cents.push([mean[0] / n, mean[1] / n, mean[2] / n]);
    let d2 = |a: &[f32; 3], b: &[f32; 3]| {
        // weighted RGB distance (closer to how eyes see colour)
        let (dr, dg, db) = (a[0] - b[0], a[1] - b[1], a[2] - b[2]);
        2.0 * dr * dr + 4.0 * dg * dg + 3.0 * db * db
    };
    while cents.len() < k {
        let far = pts
            .iter()
            .max_by(|a, b| {
                let da = cents.iter().map(|c| d2(a, c)).fold(f32::MAX, f32::min);
                let db = cents.iter().map(|c| d2(b, c)).fold(f32::MAX, f32::min);
                da.partial_cmp(&db).unwrap()
            })
            .copied()
            .unwrap();
        cents.push(far);
    }
    let mut assign = vec![0usize; pts.len()];
    for _ in 0..10 {
        for (i, p) in pts.iter().enumerate() {
            assign[i] = (0..cents.len()).min_by(|&a, &b| d2(p, &cents[a]).partial_cmp(&d2(p, &cents[b])).unwrap()).unwrap();
        }
        let mut sum = vec![[0.0f32; 4]; cents.len()];
        for (i, p) in pts.iter().enumerate() {
            let s = &mut sum[assign[i]];
            s[0] += p[0];
            s[1] += p[1];
            s[2] += p[2];
            s[3] += 1.0;
        }
        for (c, s) in cents.iter_mut().zip(sum) {
            if s[3] > 0.0 {
                *c = [s[0] / s[3], s[1] / s[3], s[2] / s[3]];
            }
        }
    }
    let ww = out * sub;
    let mut px = Vec::with_capacity((out * out * 4) as usize);
    for oy in 0..out {
        for ox in 0..out {
            let mut votes = vec![0u32; cents.len()];
            for dy in 0..sub {
                for dx in 0..sub {
                    let i = ((oy * sub + dy) * ww + ox * sub + dx) as usize;
                    // centre pixels count double so thin details survive
                    let w = if (1..sub - 1).contains(&dx) && (1..sub - 1).contains(&dy) { 2 } else { 1 };
                    votes[assign[i]] += w;
                }
            }
            let best = (0..votes.len()).max_by_key(|&i| votes[i]).unwrap();
            let c = cents[best];
            px.extend_from_slice(&[c[0].round() as u8, c[1].round() as u8, c[2].round() as u8, 255]);
        }
    }
    px
}

#[cfg(test)]
mod tests {
    #[test]
    fn no_pink() {
        // red/white stripes + blue square: output must only contain colours near red, white, blue
        let mut img = image::RgbaImage::new(300, 300);
        for (x, y, p) in img.enumerate_pixels_mut() {
            *p = if x > 200 && y > 200 { image::Rgba([20, 40, 200, 255]) } else if (x / 13) % 2 == 0 { image::Rgba([220, 20, 30, 255]) } else { image::Rgba([250, 250, 250, 255]) };
        }
        let px = super::pixelate(&img, 24, 6);
        image::RgbaImage::from_raw(24, 24, px.clone()).unwrap().save("/tmp/pix_test.png").unwrap();
        for c in px.chunks(4) {
            let (r, g, b) = (c[0] as i32, c[1] as i32, c[2] as i32);
            let pink = r > 200 && g > 80 && g < 200 && b > 80 && b < 200;
            assert!(!pink, "pink pixel {:?}", c);
        }
    }
}

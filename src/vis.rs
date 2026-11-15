//! Spectrum analyser fed by WASAPI loopback (what your speakers are playing).
//! The capture only runs while music plays and the visualizer is on.

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::sync::{Arc, Mutex};
use std::time::Instant;

const N: usize = 1024;

pub struct Vis {
    stream: Option<cpal::Stream>,
    ring: Arc<Mutex<Ring>>,
    rate: f32,
    pub bars: Vec<f32>,
    pub peaks: Vec<f32>,
    peak_hold: Vec<f32>,
    last: Instant,
    failed_at: Option<Instant>,
    idle_since: Option<Instant>,
}

struct Ring {
    buf: Vec<f32>,
    pos: usize,
    fresh: bool,
}

impl Vis {
    pub fn new(bars: usize) -> Self {
        Self {
            stream: None,
            ring: Arc::new(Mutex::new(Ring { buf: vec![0.0; N], pos: 0, fresh: false })),
            rate: 48000.0,
            bars: vec![0.0; bars],
            peaks: vec![0.0; bars],
            peak_hold: vec![0.0; bars],
            last: Instant::now(),
            failed_at: None,
            idle_since: None,
        }
    }

    pub fn resize(&mut self, bars: usize) {
        if self.bars.len() != bars {
            *self = Self::new(bars);
        }
    }

    fn start(&mut self) {
        if self.stream.is_some() {
            return;
        }
        if let Some(t) = self.failed_at {
            if t.elapsed().as_secs() < 10 {
                return;
            }
        }
        match self.open() {
            Ok(s) => {
                self.stream = Some(s);
                self.failed_at = None;
            }
            Err(_) => self.failed_at = Some(Instant::now()),
        }
    }

    fn open(&mut self) -> Result<cpal::Stream, String> {
        let host = cpal::default_host();
        let dev = host.default_output_device().ok_or("no output device")?;
        let cfg = dev.default_output_config().map_err(|e| e.to_string())?;
        self.rate = cfg.sample_rate().0 as f32;
        let ch = cfg.channels().max(1) as usize;
        let ring = self.ring.clone();
        let err = |_e| {};
        let stream = match cfg.sample_format() {
            cpal::SampleFormat::F32 => dev.build_input_stream(
                &cfg.config(),
                move |d: &[f32], _: &cpal::InputCallbackInfo| push(&ring, d.chunks(ch).map(|f| f.iter().sum::<f32>() / ch as f32)),
                err,
                None,
            ),
            cpal::SampleFormat::I16 => dev.build_input_stream(
                &cfg.config(),
                move |d: &[i16], _: &cpal::InputCallbackInfo| {
                    push(&ring, d.chunks(ch).map(|f| f.iter().map(|s| *s as f32 / 32768.0).sum::<f32>() / ch as f32))
                },
                err,
                None,
            ),
            _ => return Err("unsupported format".into()),
        }
        .map_err(|e| e.to_string())?;
        stream.play().map_err(|e| e.to_string())?;
        Ok(stream)
    }

    /// Call every frame while visible. `playing` controls whether capture runs.
    pub fn update(&mut self, playing: bool) {
        let dt = self.last.elapsed().as_secs_f32().min(0.1);
        self.last = Instant::now();
        if playing {
            self.idle_since = None;
            self.start();
        } else {
            let t = *self.idle_since.get_or_insert_with(Instant::now);
            if t.elapsed().as_secs() >= 2 {
                self.stream = None; // stop capturing: zero CPU while paused
            }
        }
        let mut target = vec![0.0f32; self.bars.len()];
        if playing && self.stream.is_some() {
            let samples = {
                let mut r = self.ring.lock().unwrap();
                if r.fresh {
                    r.fresh = false;
                    let mut s = Vec::with_capacity(N);
                    s.extend_from_slice(&r.buf[r.pos..]);
                    s.extend_from_slice(&r.buf[..r.pos]);
                    Some(s)
                } else {
                    None
                }
            };
            match samples {
                Some(s) => target = spectrum(&s, self.rate, self.bars.len()),
                None => target = self.bars.iter().map(|b| b * 0.9).collect(),
            }
        }
        for i in 0..self.bars.len() {
            let fall = self.bars[i] - dt * 2.2;
            self.bars[i] = target[i].max(fall).max(0.0);
            if self.bars[i] >= self.peaks[i] {
                self.peaks[i] = self.bars[i];
                self.peak_hold[i] = 0.35;
            } else if self.peak_hold[i] > 0.0 {
                self.peak_hold[i] -= dt;
            } else {
                self.peaks[i] = (self.peaks[i] - dt * 0.8).max(0.0);
            }
        }
    }

    /// Stop capturing sound (window hidden, or the visualizer is frozen).
    pub fn sleep(&mut self) {
        self.stream = None;
    }

    pub fn active(&self) -> bool {
        self.bars.iter().chain(self.peaks.iter()).any(|v| *v > 0.001)
    }
}

fn push(ring: &Arc<Mutex<Ring>>, it: impl Iterator<Item = f32>) {
    if let Ok(mut r) = ring.lock() {
        for v in it {
            let p = r.pos;
            r.buf[p] = v;
            r.pos = (p + 1) % N;
        }
        r.fresh = true;
    }
}

fn fft(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    let mut j = 0;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j |= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2;
    while len <= n {
        let ang = -2.0 * std::f32::consts::PI / len as f32;
        let (wr, wi) = (ang.cos(), ang.sin());
        for start in (0..n).step_by(len) {
            let (mut cr, mut ci) = (1.0f32, 0.0f32);
            for k in 0..len / 2 {
                let a = start + k;
                let b = a + len / 2;
                let tr = re[b] * cr - im[b] * ci;
                let ti = re[b] * ci + im[b] * cr;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
                let nc = cr * wr - ci * wi;
                ci = cr * wi + ci * wr;
                cr = nc;
            }
        }
        len <<= 1;
    }
}

fn spectrum(s: &[f32], rate: f32, bars: usize) -> Vec<f32> {
    let n = s.len();
    let mut re: Vec<f32> = s
        .iter()
        .enumerate()
        .map(|(i, v)| v * (0.5 - 0.5 * (2.0 * std::f32::consts::PI * i as f32 / (n - 1) as f32).cos()))
        .collect();
    let mut im = vec![0.0; n];
    fft(&mut re, &mut im);
    let mag: Vec<f32> = (0..n / 2).map(|i| (re[i] * re[i] + im[i] * im[i]).sqrt() * 4.0 / n as f32).collect();
    let (lo, hi) = (50.0f32, 16000.0f32.min(rate / 2.0));
    (0..bars)
        .map(|b| {
            let f0 = lo * (hi / lo).powf(b as f32 / bars as f32);
            let f1 = lo * (hi / lo).powf((b + 1) as f32 / bars as f32);
            let i0 = ((f0 / rate * n as f32) as usize).clamp(1, n / 2 - 1);
            let i1 = ((f1 / rate * n as f32) as usize).clamp(i0 + 1, n / 2);
            let m = mag[i0..i1].iter().cloned().fold(0.0, f32::max);
            let db = 20.0 * (m + 1e-9).log10();
            // a gentle tilt so the highs aren't always empty
            let tilt = 6.0 * b as f32 / bars as f32;
            ((db + tilt + 54.0) / 48.0).clamp(0.0, 1.0)
        })
        .collect()
}

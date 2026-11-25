//! What you listened to: a small play log (for "Your stats" in the Recently played section) and,
//! once connected, Last.fm scrobbling ("now playing" + a scrobble after half the song or 4 min).
//! Runs on its own thread from Soloist's events, so it counts while SpoX is hidden too.

use crate::config::{self, Shared};
use crate::net::{self, enc};
use crate::state::{Ev, Play};
use serde_json::{json, Value};
use std::io::Write;
use std::sync::mpsc::Sender;
use std::time::{Duration, Instant};

fn log_path() -> std::path::PathBuf {
    config::config_dir().join("plays.jsonl")
}

/// Starts the listener. Returns a "tap": send every Soloist event to it.
pub fn start(cfg: Shared, ev: Sender<Ev>) -> Sender<Value> {
    let (tx, rx) = std::sync::mpsc::channel::<Value>();
    std::thread::Builder::new()
        .name("stats".into())
        .spawn(move || {
            let mut play = Play::default();
            let mut cur: Option<crate::state::Track> = None;
            let mut heard = 0u64;
            let mut started = 0i64;
            let mut last = Instant::now();
            let mut was_playing = false;
            let mut scrobbled = false;
            loop {
                let msg = rx.recv_timeout(Duration::from_secs(10));
                if matches!(msg, Err(std::sync::mpsc::RecvTimeoutError::Disconnected)) {
                    break;
                }
                if let Ok(v) = &msg {
                    play.apply(v);
                }
                // time heard since the last look
                if was_playing {
                    heard += last.elapsed().as_millis() as u64;
                }
                last = Instant::now();
                was_playing = play.status == "playing";
                let now = play.track.clone();
                if now.as_ref().map(|t| &t.uri) != cur.as_ref().map(|t| &t.uri) {
                    if let Some(t) = cur.take() {
                        let h = heard.min(t.duration_ms.max(30_000));
                        if !scrobbled && t.duration_ms > 30_000 && (h >= t.duration_ms / 2 || h >= 240_000) {
                            scrobble(&cfg, &t, started);
                        }
                        if h >= 30_000 {
                            let line = json!({"at": started * 1000, "uri": t.uri, "t": t.name, "a": t.artists, "ms": h});
                            if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(log_path()) {
                                let _ = writeln!(f, "{}", line);
                            }
                        }
                    }
                    heard = 0;
                    scrobbled = false;
                    started = config::now_secs();
                    if let Some(t) = &now {
                        if was_playing {
                            now_playing(&cfg, t);
                        }
                    }
                    cur = now;
                } else if let Some(t) = &cur {
                    if !scrobbled && t.duration_ms > 30_000 && (heard >= t.duration_ms / 2 || heard >= 240_000) {
                        scrobbled = true;
                        scrobble(&cfg, t, started);
                    }
                }
                let _ = &ev;
            }
        })
        .ok();
    tx
}

// ---------------- the stats ----------------

struct Entry {
    at: i64,
    uri: String,
    title: String,
    artist: String,
    ms: u64,
}

fn all() -> Vec<Entry> {
    let Ok(s) = std::fs::read_to_string(log_path()) else { return Vec::new() };
    s.lines()
        .filter_map(|l| {
            let v: Value = serde_json::from_str(l).ok()?;
            Some(Entry {
                at: v["at"].as_i64()?,
                uri: v["uri"].as_str()?.to_string(),
                title: v["t"].as_str()?.to_string(),
                artist: v["a"].as_str()?.to_string(),
                ms: v["ms"].as_u64()?,
            })
        })
        .collect()
}

fn time(ms: u64) -> String {
    let m = ms / 60_000;
    if m >= 60 { format!("{} h {} min", m / 60, m % 60) } else { format!("{} min", m) }
}

/// Rows (left, right, heading?) for the last `days` days (0 = all time).
pub fn rows(days: i64) -> Vec<(String, String, bool)> {
    let from = if days <= 0 { 0 } else { (config::now_secs() - days * 86400) * 1000 };
    let plays: Vec<Entry> = all().into_iter().filter(|p| p.at >= from).collect();
    let first = |a: &str| a.split(", ").next().unwrap_or("").to_string();
    let mut out = Vec::new();
    let total: u64 = plays.iter().map(|p| p.ms).sum();
    let artists: std::collections::HashMap<String, (u32, u64)> = plays.iter().fold(Default::default(), |mut m, p| {
        let e = m.entry(first(&p.artist)).or_insert((0, 0));
        e.0 += 1;
        e.1 += p.ms;
        m
    });
    out.push((format!("Listened {}", time(total)), format!("{} songs \u{b7} {} artists", plays.len(), artists.len()), false));
    if plays.is_empty() {
        return out;
    }
    out.push(("Top artists".into(), String::new(), true));
    let mut a: Vec<_> = artists.into_iter().collect();
    a.sort_by(|x, y| y.1 .1.cmp(&x.1 .1));
    for (i, (name, (n, ms))) in a.into_iter().take(10).enumerate() {
        out.push((format!("{}. {}", i + 1, name), format!("{} \u{b7} {}", n, time(ms)), false));
    }
    out.push(("Top songs".into(), String::new(), true));
    let mut songs: std::collections::HashMap<&str, (u32, &Entry)> = Default::default();
    for p in &plays {
        songs.entry(p.uri.as_str()).or_insert((0, p)).0 += 1;
    }
    let mut s: Vec<_> = songs.into_values().collect();
    s.sort_by(|x, y| y.0.cmp(&x.0));
    for (i, (n, p)) in s.into_iter().take(15).enumerate() {
        out.push((format!("{}. {} - {}", i + 1, first(&p.artist), p.title), format!("{}x", n), false));
    }
    out
}

// ---------------- Last.fm ----------------

fn md5_hex(s: &str) -> String {
    use md5::{Digest, Md5};
    Md5::digest(s.as_bytes()).iter().map(|b| format!("{:02x}", b)).collect()
}

/// A signed Last.fm call (POST).
fn call(cfg: &Shared, params: &[(&str, String)]) -> Option<Value> {
    let (key, secret) = {
        let c = cfg.lock().unwrap();
        (c.lastfm_key.trim().to_string(), c.lastfm_secret.trim().to_string())
    };
    if key.is_empty() || secret.is_empty() {
        return None;
    }
    let mut p: Vec<(String, String)> = params.iter().map(|(k, v)| (k.to_string(), v.clone())).collect();
    p.push(("api_key".into(), key));
    p.sort();
    let sig = md5_hex(&(p.iter().map(|(k, v)| format!("{}{}", k, v)).collect::<String>() + &secret));
    p.push(("api_sig".into(), sig));
    p.push(("format".into(), "json".into()));
    let body = p.iter().map(|(k, v)| format!("{}={}", k, enc(v))).collect::<Vec<_>>().join("&");
    let r = net::agent()
        .post("https://ws.audioscrobbler.com/2.0/")
        .timeout(Duration::from_secs(10))
        .set("Content-Type", "application/x-www-form-urlencoded")
        .send_string(&body);
    match r {
        Ok(r) => r.into_json().ok(),
        Err(ureq::Error::Status(_, r)) => r.into_json().ok(),
        Err(_) => None,
    }
}

fn on(cfg: &Shared) -> Option<String> {
    let c = cfg.lock().unwrap();
    (!c.lastfm_sk.is_empty()).then(|| c.lastfm_sk.clone())
}

fn now_playing(cfg: &Shared, t: &crate::state::Track) {
    let Some(sk) = on(cfg) else { return };
    let (cfg, t) = (cfg.clone(), t.clone());
    std::thread::spawn(move || {
        call(&cfg, &[
            ("method", "track.updateNowPlaying".into()),
            ("artist", t.artists.split(", ").next().unwrap_or("").to_string()),
            ("track", t.name.clone()),
            ("album", t.album.clone()),
            ("duration", (t.duration_ms / 1000).to_string()),
            ("sk", sk),
        ]);
    });
}

fn scrobble(cfg: &Shared, t: &crate::state::Track, at: i64) {
    let Some(sk) = on(cfg) else { return };
    let (cfg, t) = (cfg.clone(), t.clone());
    std::thread::spawn(move || {
        call(&cfg, &[
            ("method", "track.scrobble".into()),
            ("artist[0]", t.artists.split(", ").next().unwrap_or("").to_string()),
            ("track[0]", t.name.clone()),
            ("album[0]", t.album.clone()),
            ("duration[0]", (t.duration_ms / 1000).to_string()),
            ("timestamp[0]", at.to_string()),
            ("sk", sk),
        ]);
    });
}

/// Connect Last.fm: approve SpoX in the browser; SpoX waits for that (up to 3 minutes).
pub fn connect(cfg: Shared, ev: Sender<Ev>, ctx: eframe::egui::Context) {
    std::thread::spawn(move || {
        let say = |m: &str| {
            let _ = ev.send(Ev::Osd(m.to_string()));
            ctx.request_repaint();
        };
        let Some(token) = call(&cfg, &[("method", "auth.getToken".into())]).and_then(|v| v["token"].as_str().map(|s| s.to_string())) else {
            say("LAST.FM DIDN'T ANSWER: CHECK KEY + SECRET IN SETUP");
            return;
        };
        let key = cfg.lock().unwrap().lastfm_key.trim().to_string();
        net::open_url(&format!("https://www.last.fm/api/auth/?api_key={}&token={}", key, token));
        say("APPROVE SPOX ON LAST.FM IN YOUR BROWSER");
        for _ in 0..60 {
            std::thread::sleep(Duration::from_secs(3));
            if let Some(s) = call(&cfg, &[("method", "auth.getSession".into()), ("token", token.clone())]).and_then(|v| v.get("session").cloned()) {
                let name = s["name"].as_str().unwrap_or("").to_string();
                {
                    let mut c = cfg.lock().unwrap();
                    c.lastfm_sk = s["key"].as_str().unwrap_or("").to_string();
                    c.lastfm_user = name.clone();
                    c.save();
                }
                say(&format!("LAST.FM CONNECTED: {}", name.to_uppercase()));
                return;
            }
        }
        say("LAST.FM: NOT APPROVED IN TIME");
    });
}

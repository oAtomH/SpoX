//! Discord Rich Presence: "Listening to <song>" on your profile, with the album art,
//! a progress bar and a "Get SpoX" button. Talks to the Discord app over its local IPC pipe,
//! so nothing goes through the internet and nothing runs when Discord is closed.
//! The look is fixed (users only get an on/off switch): the SpoX icon and name link to GitHub.

use crate::config::{now_ms, Shared};
use crate::state::Play;
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// The SpoX Discord application (Discord Developer Portal). Its name ("SpoX") is what people see
/// after "Listening to"; the pixel icon is uploaded there as the art asset `spox`.
pub const APP_ID: &str = "";
/// Where the SpoX icon and the "Get SpoX" button lead.
pub const LINK: &str = "https://github.com/oAtomH/SpoX";

trait Pipe: Read + Write + Send {}
impl<T: Read + Write + Send> Pipe for T {}

fn connect() -> Option<Box<dyn Pipe>> {
    for i in 0..10 {
        #[cfg(windows)]
        {
            if let Ok(f) = std::fs::OpenOptions::new().read(true).write(true).open(format!(r"\\.\pipe\discord-ipc-{}", i)) {
                return Some(Box::new(f));
            }
        }
        #[cfg(unix)]
        {
            let base = std::env::var("XDG_RUNTIME_DIR").or_else(|_| std::env::var("TMPDIR")).unwrap_or_else(|_| "/tmp".into());
            if let Ok(s) = std::os::unix::net::UnixStream::connect(format!("{}/discord-ipc-{}", base, i)) {
                let _ = s.set_read_timeout(Some(Duration::from_secs(5)));
                return Some(Box::new(s));
            }
        }
    }
    None
}

fn send(p: &mut Box<dyn Pipe>, op: u32, v: &Value) -> std::io::Result<Value> {
    let body = serde_json::to_vec(v).unwrap_or_default();
    let mut buf = Vec::with_capacity(8 + body.len());
    buf.extend_from_slice(&op.to_le_bytes());
    buf.extend_from_slice(&(body.len() as u32).to_le_bytes());
    buf.extend_from_slice(&body);
    p.write_all(&buf)?;
    p.flush()?;
    // every request gets exactly one reply frame
    let mut h = [0u8; 8];
    p.read_exact(&mut h)?;
    let n = u32::from_le_bytes([h[4], h[5], h[6], h[7]]) as usize;
    if n > 1 << 20 {
        return Err(std::io::Error::other("frame too big"));
    }
    let mut b = vec![0u8; n];
    p.read_exact(&mut b)?;
    let op_in = u32::from_le_bytes([h[0], h[1], h[2], h[3]]);
    if op_in == 2 {
        return Err(std::io::Error::other("discord closed the connection"));
    }
    Ok(serde_json::from_slice(&b).unwrap_or(Value::Null))
}

fn nonce() -> String {
    format!("{:x}", now_ms())
}

/// Discord wants 2..128 characters.
fn clamp(s: &str) -> String {
    let mut t: String = s.chars().take(120).collect();
    while t.chars().count() < 2 {
        t.push(' ');
    }
    t
}

fn spotify_link(uri: &str) -> Option<String> {
    let mut it = uri.split(':');
    match (it.next(), it.next(), it.next()) {
        (Some("spotify"), Some(kind), Some(id)) => Some(format!("https://open.spotify.com/{}/{}", kind, id)),
        _ => None,
    }
}

fn activity(p: &Play, link: &str) -> Option<Value> {
    let t = p.track.as_ref()?;
    if !p.is_active || !(p.status == "playing" || p.status == "paused" || p.status == "buffering") {
        return None;
    }
    let art = t.cover_large.clone().or_else(|| t.cover_small.clone()).unwrap_or_else(|| "spox".into());
    let song = spotify_link(&t.uri).unwrap_or_else(|| link.to_string());
    let mut a = json!({
        "type": 2,
        // member list: "Listening to SpoX"
        "status_display_type": 0,
        "details": clamp(&t.name),
        "details_url": song,
        "state": clamp(&if p.status == "paused" { format!("Paused \u{b7} {}", t.artists) } else { t.artists.clone() }),
        "assets": {
            "large_image": art,
            "large_text": clamp(&t.album),
            "large_url": song,
            "small_image": "spox",
            "small_text": "SpoX",
            "small_url": link,
        },
    });
    if link.starts_with("https://") {
        a["buttons"] = json!([{ "label": "Get SpoX", "url": link }]);
    }
    if p.status != "paused" && t.duration_ms > 0 {
        let start = now_ms() - p.position(now_ms()) as i64;
        a["timestamps"] = json!({ "start": start, "end": start + t.duration_ms as i64 });
    }
    Some(a)
}

/// Background thread: keeps the presence in sync with what's playing.
pub fn start(cfg: Shared, live: Arc<Mutex<Play>>) {
    std::thread::Builder::new()
        .name("discord".into())
        .spawn(move || {
            let mut pipe: Option<Box<dyn Pipe>> = None;
            let mut app = String::new();
            let mut last_try = Instant::now() - Duration::from_secs(60);
            let mut last_sent: Option<(String, String, i64)> = None; // uri, status, start time
            let mut last_send_at = Instant::now() - Duration::from_secs(60);
            loop {
                std::thread::sleep(Duration::from_millis(1000));
                let on = cfg.lock().unwrap().discord;
                let (id, link) = (APP_ID.to_string(), LINK.to_string());
                if !on || id.is_empty() || id != app {
                    if let Some(mut p) = pipe.take() {
                        let _ = send(&mut p, 1, &json!({"cmd": "SET_ACTIVITY", "args": {"pid": std::process::id(), "activity": null}, "nonce": nonce()}));
                    }
                    last_sent = None;
                    app = id.clone();
                    if !on || id.is_empty() {
                        continue;
                    }
                }
                if pipe.is_none() {
                    if last_try.elapsed() < Duration::from_secs(15) {
                        continue;
                    }
                    last_try = Instant::now();
                    if let Some(mut p) = connect() {
                        if send(&mut p, 0, &json!({"v": 1, "client_id": app})).is_ok() {
                            pipe = Some(p);
                            last_sent = None;
                        }
                    }
                    if pipe.is_none() {
                        continue;
                    }
                }
                let play = live.lock().unwrap().clone();
                let act = activity(&play, &link);
                let key = play.track.as_ref().map(|t| {
                    let start = if play.status == "paused" { 0 } else { now_ms() - play.position(now_ms()) as i64 };
                    (t.uri.clone(), play.status.clone(), start)
                });
                // only resend on a real change (song, play/pause, or a seek of more than 3 s)
                let changed = match (&key, &last_sent) {
                    (Some(k), Some(l)) => k.0 != l.0 || k.1 != l.1 || (k.2 - l.2).abs() > 3000,
                    (None, None) => false,
                    _ => true,
                };
                // Discord allows about 5 updates per 20 s
                if !changed || last_send_at.elapsed() < Duration::from_secs(4) {
                    continue;
                }
                let msg = json!({"cmd": "SET_ACTIVITY", "args": {"pid": std::process::id(), "activity": act}, "nonce": nonce()});
                match send(pipe.as_mut().unwrap(), 1, &msg) {
                    Ok(_) => {
                        last_sent = key;
                        last_send_at = Instant::now();
                    }
                    Err(_) => {
                        pipe = None; // Discord closed: try again later
                    }
                }
            }
        })
        .ok();
}

//! Discord Rich Presence: "Listening to SpoX" on your profile, with the song, album art, a time bar,
//! "Listen on Spotify" + "Get SpoX" buttons, and Join: a friend with SpoX clicks Join on your profile
//! and their SpoX jumps into the same song at the same moment.
//! Talks to the Discord app over its local IPC pipe: nothing goes through the internet, and nothing
//! runs while Discord is closed. Users only get on/off switches; the look is fixed.

use crate::config::{now_ms, Shared};
use crate::soloist::{command, command_with};
use crate::state::Play;
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// The SpoX Discord application (Discord Developer Portal). Its name ("SpoX") is what people see
/// after "Listening to"; the pixel icon is uploaded there as the art asset `spox`.
pub const APP_ID: &str = "1571562342980788294";
/// Where the SpoX icon and the "Get SpoX" button lead.
pub const LINK: &str = "https://github.com/oAtomH/SpoX";

type Reader = Box<dyn Read + Send>;
type Writer = Box<dyn Write + Send>;

fn connect() -> Option<(Reader, Writer)> {
    for i in 0..10 {
        #[cfg(windows)]
        {
            if let Ok(f) = std::fs::OpenOptions::new().read(true).write(true).open(format!(r"\\.\pipe\discord-ipc-{}", i)) {
                if let Ok(r) = f.try_clone() {
                    return Some((Box::new(r), Box::new(f)));
                }
            }
        }
        #[cfg(unix)]
        {
            let base = std::env::var("XDG_RUNTIME_DIR").or_else(|_| std::env::var("TMPDIR")).unwrap_or_else(|_| "/tmp".into());
            if let Ok(s) = std::os::unix::net::UnixStream::connect(format!("{}/discord-ipc-{}", base, i)) {
                if let Ok(r) = s.try_clone() {
                    return Some((Box::new(r), Box::new(s)));
                }
            }
        }
        let _ = i;
    }
    None
}

fn write_frame(w: &mut Writer, op: u32, v: &Value) -> std::io::Result<()> {
    let body = serde_json::to_vec(v).unwrap_or_default();
    let mut buf = Vec::with_capacity(8 + body.len());
    buf.extend_from_slice(&op.to_le_bytes());
    buf.extend_from_slice(&(body.len() as u32).to_le_bytes());
    buf.extend_from_slice(&body);
    w.write_all(&buf)?;
    w.flush()
}

fn read_frame(r: &mut Reader) -> std::io::Result<(u32, Value)> {
    let mut h = [0u8; 8];
    r.read_exact(&mut h)?;
    let op = u32::from_le_bytes([h[0], h[1], h[2], h[3]]);
    let n = u32::from_le_bytes([h[4], h[5], h[6], h[7]]) as usize;
    if n > 1 << 20 {
        return Err(std::io::Error::other("frame too big"));
    }
    let mut b = vec![0u8; n];
    r.read_exact(&mut b)?;
    Ok((op, serde_json::from_slice(&b).unwrap_or(Value::Null)))
}

fn nonce() -> String {
    static N: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    format!("{:x}-{}", now_ms(), N.fetch_add(1, Ordering::SeqCst))
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

/// Join secret: "track uri;position ms;time it was taken". Fits Discord's 128-character limit.
fn join_secret(p: &Play) -> Option<String> {
    let t = p.track.as_ref()?;
    Some(format!("{};{};{}", t.uri, p.position(now_ms()) as i64, now_ms()))
}

/// `level` = how careful to be: if Discord refuses an activity, the next one leaves out the newer
/// extras (0: everything, 1: no Join, 2: no links, 3: bare minimum with the SpoX icon only).
fn activity(p: &Play, link: &str, party: Option<&str>, level: u8) -> Option<Value> {
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
    if level >= 2 {
        let o = a.as_object_mut().unwrap();
        o.remove("status_display_type");
        o.remove("details_url");
        if let Some(asr) = o.get_mut("assets").and_then(|x| x.as_object_mut()) {
            asr.remove("large_url");
            asr.remove("small_url");
            if level >= 3 {
                asr.insert("large_image".into(), json!("spox"));
            }
        }
    }
    // "Listen along" for everyone: one click opens the same song in Spotify
    let mut buttons = vec![json!({ "label": "Listen on Spotify", "url": song })];
    if link.starts_with("https://") {
        buttons.push(json!({ "label": "Get SpoX", "url": link }));
    }
    if level < 3 {
        a["buttons"] = Value::Array(buttons);
    }
    if p.status != "paused" && t.duration_ms > 0 {
        let start = now_ms() - p.position(now_ms()) as i64;
        a["timestamps"] = json!({ "start": start, "end": start + t.duration_ms as i64 });
    }
    // Join (friends with SpoX): party + join secret
    if let (Some(id), Some(secret), true) = (party, join_secret(p), level == 0) {
        if p.status != "paused" {
            a["party"] = json!({ "id": id, "size": [1, 10] });
            a["secrets"] = json!({ "join": secret });
        }
    }
    Some(a)
}

/// Tells Windows how Discord can start SpoX when someone clicks Join while SpoX is closed.
fn register_launcher() {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let Ok(exe) = std::env::current_exe() else { return };
        let key = format!(r"HKCU\Software\Classes\discord-{}", APP_ID);
        let exe = exe.display().to_string();
        let cmd = format!("\"{}\"", exe);
        for args in [
            vec!["add".to_string(), key.clone(), "/ve".into(), "/d".into(), format!("URL:Run game {} protocol", APP_ID), "/f".into()],
            vec!["add".to_string(), key.clone(), "/v".into(), "URL Protocol".into(), "/d".into(), String::new(), "/f".into()],
            vec!["add".to_string(), format!(r"{}\DefaultIcon", key), "/ve".into(), "/d".into(), exe.clone(), "/f".into()],
            vec!["add".to_string(), format!(r"{}\shell\open\command", key), "/ve".into(), "/d".into(), cmd.clone(), "/f".into()],
        ] {
            let _ = std::process::Command::new("reg").args(&args).creation_flags(0x0800_0000).status();
        }
    }
}

/// A friend clicked Join on your profile: play the same song from the same moment.
fn join(solo: &Sender<String>, secret: &str) {
    let mut it = secret.split(';');
    let (Some(uri), Some(pos), Some(at)) = (it.next(), it.next().and_then(|x| x.parse::<i64>().ok()), it.next().and_then(|x| x.parse::<i64>().ok())) else { return };
    if !uri.starts_with("spotify:") {
        return;
    }
    let _ = solo.send(command("activate"));
    let _ = solo.send(command_with("play", "uri", json!(uri)));
    let solo = solo.clone();
    let at = at.max(now_ms() - 30 * 60_000);
    std::thread::spawn(move || {
        // let the song start, then jump to where the friend is now
        std::thread::sleep(Duration::from_millis(1200));
        let target = pos + (now_ms() - at) + 300;
        let _ = solo.send(command_with("seek", "position_ms", json!(target.max(0))));
    });
}

/// One live connection to the Discord app.
struct Conn {
    w: Writer,
    alive: Arc<AtomicBool>,
    /// replies the reader wants written (accepting "Ask to join")
    out: Receiver<Value>,
}

fn open(cfg: &Shared, solo: &Sender<String>, no_join: &Arc<AtomicU8>) -> Option<Conn> {
    let Some((mut r, mut w)) = connect() else {
        log("Discord not running (no pipe)");
        return None;
    };
    write_frame(&mut w, 0, &json!({"v": 1, "client_id": APP_ID})).ok()?;
    // first reply is READY (or a close with a reason)
    match read_frame(&mut r) {
        Ok((op, _)) if op != 2 => log("connected"),
        other => {
            log(&format!("handshake refused: {:?}", other.map(|x| x.1)));
            return None;
        }
    }
    for evt in ["ACTIVITY_JOIN", "ACTIVITY_JOIN_REQUEST"] {
        let _ = write_frame(&mut w, 1, &json!({"cmd": "SUBSCRIBE", "evt": evt, "nonce": nonce()}));
    }
    let alive = Arc::new(AtomicBool::new(true));
    let (tx, out) = std::sync::mpsc::channel::<Value>();
    let (alive2, no_join2, solo2, cfg2) = (alive.clone(), no_join.clone(), solo.clone(), cfg.clone());
    // reader: replies + events (someone joins / asks to join)
    std::thread::spawn(move || {
        while let Ok((op, v)) = read_frame(&mut r) {
            if op == 2 {
                break;
            }
            match v["evt"].as_str() {
                Some("ACTIVITY_JOIN") => {
                    if let Some(sec) = v["data"]["secret"].as_str() {
                        join(&solo2, sec);
                    }
                }
                Some("ACTIVITY_JOIN_REQUEST") => {
                    // "Ask to join": say yes right away (it's just music)
                    if cfg2.lock().unwrap().discord_join {
                        if let Some(uid) = v["data"]["user"]["id"].as_str() {
                            let _ = tx.send(json!({"cmd": "SEND_ACTIVITY_JOIN_INVITE", "args": {"user_id": uid}, "nonce": nonce()}));
                        }
                    }
                }
                Some("ERROR") if v["cmd"] == "SET_ACTIVITY" => {
                    // refused: try again with fewer extras, and write down why (cache\\discord.log)
                    let l = no_join2.fetch_add(1, Ordering::SeqCst);
                    log(&format!("level {} refused: {}", l, v["data"]));
                }
                _ if v["cmd"] == "SET_ACTIVITY" => log("activity set"),
                _ => {}
            }
        }
        alive2.store(false, Ordering::SeqCst);
    });
    Some(Conn { w, alive, out })
}

/// Background thread: keeps the presence in sync with what's playing and handles Join.
pub fn start(cfg: Shared, live: Arc<Mutex<Play>>, solo: Sender<String>) {
    std::thread::Builder::new()
        .name("discord".into())
        .spawn(move || {
            let party_id = format!("spox-{}-{}", std::process::id(), now_ms());
            let mut conn: Option<Conn> = None;
            // Discord refused our activity with Join in it: keep going without Join
            let no_join = Arc::new(AtomicU8::new(0));
            let mut tried_level = 0u8;
            let mut last_try = Instant::now() - Duration::from_secs(60);
            let mut last_sent: Option<(String, String, i64, bool)> = None; // uri, status, start time, joinable
            let mut last_send_at = Instant::now() - Duration::from_secs(60);
            let mut registered = false;
            loop {
                std::thread::sleep(Duration::from_millis(1000));
                let (on, allow_join) = {
                    let c = cfg.lock().unwrap();
                    (c.discord, c.discord_join)
                };
                if !on || APP_ID.is_empty() {
                    if let Some(mut c) = conn.take() {
                        let _ = write_frame(&mut c.w, 1, &json!({"cmd": "SET_ACTIVITY", "args": {"pid": std::process::id(), "activity": null}, "nonce": nonce()}));
                    }
                    last_sent = None;
                    continue;
                }
                if !registered {
                    registered = true;
                    register_launcher();
                }
                if conn.as_ref().map(|c| !c.alive.load(Ordering::SeqCst)).unwrap_or(false) {
                    conn = None; // Discord closed: try again later
                }
                if conn.is_none() {
                    if last_try.elapsed() < Duration::from_secs(15) {
                        continue;
                    }
                    last_try = Instant::now();
                    conn = open(&cfg, &solo, &no_join);
                    last_sent = None;
                    if conn.is_none() {
                        continue;
                    }
                }
                let c = conn.as_mut().unwrap();
                while let Ok(m) = c.out.try_recv() {
                    let _ = write_frame(&mut c.w, 1, &m);
                }
                let play = live.lock().unwrap().clone();
                let level = no_join.load(Ordering::SeqCst).min(3);
                let joinable = allow_join && level == 0;
                let key = play.track.as_ref().map(|t| {
                    let start = if play.status == "paused" { 0 } else { now_ms() - play.position(now_ms()) as i64 };
                    (t.uri.clone(), play.status.clone(), start, joinable)
                });
                // only resend on a real change (song, play/pause, a seek of more than 3 s, Join on/off)
                let mut changed = match (&key, &last_sent) {
                    (Some(k), Some(l)) => k.0 != l.0 || k.1 != l.1 || (k.2 - l.2).abs() > 3000 || k.3 != l.3,
                    (None, None) => false,
                    _ => true,
                };
                if level != tried_level {
                    tried_level = level;
                    changed = true;
                }
                // Discord allows about 5 updates per 20 s
                if !changed || last_send_at.elapsed() < Duration::from_secs(4) {
                    continue;
                }
                let act = activity(&play, LINK, joinable.then_some(party_id.as_str()), level);
                let msg = json!({"cmd": "SET_ACTIVITY", "args": {"pid": std::process::id(), "activity": act}, "nonce": nonce()});
                if write_frame(&mut c.w, 1, &msg).is_ok() {
                    last_sent = key;
                    last_send_at = Instant::now();
                } else {
                    conn = None;
                }
            }
        })
        .ok();
}

/// Small log next to the cache, to see why Discord refuses something.
fn log(line: &str) {
    use std::io::Write as _;
    let p = crate::config::cache_dir().join("discord.log");
    if std::fs::metadata(&p).map(|m| m.len() > 64 * 1024).unwrap_or(false) {
        let _ = std::fs::remove_file(&p);
    }
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(p) {
        let _ = writeln!(f, "{} {}", now_ms(), line);
    }
}

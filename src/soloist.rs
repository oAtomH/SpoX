//! Runs Spotify Soloist in the background and talks to it over its local WebSocket API.
//! Soloist is left running when SpoX closes, so the next launch connects instantly.

use crate::config::{self, now_secs, Shared};
use crate::net;
use crate::state::Ev;
use eframe::egui;
use std::io::Read;
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{Receiver, Sender};
use std::time::{Duration, Instant};
use tungstenite::Message;

const DOWNLOAD_URL: &str = "https://soloist-builds.spotifycdn.com/soloist_release_windows_x86_64.zip";
const UPDATE_AFTER_DAYS: i64 = 45; // builds expire 90 days after their build date
const FORCE_AFTER_DAYS: i64 = 75;

fn exe_name() -> &'static str {
    if cfg!(windows) { "soloist.exe" } else { "soloist" }
}
fn bin_path() -> PathBuf {
    config::soloist_dir().join(exe_name())
}
fn new_bin_path() -> PathBuf {
    config::soloist_dir().join(format!("{}.new", exe_name()))
}
fn data_dir() -> PathBuf {
    config::soloist_dir().join("data")
}
fn cache_dir() -> PathBuf {
    config::soloist_dir().join("cache")
}

pub struct Ctl {
    pub cmd: Sender<String>,
    /// Copies of every Soloist event (media keys, smart shuffle).
    pub tap: Taps,
    /// Always-current playback state, kept by the Soloist thread itself (works while the window is hidden).
    pub live: std::sync::Arc<std::sync::Mutex<crate::state::Play>>,
}

pub type Taps = std::sync::Arc<std::sync::Mutex<Vec<Sender<serde_json::Value>>>>;

pub fn command(name: &str) -> String {
    format!(r#"{{"type":"command","command":"{}"}}"#, name)
}
pub fn command_with(name: &str, key: &str, val: serde_json::Value) -> String {
    let mut m = serde_json::Map::new();
    m.insert("type".into(), "command".into());
    m.insert("command".into(), name.into());
    m.insert(key.into(), val);
    serde_json::Value::Object(m).to_string()
}

pub fn start(cfg: Shared, ev: Sender<Ev>, ctx: egui::Context) -> Ctl {
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    let tap: Taps = Default::default();
    let live: std::sync::Arc<std::sync::Mutex<crate::state::Play>> = Default::default();
    let (tap2, live2) = (tap.clone(), live.clone());
    std::thread::Builder::new()
        .name("soloist".into())
        .spawn(move || run(cfg, ev, ctx, rx, tap2, live2))
        .ok();
    Ctl { cmd: tx, tap, live }
}

fn status(ev: &Sender<Ev>, ctx: &egui::Context, s: impl Into<String>) {
    let _ = ev.send(Ev::Status(s.into()));
    ctx.request_repaint();
}

fn read_port() -> Option<u16> {
    let s = std::fs::read_to_string(data_dir().join("ws.port")).ok()?;
    s.trim().parse().ok()
}
fn read_addr() -> String {
    std::fs::read_to_string(data_dir().join("ws.addr"))
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "127.0.0.1".into())
}

type Ws = tungstenite::WebSocket<TcpStream>;

fn try_connect() -> Option<Ws> {
    let port = read_port()?;
    let mut host = read_addr();
    // ws.addr may already contain the port
    if let Some((h, _)) = host.rsplit_once(':') {
        if !h.contains(':') || h.starts_with('[') {
            host = h.to_string();
        }
    }
    if host == "0.0.0.0" {
        host = "127.0.0.1".into();
    }
    let addr: SocketAddr = format!("{}:{}", host, port).parse().ok()?;
    let stream = TcpStream::connect_timeout(&addr, Duration::from_millis(400)).ok()?;
    stream.set_nodelay(true).ok();
    let (ws, _) = tungstenite::client(format!("ws://{}:{}/", host, port), stream).ok()?;
    ws.get_ref().set_read_timeout(Some(Duration::from_millis(40))).ok();
    Some(ws)
}

fn download(ev: &Sender<Ev>, ctx: &egui::Context, to: &Path) -> Result<(), String> {
    status(ev, ctx, "DOWNLOADING SOLOIST...");
    let resp = net::agent().get(DOWNLOAD_URL).call().map_err(|e| e.to_string())?;
    let total: usize = resp.header("Content-Length").and_then(|s| s.parse().ok()).unwrap_or(0);
    let mut buf = Vec::with_capacity(total.max(1 << 20));
    let mut r = resp.into_reader();
    let mut chunk = [0u8; 64 * 1024];
    let mut last = Instant::now();
    loop {
        let n = r.read(&mut chunk).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&chunk[..n]);
        if total > 0 && last.elapsed() > Duration::from_millis(200) {
            last = Instant::now();
            status(ev, ctx, format!("DOWNLOADING SOLOIST {}%", buf.len() * 100 / total));
        }
    }
    let mut zip = zip::ZipArchive::new(std::io::Cursor::new(buf)).map_err(|e| e.to_string())?;
    for i in 0..zip.len() {
        let mut f = zip.by_index(i).map_err(|e| e.to_string())?;
        let name = f.name().replace('\\', "/");
        if name.rsplit('/').next() == Some(exe_name()) {
            let mut out = Vec::new();
            f.read_to_end(&mut out).map_err(|e| e.to_string())?;
            std::fs::create_dir_all(config::soloist_dir()).map_err(|e| e.to_string())?;
            std::fs::write(to, out).map_err(|e| e.to_string())?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let _ = std::fs::set_permissions(to, std::fs::Permissions::from_mode(0o755));
            }
            return Ok(());
        }
    }
    Err("soloist.exe not found in download".into())
}

fn kill_running() {
    if let Ok(pid) = std::fs::read_to_string(data_dir().join("soloist.pid")) {
        let pid = pid.trim().to_string();
        if pid.is_empty() {
            return;
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            let _ = std::process::Command::new("taskkill")
                .args(["/PID", &pid, "/F"])
                .creation_flags(0x0800_0000)
                .status();
        }
        #[cfg(not(windows))]
        {
            let _ = std::process::Command::new("kill").arg(&pid).status();
        }
        std::thread::sleep(Duration::from_millis(300));
    }
}

/// Stops Soloist (used by "Quit all").
pub fn stop_soloist() {
    kill_running();
}

fn spawn(cfg: &Shared) -> Option<std::process::Child> {
    // Swap in a pre-downloaded update before starting.
    if new_bin_path().exists() {
        let _ = std::fs::rename(new_bin_path(), bin_path());
    }
    let (key, name) = {
        let c = cfg.lock().unwrap();
        (c.soloist_key.clone(), c.device_name.clone())
    };
    let _ = std::fs::create_dir_all(data_dir());
    let _ = std::fs::create_dir_all(cache_dir());
    let _ = std::fs::remove_file(data_dir().join("ws.port"));
    let log = std::fs::File::create(config::soloist_dir().join("soloist.log")).ok();
    let mut cmd = std::process::Command::new(bin_path());
    cmd.arg("--device-name")
        .arg(if name.is_empty() { "SpoX".to_string() } else { name })
        .arg("--api-key")
        .arg(key)
        .arg("--data-dir")
        .arg(data_dir())
        .arg("--cache-dir")
        .arg(cache_dir())
        .arg("--ws")
        .arg("127.0.0.1:0")
        .current_dir(config::soloist_dir())
        .stdin(std::process::Stdio::null());
    if let Some(l) = log {
        if let Ok(l2) = l.try_clone() {
            cmd.stdout(l).stderr(l2);
        }
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // CREATE_NO_WINDOW: no console. Stays a child of SpoX, so Task Manager lists it under SpoX.
        cmd.creation_flags(0x0800_0000);
    }
    cmd.spawn().ok()
}

fn run(cfg: Shared, ev: Sender<Ev>, ctx: egui::Context, rx: Receiver<String>, tap: Taps, live: std::sync::Arc<std::sync::Mutex<crate::state::Play>>) {
    let mut playing = false;
    let mut failures = 0u32;
    loop {
        if cfg.lock().unwrap().soloist_key.trim().is_empty() {
            status(&ev, &ctx, "ADD YOUR SOLOIST KEY (MENU > SETUP)");
            // wait for a key to appear
            std::thread::sleep(Duration::from_millis(500));
            while rx.try_recv().is_ok() {}
            continue;
        }

        // 1. Already running? Connect straight away (the fast path).
        let mut ws = try_connect();

        // 2. Otherwise make sure we have a binary, then start it.
        if ws.is_none() {
            if !bin_path().exists() {
                match download(&ev, &ctx, &bin_path()) {
                    Ok(()) => {
                        let mut c = cfg.lock().unwrap();
                        c.soloist_downloaded = now_secs();
                        c.save();
                    }
                    Err(e) => {
                        status(&ev, &ctx, format!("SOLOIST DOWNLOAD FAILED: {}", e));
                        std::thread::sleep(Duration::from_secs(10));
                        continue;
                    }
                }
            } else if new_bin_path().exists() {
                let mut c = cfg.lock().unwrap();
                c.soloist_downloaded = now_secs();
                c.save();
            }
            status(&ev, &ctx, "STARTING SOLOIST...");
            let mut child = spawn(&cfg);
            let t0 = Instant::now();
            while ws.is_none() && t0.elapsed() < Duration::from_secs(20) {
                std::thread::sleep(Duration::from_millis(60));
                if let Some(c) = child.as_mut() {
                    if let Ok(Some(code)) = c.try_wait() {
                        if code.code() == Some(10) {
                            // build expired: fetch a new one
                            let _ = std::fs::remove_file(bin_path());
                            status(&ev, &ctx, "SOLOIST EXPIRED, UPDATING...");
                        } else {
                            status(&ev, &ctx, "SOLOIST EXITED (CHECK YOUR KEY)");
                        }
                        break;
                    }
                }
                ws = try_connect();
            }
        }

        let Some(mut ws) = ws else {
            failures += 1;
            std::thread::sleep(Duration::from_secs((2 * failures).min(15) as u64));
            continue;
        };
        failures = 0;
        let _ = ev.send(Ev::SoloistConnected(true));
        status(&ev, &ctx, "");
        let _ = ws.send(Message::text(command("get_state")));

        // Background update check (download now, swap on next start).
        let age_days = {
            let mut c = cfg.lock().unwrap();
            if c.soloist_downloaded == 0 {
                c.soloist_downloaded = now_secs();
                c.save();
            }
            (now_secs() - c.soloist_downloaded) / 86400
        };
        if cfg!(windows) && age_days >= UPDATE_AFTER_DAYS && !new_bin_path().exists() {
            let ev2 = ev.clone();
            let ctx2 = ctx.clone();
            std::thread::spawn(move || {
                let _ = download(&ev2, &ctx2, &new_bin_path());
                status(&ev2, &ctx2, "");
            });
        }
        let force_restart_at = if age_days >= FORCE_AFTER_DAYS { Some(Instant::now() + Duration::from_secs(60)) } else { None };

        // 3. Pump messages.
        'conn: loop {
            while let Ok(cmd) = rx.try_recv() {
                if cmd == "__restart__" {
                    kill_running();
                    break 'conn;
                }
                let cmd = if cmd == "__toggle__" { command(if playing { "pause" } else { "play" }) } else { cmd };
                if ws.send(Message::text(cmd)).is_err() {
                    break 'conn;
                }
            }
            match ws.read() {
                Ok(Message::Text(t)) => {
                    if let Ok(v) = serde_json::from_str::<serde_json::Value>(t.as_str()) {
                        if let Some(st) = v.get("status").and_then(|x| x.as_str()) {
                            playing = st == "playing" || st == "buffering";
                        }
                        live.lock().unwrap().apply(&v);
                        tap.lock().unwrap().retain(|t| t.send(v.clone()).is_ok());
                        ctx.request_repaint_of(crate::tb::id());
                        let _ = ev.send(Ev::SoloistMsg(v));
                        ctx.request_repaint();
                    }
                }
                Ok(Message::Close(_)) => break 'conn,
                Ok(_) => {}
                Err(tungstenite::Error::Io(e))
                    if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {}
                Err(_) => break 'conn,
            }
            if let Some(t) = force_restart_at {
                if Instant::now() > t && new_bin_path().exists() {
                    kill_running();
                    break 'conn;
                }
            }
        }
        let _ = ev.send(Ev::SoloistConnected(false));
        status(&ev, &ctx, "RECONNECTING...");
        std::thread::sleep(Duration::from_millis(300));
    }
}

/// Soloist's process id (from the pid file it writes), if it's running.
pub fn pid() -> Option<u32> {
    std::fs::read_to_string(data_dir().join("soloist.pid")).ok()?.trim().parse().ok()
}

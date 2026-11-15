//! Auto-update from GitHub releases: checks once at start (and every 6 hours), downloads the new
//! SpoX.exe next to the old one and swaps them. Windows lets a running exe be renamed, so the swap
//! is safe; the new version starts the next time SpoX opens (or right away from the menu).

use crate::config::exe_dir;
use crate::state::Ev;
use std::sync::mpsc::Sender;
use std::time::Duration;

const API: &str = "https://api.github.com/repos/oAtomH/SpoX/releases/latest";

fn ver(s: &str) -> Vec<u32> {
    s.trim_start_matches('v').split(['.', '-']).map(|p| p.parse().unwrap_or(0)).collect()
}

/// Leftover from the last update.
pub fn cleanup() {
    let _ = std::fs::remove_file(exe_dir().join("SpoX.exe.old"));
    let _ = std::fs::remove_file(exe_dir().join("SpoX.exe.new"));
}

/// Our own build folder (Cargo.toml next to the exe) never updates itself.
fn allowed() -> bool {
    !exe_dir().join("Cargo.toml").exists()
}

fn check_once(ev: &Sender<Ev>) -> Option<String> {
    let r = crate::net::agent().get(API).set("Accept", "application/vnd.github+json").call().ok()?;
    let j: serde_json::Value = r.into_json().ok()?;
    let tag = j["tag_name"].as_str()?.to_string();
    if ver(&tag) <= ver(env!("CARGO_PKG_VERSION")) {
        return None;
    }
    let url = j["assets"].as_array()?.iter().find(|a| a["name"].as_str() == Some("SpoX.exe"))?["browser_download_url"].as_str()?.to_string();
    let resp = crate::net::agent().get(&url).timeout(Duration::from_secs(120)).call().ok()?;
    let mut bytes = Vec::new();
    std::io::Read::read_to_end(&mut std::io::Read::take(resp.into_reader(), 64 << 20), &mut bytes).ok()?;
    // a real Windows program starts with "MZ"; anything else (error page) is ignored
    if bytes.len() < 1_000_000 || &bytes[..2] != b"MZ" {
        return None;
    }
    let me = std::env::current_exe().ok()?;
    let new = exe_dir().join("SpoX.exe.new");
    let old = exe_dir().join("SpoX.exe.old");
    std::fs::write(&new, &bytes).ok()?;
    let _ = std::fs::remove_file(&old);
    std::fs::rename(&me, &old).ok()?;
    if std::fs::rename(&new, &me).is_err() {
        let _ = std::fs::rename(&old, &me); // put the old one back
        return None;
    }
    let v = tag.trim_start_matches('v').to_string();
    let _ = ev.send(Ev::UpdateReady(v.clone()));
    Some(v)
}

pub fn start(cfg: crate::config::Shared, ev: Sender<Ev>) {
    cleanup();
    if !allowed() {
        return;
    }
    std::thread::Builder::new()
        .name("update".into())
        .spawn(move || {
            std::thread::sleep(Duration::from_secs(8));
            loop {
                if cfg.lock().unwrap().auto_update && check_once(&ev).is_some() {
                    return; // one update per run
                }
                std::thread::sleep(Duration::from_secs(6 * 3600));
            }
        })
        .ok();
}

/// Start the new version and quit this one.
pub fn restart() {
    if let Ok(me) = std::env::current_exe() {
        let _ = std::process::Command::new(me).arg("--after-install").current_dir(exe_dir()).spawn();
    }
}

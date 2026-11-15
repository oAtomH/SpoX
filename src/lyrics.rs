//! Synced lyrics from LRCLIB (lrclib.net), cached on disk. Never blocks the UI.

use crate::config::{self, now_secs};
use crate::net::{self, enc};
use crate::state::{Ev, Lyrics, Track};
use eframe::egui;
use serde_json::{json, Value};
use std::sync::mpsc::Sender;

fn cache_path(uri: &str) -> std::path::PathBuf {
    let id: String = uri.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
    config::cache_dir().join("lyrics").join(format!("{}.json", id))
}

pub fn parse_lrc(s: &str) -> Vec<(u64, String)> {
    let mut out = Vec::new();
    for line in s.lines() {
        let mut rest = line.trim();
        let mut stamps = Vec::new();
        while rest.starts_with('[') {
            let Some(end) = rest.find(']') else { break };
            let tag = &rest[1..end];
            rest = &rest[end + 1..];
            let mut it = tag.splitn(2, ':');
            let (Some(m), Some(sec)) = (it.next(), it.next()) else { continue };
            if let (Ok(m), Ok(sec)) = (m.trim().parse::<u64>(), sec.trim().parse::<f64>()) {
                stamps.push(m * 60_000 + (sec * 1000.0) as u64);
            }
        }
        let text = rest.trim().to_string();
        for t in stamps {
            out.push((t, text.clone()));
        }
    }
    out.sort_by_key(|x| x.0);
    out
}

fn from_record(v: &Value) -> Option<Lyrics> {
    if let Some(s) = v.get("syncedLyrics").and_then(|x| x.as_str()) {
        let lines = parse_lrc(s);
        if !lines.is_empty() {
            return Some(Lyrics { synced: true, lines });
        }
    }
    if let Some(s) = v.get("plainLyrics").and_then(|x| x.as_str()) {
        if !s.trim().is_empty() {
            return Some(Lyrics { synced: false, lines: s.lines().map(|l| (0, l.trim().to_string())).collect() });
        }
    }
    None
}

fn first_artist(a: &str) -> &str {
    a.split(", ").next().unwrap_or(a)
}

fn fetch(t: &Track) -> Option<Lyrics> {
    let a = net::agent();
    let artist = first_artist(&t.artists);
    let get = format!(
        "https://lrclib.net/api/get?artist_name={}&track_name={}&album_name={}&duration={}",
        enc(artist), enc(&t.name), enc(&t.album), t.duration_ms / 1000
    );
    if let Ok(r) = a.get(&get).call() {
        if let Ok(v) = r.into_json::<Value>() {
            if let Some(l) = from_record(&v) {
                return Some(l);
            }
        }
    }
    let search = format!("https://lrclib.net/api/search?artist_name={}&track_name={}", enc(artist), enc(&t.name));
    let list: Value = a.get(&search).call().ok()?.into_json().ok()?;
    let arr = list.as_array()?;
    let dur = t.duration_ms as f64 / 1000.0;
    let close = |v: &&Value| v.get("duration").and_then(|d| d.as_f64()).map(|d| (d - dur).abs() < 4.0).unwrap_or(true);
    arr.iter()
        .filter(close)
        .find(|v| v.get("syncedLyrics").map(|s| s.is_string()).unwrap_or(false))
        .or_else(|| arr.iter().find(close))
        .and_then(from_record)
}

pub fn request(t: Track, ev: Sender<Ev>, ctx: egui::Context) {
    std::thread::spawn(move || {
        let path = cache_path(&t.uri);
        if let Ok(b) = std::fs::read(&path) {
            if let Ok(v) = serde_json::from_slice::<Value>(&b) {
                let fresh_none = v.get("none").and_then(|x| x.as_i64()).map(|ts| now_secs() - ts < 3 * 86400);
                let lyr = if fresh_none == Some(true) {
                    Some(None)
                } else if v.get("none").is_none() {
                    let synced = v.get("synced").and_then(|x| x.as_bool()).unwrap_or(false);
                    let lines = v
                        .get("lines")
                        .and_then(|x| x.as_array())
                        .map(|a| {
                            a.iter()
                                .filter_map(|e| Some((e.get(0)?.as_u64()?, e.get(1)?.as_str()?.to_string())))
                                .collect()
                        })
                        .unwrap_or_default();
                    Some(Some(Lyrics { synced, lines }))
                } else {
                    None
                };
                if let Some(l) = lyr {
                    let _ = ev.send(Ev::Lyrics { uri: t.uri.clone(), lyrics: l });
                    ctx.request_repaint();
                    return;
                }
            }
        }
        let res = fetch(&t);
        let _ = std::fs::create_dir_all(path.parent().unwrap());
        let doc = match &res {
            Some(l) => json!({"synced": l.synced, "lines": l.lines.iter().map(|(t, s)| json!([t, s])).collect::<Vec<_>>()}),
            None => json!({"none": now_secs()}),
        };
        let _ = std::fs::write(&path, doc.to_string());
        let _ = ev.send(Ev::Lyrics { uri: t.uri.clone(), lyrics: res });
        ctx.request_repaint();
    });
}

#[cfg(test)]
mod tests {
    #[test]
    fn lrc() {
        let l = super::parse_lrc("[00:12.50]Hello\n[01:02.00][01:30.10]Chorus\nno tag\n[ti:Title]");
        assert_eq!(l, vec![(12500, "Hello".into()), (62000, "Chorus".into()), (90100, "Chorus".into())]);
    }
}

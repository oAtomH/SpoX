//! Spotify Web API: only used to list your playlists / liked songs and to start a track inside a playlist.
//! Sign-in is OAuth PKCE with your own Client ID (no client secret is stored).

use crate::config::{self, now_secs, Shared};
use crate::net::{self, enc};
use crate::state::{Ev, Playlist, Track};
use base64::Engine;
use eframe::egui;
use serde_json::{json, Value};
use sha2::Digest;
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

/// Bumped on every sign-in attempt; an older attempt that is still waiting gives up.
static SIGNIN_GEN: AtomicU64 = AtomicU64::new(0);

pub const REDIRECT_PORT: u16 = 43821;
pub const REDIRECT_URI: &str = "http://127.0.0.1:43821/callback";
const SCOPES: &str = "playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public user-library-read user-library-modify user-read-private user-read-playback-state user-modify-playback-state";
/// Scopes added after the first version: if the saved sign-in lacks these, likes/adding ask you to sign in again.
pub const NEW_SCOPES: &[&str] = &["user-library-modify", "playlist-modify-private"];
const API: &str = "https://api.spotify.com/v1";

pub enum Req {
    SignIn,
    LoadPlaylists,
    LoadTracks(Playlist),
    /// Play `track` inside `context` (a playlist uri, or the liked-songs pseudo uri) on our Soloist device.
    PlayIn { context: String, track: String, device_name: String, fallback_uris: Vec<String> },
    LoadMe,
    LoadTracksQuiet(Playlist),
    CheckLiked(String),
    SetLiked(String, bool),
    AddTo { playlist: Playlist, uri: String },
    RemoveFrom { playlist: Playlist, uri: String, key: String },
}

pub fn start(cfg: Shared, ev: Sender<Ev>, ctx: egui::Context) -> Sender<Req> {
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::Builder::new().name("webapi".into()).spawn(move || run(cfg, ev, ctx, rx)).ok();
    tx
}

pub fn liked_uri(user_id: &str) -> String {
    format!("spotify:user:{}:collection", user_id)
}

fn b64(b: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b)
}

#[derive(Clone)]
pub struct Api {
    cfg: Shared,
    ev: Sender<Ev>,
    ctx: egui::Context,
}

impl Api {
    pub fn new(cfg: Shared, ev: Sender<Ev>, ctx: egui::Context) -> Self {
        Self { cfg, ev, ctx }
    }

    /// Finds one Spotify track for an artist + title (used by smart shuffle).
    pub fn search_track(&self, artist: &str, title: &str) -> Option<Track> {
        let q = format!("track:{} artist:{}", title, artist);
        let v = self.get_bg(&format!("/search?type=track&limit=3&q={}", enc(&q)))?;
        let items = v.pointer("/tracks/items")?.as_array()?;
        items.iter().find_map(Self::parse_track)
    }

    /// Songs by an artist (for smart shuffle without a Last.fm key), shuffled a bit by the search offset.
    /// Songs by an artist, for new songs. Each artist is asked once every two weeks (kept on
    /// disk), and at most 8 searches go out per 10 minutes: discovery never eats the request
    /// budget SpoX (on PC and phone, same Spotify app) needs for playing, likes and lists.
    pub fn search_artist(&self, artist: &str) -> Vec<Track> {
        if artist.is_empty() {
            return Vec::new();
        }
        let k = artist.to_lowercase();
        let now = crate::config::now_ms();
        if let Some((at, ts)) = artist_cache().lock().unwrap().get(&k) {
            if now - at < 14 * 86_400_000 {
                return ts.clone();
            }
        }
        {
            let mut b = SEARCHES.lock().unwrap();
            b.retain(|t| now - t < 600_000);
            if b.len() >= 8 {
                return Vec::new();
            }
            b.push(now);
        }
        let q = format!("artist:\"{}\"", artist);
        let Some(v) = self.get_bg(&format!("/search?type=track&limit=10&q={}", enc(&q))) else { return Vec::new() };
        let out: Vec<Track> = v.pointer("/tracks/items").and_then(|x| x.as_array()).map(|a| a.iter().filter_map(Self::parse_track).collect()).unwrap_or_default();
        let mut c = artist_cache().lock().unwrap();
        c.insert(k, (now, out.clone()));
        let v: serde_json::Map<String, Value> = c
            .iter()
            .map(|(a, (t, ts))| (a.clone(), json!([t, ts.iter().map(|x| json!([x.uri, x.name, x.artists])).collect::<Vec<_>>()])))
            .collect();
        let _ = std::fs::write(crate::config::config_dir().join("artists.json"), Value::Object(v).to_string());
        out
    }

    /// Your last 50 played songs (Spotify keeps this list; that's all its API gives).
    pub fn recent(&self) -> Vec<Track> {
        let Ok(v) = self.get("/me/player/recently-played?limit=50") else { return Vec::new() };
        v.get("items").and_then(|x| x.as_array()).map(|a| a.iter().filter_map(|e| e.get("track").and_then(Self::parse_track)).collect()).unwrap_or_default()
    }

    /// Every song in Liked Songs (paged, capped at 5000).
    pub fn liked_all(&self) -> Vec<Track> {
        let mut out = Vec::new();
        let mut next = Some(format!("{}/me/tracks?limit=50", API));
        while let Some(url) = next.take() {
            let Ok(v) = self.get(&url) else { break };
            for e in v.get("items").and_then(|x| x.as_array()).cloned().unwrap_or_default() {
                let t = e.get("item").or_else(|| e.get("track")).cloned().unwrap_or(Value::Null);
                if let Some(t) = Self::parse_track(&t) {
                    out.push(t);
                }
            }
            next = v.get("next").and_then(|x| x.as_str()).map(|s| s.to_string());
            if out.len() >= 5000 {
                break;
            }
        }
        out
    }

    fn say(&self, s: &str) {
        let _ = self.ev.send(Ev::Status(s.into()));
        self.ctx.request_repaint();
    }

    fn sign_in(&self) -> Result<(), String> {
        let client_id = self.cfg.lock().unwrap().client_id.trim().to_string();
        if client_id.is_empty() {
            return Err("no client id".into());
        }
        let mut raw = [0u8; 48];
        getrandom::getrandom(&mut raw).map_err(|e| e.to_string())?;
        let verifier = b64(&raw);
        let challenge = b64(&sha2::Sha256::digest(verifier.as_bytes()));
        let mut st = [0u8; 12];
        getrandom::getrandom(&mut st).map_err(|e| e.to_string())?;
        let state = b64(&st);
        let my_gen = SIGNIN_GEN.fetch_add(1, Ordering::SeqCst) + 1;
        // An older attempt may still hold the port for a moment while it notices it was replaced.
        let t_bind = Instant::now();
        let listener = loop {
            match TcpListener::bind(("127.0.0.1", REDIRECT_PORT)) {
                Ok(l) => break l,
                Err(e) if t_bind.elapsed() > Duration::from_secs(2) => return Err(format!("port {} busy: {}", REDIRECT_PORT, e)),
                Err(_) => std::thread::sleep(Duration::from_millis(100)),
            }
        };
        listener.set_nonblocking(true).ok();
        let url = format!(
            "https://accounts.spotify.com/authorize?client_id={}&response_type=code&redirect_uri={}&code_challenge_method=S256&code_challenge={}&scope={}&state={}",
            enc(&client_id), enc(REDIRECT_URI), challenge, enc(SCOPES), state
        );
        net::open_url(&url);
        self.say("APPROVE SPOX IN YOUR BROWSER...");
        let t0 = Instant::now();
        let code = loop {
            if SIGNIN_GEN.load(Ordering::SeqCst) != my_gen {
                return Err("replaced by a newer sign-in".into());
            }
            if t0.elapsed() > Duration::from_secs(300) {
                return Err("sign-in timed out".into());
            }
            match listener.accept() {
                Ok((mut s, _)) => {
                    s.set_nonblocking(false).ok();
                    s.set_read_timeout(Some(Duration::from_secs(3))).ok();
                    let mut line = String::new();
                    let _ = BufReader::new(&s).read_line(&mut line);
                    let path = line.split_whitespace().nth(1).unwrap_or("").to_string();
                    if !path.starts_with("/callback") {
                        let _ = s.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                        continue;
                    }
                    let q = path.split_once('?').map(|x| x.1).unwrap_or("");
                    let get = |k: &str| {
                        q.split('&').find_map(|kv| kv.split_once('=').filter(|(a, _)| *a == k).map(|(_, b)| b.to_string()))
                    };
                    let ok = get("state").as_deref() == Some(state.as_str()) && get("code").is_some();
                    let body = if ok {
                        "<html><body style='background:#0b0c0d;color:#5be38e;font-family:monospace;padding:40px'>SpoX is signed in. You can close this tab.</body></html>"
                    } else {
                        "<html><body style='background:#0b0c0d;color:#e9f27a;font-family:monospace;padding:40px'>Sign-in was cancelled or failed. Try again from SpoX.</body></html>"
                    };
                    let _ = s.write_all(
                        format!("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", body.len(), body).as_bytes(),
                    );
                    if !ok {
                        return Err(get("error").unwrap_or_else(|| "cancelled".into()));
                    }
                    break get("code").unwrap();
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(100)),
                Err(e) => return Err(e.to_string()),
            }
        };
        let resp = net::agent()
            .post("https://accounts.spotify.com/api/token")
            .send_form(&[
                ("grant_type", "authorization_code"),
                ("code", &code),
                ("redirect_uri", REDIRECT_URI),
                ("client_id", &client_id),
                ("code_verifier", &verifier),
            ])
            .map_err(|e| e.to_string())?;
        self.store_tokens(resp.into_json().map_err(|e| e.to_string())?);
        let me = self.get("/me")?;
        let mut c = self.cfg.lock().unwrap();
        c.user_id = me.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
        c.save();
        Ok(())
    }

    fn store_tokens(&self, v: Value) {
        let mut c = self.cfg.lock().unwrap();
        if let Some(a) = v.get("access_token").and_then(|x| x.as_str()) {
            c.access_token = a.to_string();
        }
        if let Some(r) = v.get("refresh_token").and_then(|x| x.as_str()) {
            c.refresh_token = r.to_string();
        }
        c.token_expires = now_secs() + v.get("expires_in").and_then(|x| x.as_i64()).unwrap_or(3600) - 60;
        if let Some(sc) = v.get("scope").and_then(|x| x.as_str()) {
            c.scopes = sc.to_string();
        }
        c.save();
    }

    fn token(&self) -> Result<String, String> {
        let (tok, exp, refresh, client) = {
            let c = self.cfg.lock().unwrap();
            (c.access_token.clone(), c.token_expires, c.refresh_token.clone(), c.client_id.clone())
        };
        if !tok.is_empty() && now_secs() < exp {
            return Ok(tok);
        }
        if refresh.is_empty() || client.is_empty() {
            return Err("not signed in".into());
        }
        let resp = net::agent()
            .post("https://accounts.spotify.com/api/token")
            .send_form(&[("grant_type", "refresh_token"), ("refresh_token", &refresh), ("client_id", &client)]);
        match resp {
            Ok(r) => {
                self.store_tokens(r.into_json().map_err(|e| e.to_string())?);
                Ok(self.cfg.lock().unwrap().access_token.clone())
            }
            Err(ureq::Error::Status(400, _)) => {
                // refresh token revoked/expired (they last ~6 months): sign in again
                let mut c = self.cfg.lock().unwrap();
                c.refresh_token.clear();
                c.access_token.clear();
                c.save();
                let _ = self.ev.send(Ev::SignedIn(false));
                Err("signed out".into())
            }
            Err(e) => Err(e.to_string()),
        }
    }

    fn get(&self, path: &str) -> Result<Value, String> {
        let url = if path.starts_with("http") { path.to_string() } else { format!("{}{}", API, path) };
        for attempt in 0..3 {
            wait_cool(30);
            let tok = self.token()?;
            match net::agent().get(&url).set("Authorization", &format!("Bearer {}", tok)).call() {
                Ok(r) => return r.into_json().map_err(|e| e.to_string()),
                Err(ureq::Error::Status(429, r)) => limited(&r),
                Err(ureq::Error::Status(401, _)) if attempt == 0 => {
                    self.cfg.lock().unwrap().token_expires = 0;
                }
                Err(ureq::Error::Status(code, _)) => return Err(format!("http {}", code)),
                Err(e) => return Err(e.to_string()),
            }
        }
        Err("rate limited".into())
    }

    /// Background work (finding new songs): one request at a time, 1.5 s apart, and nothing
    /// while Spotify asks to slow down (it tries again later instead of blocking play / likes).
    fn get_bg(&self, path: &str) -> Option<Value> {
        let _one = BG_LOCK.lock().unwrap();
        if cooling() {
            return None;
        }
        let since = now_ms() - LAST_BG.load(Ordering::SeqCst);
        if since < 1500 {
            std::thread::sleep(Duration::from_millis((1500 - since) as u64));
        }
        LAST_BG.store(now_ms(), Ordering::SeqCst);
        let tok = self.token().ok()?;
        match net::agent().get(&format!("{}{}", API, path)).set("Authorization", &format!("Bearer {}", tok)).call() {
            Ok(r) => r.into_json().ok(),
            Err(ureq::Error::Status(429, r)) => {
                limited(&r);
                None
            }
            Err(_) => None,
        }
    }

    fn put(&self, path: &str, body: Value) -> Result<(), String> {
        self.send("PUT", path, Some(body))
    }

    fn send(&self, method: &str, path: &str, body: Option<Value>) -> Result<(), String> {
        for _ in 0..2 {
            wait_cool(30);
            let tok = self.token()?;
            let req = net::agent().request(method, &format!("{}{}", API, path)).set("Authorization", &format!("Bearer {}", tok));
            let r = match &body {
                Some(b) => req.send_json(b.clone()),
                None => req.set("Content-Length", "0").call(),
            };
            match r {
                Ok(_) => return Ok(()),
                Err(ureq::Error::Status(429, r)) => limited(&r),
                Err(ureq::Error::Status(code, _)) => return Err(format!("http {}", code)),
                Err(e) => return Err(e.to_string()),
            }
        }
        Err("rate limited".into())
    }

    fn me(&self) {
        let me = match self.get("/me") {
            Ok(v) => v,
            Err(e) => {
                web_log(&format!("/me failed: {}", e));
                // no profile access: at least the name, from the owner of one of your playlists
                let name = self
                    .get("/me/playlists?limit=20")
                    .ok()
                    .and_then(|v| {
                        let uid = self.user_id();
                        v.get("items")?.as_array()?.iter().find_map(|p| {
                            let o = p.get("owner")?;
                            (uid.is_empty() || o.get("id")?.as_str()? == uid).then(|| o.get("display_name")?.as_str().map(|s| s.to_string()))?
                        })
                    })
                    .unwrap_or_default();
                // the picture from last time
                let (img, small) = pfp_images(std::fs::read(crate::config::cache_dir().join("pfp.img")).ok());
                if !name.is_empty() || img.is_some() {
                    let _ = self.ev.send(Ev::Profile { name, img, small });
                    self.ctx.request_repaint();
                }
                return;
            }
        };
        let name = me.get("display_name").and_then(|x| x.as_str()).or_else(|| me.get("id").and_then(|x| x.as_str())).unwrap_or("").to_string();
        // smallest profile picture, turned into 12x12 pixel art
        let url = me
            .get("images")
            .and_then(|x| x.as_array())
            .and_then(|a| a.iter().filter_map(|i| Some((i.get("width").and_then(|w| w.as_u64()).unwrap_or(9999), i.get("url")?.as_str()?.to_string()))).min_by_key(|x| x.0))
            .map(|x| x.1);
        // the picture is kept on disk: it shows at once next time, even if Spotify is busy
        let pfp_file = crate::config::cache_dir().join("pfp.img");
        let bytes = url
            .and_then(|u| {
                let r = net::agent().get(&u).call().ok()?;
                let mut b = Vec::new();
                std::io::Read::read_to_end(&mut std::io::Read::take(r.into_reader(), 4 << 20), &mut b).ok()?;
                let _ = std::fs::write(&pfp_file, &b);
                Some(b)
            })
            .or_else(|| std::fs::read(&pfp_file).ok());
        let (img, small) = pfp_images(bytes);
        let _ = self.ev.send(Ev::Profile { name, img, small });
        self.ctx.request_repaint();
    }

    fn user_id(&self) -> String {
        let id = self.cfg.lock().unwrap().user_id.clone();
        if !id.is_empty() {
            return id;
        }
        if let Ok(me) = self.get("/me") {
            let id = me.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string();
            let mut c = self.cfg.lock().unwrap();
            c.user_id = id.clone();
            c.save();
            return id;
        }
        String::new()
    }

    fn playlists(&self) -> Result<Vec<Playlist>, String> {
        let uid = self.user_id();
        let mut out = vec![Playlist { uri: liked_uri(&uid), id: "liked".into(), name: "Liked Songs".into(), total: 0, owned: true }];
        let mut next = Some(format!("{}/me/playlists?limit=50", API));
        while let Some(url) = next.take() {
            let v = self.get(&url)?;
            for p in v.get("items").and_then(|x| x.as_array()).cloned().unwrap_or_default() {
                if p.is_null() {
                    continue;
                }
                let owner = p.pointer("/owner/id").and_then(|x| x.as_str()).unwrap_or("");
                let collab = p.get("collaborative").and_then(|x| x.as_bool()).unwrap_or(false);
                let total = p
                    .pointer("/items/total")
                    .or_else(|| p.pointer("/tracks/total"))
                    .and_then(|x| x.as_u64())
                    .unwrap_or(0) as u32;
                out.push(Playlist {
                    uri: p.get("uri").and_then(|x| x.as_str()).unwrap_or("").to_string(),
                    id: p.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string(),
                    name: p.get("name").and_then(|x| x.as_str()).unwrap_or("?").to_string(),
                    total,
                    owned: owner == uid || collab,
                });
            }
            next = v.get("next").and_then(|x| x.as_str()).map(|s| s.to_string());
        }
        Ok(out)
    }

    fn parse_track(t: &Value) -> Option<Track> {
        if t.is_null() {
            return None;
        }
        let uri = t.get("uri")?.as_str()?.to_string();
        let artists = t
            .get("artists")
            .and_then(|a| a.as_array())
            .map(|a| a.iter().filter_map(|x| x.get("name").and_then(|n| n.as_str())).collect::<Vec<_>>().join(", "))
            .or_else(|| t.pointer("/show/name").and_then(|x| x.as_str()).map(|s| s.to_string()))
            .unwrap_or_default();
        Some(Track {
            uri,
            name: t.get("name").and_then(|x| x.as_str()).unwrap_or("").to_string(),
            artists,
            album: t.pointer("/album/name").and_then(|x| x.as_str()).unwrap_or("").to_string(),
            duration_ms: t.get("duration_ms").and_then(|x| x.as_u64()).unwrap_or(0),
            ..Default::default()
        })
    }

    /// One entry of a playlist / Liked Songs page, with when it was added and its place.
    fn parse_entry(e: &Value, order: usize) -> Option<Track> {
        let t = e.get("item").or_else(|| e.get("track")).cloned().unwrap_or(Value::Null);
        let mut t = Self::parse_track(&t)?;
        t.added = e.get("added_at").and_then(|x| x.as_str()).map(iso_secs).unwrap_or(0);
        t.order = order as u32;
        Some(t)
    }

    fn tracks(&self, p: &Playlist) {
        let (first, max) = if p.id == "liked" {
            (format!("{}/me/tracks?limit=50", API), 3000)
        } else {
            (format!("{}/playlists/{}/items?limit=100", API, p.id), 3000)
        };
        let mut all = Vec::new();
        let mut next = Some(first);
        let mut listable = true;
        while let Some(url) = next.take() {
            match self.get(&url) {
                Ok(v) => {
                    for e in v.get("items").and_then(|x| x.as_array()).cloned().unwrap_or_default() {
                        if let Some(t) = Self::parse_entry(&e, all.len()) {
                            all.push(t);
                        }
                    }
                    next = v.get("next").and_then(|x| x.as_str()).map(|s| s.to_string());
                    if all.len() >= max {
                        next = None;
                    }
                    let _ = self.ev.send(Ev::Tracks { playlist: p.uri.clone(), tracks: all.clone(), listable, done: next.is_none() });
                    self.ctx.request_repaint();
                }
                Err(_) => {
                    // Spotify only lists the contents of playlists you own or collaborate on (Feb 2026 rules).
                    listable = false;
                    let _ = self.ev.send(Ev::Tracks { playlist: p.uri.clone(), tracks: all.clone(), listable, done: true });
                    self.ctx.request_repaint();
                }
            }
        }
    }

    /// All songs of a playlist in one go (no progress updates). bool = Spotify allowed listing it.
    pub fn fetch_all(&self, p: &Playlist) -> (Vec<Track>, bool) {
        let mut all = Vec::new();
        let mut next = Some(if p.id == "liked" { format!("{}/me/tracks?limit=50", API) } else { format!("{}/playlists/{}/items?limit=100", API, p.id) });
        while let Some(url) = next.take() {
            match self.get(&url) {
                Ok(v) => {
                    for e in v.get("items").and_then(|x| x.as_array()).cloned().unwrap_or_default() {
                        if let Some(t) = Self::parse_entry(&e, all.len()) {
                            all.push(t);
                        }
                    }
                    next = v.get("next").and_then(|x| x.as_str()).map(|s| s.to_string());
                }
                Err(_) => return (all, false),
            }
        }
        (all, true)
    }

    fn device_id(&self, name: &str) -> Option<String> {
        let v = self.get("/me/player/devices").ok()?;
        v.get("devices")?
            .as_array()?
            .iter()
            .find(|d| d.get("name").and_then(|n| n.as_str()) == Some(name))
            .and_then(|d| d.get("id").and_then(|i| i.as_str()).map(|s| s.to_string()))
    }

    /// Plays exactly these songs (up to 100), in order, on the SpoX device: SpoX shuffle and
    /// playing a list in your own sort order. `position_ms` starts the first one part-way.
    pub fn play_uris(&self, device: &str, uris: &[String], position_ms: u64, offset: usize) -> Result<(), String> {
        if uris.is_empty() {
            return Err("nothing to play".into());
        }
        let id = self.device_id(device).ok_or("device not found")?;
        let window: Vec<&String> = uris.iter().take(100).collect();
        let mut body = json!({ "uris": window });
        if offset > 0 {
            body["offset"] = json!({ "position": offset });
        }
        if position_ms > 0 {
            body["position_ms"] = json!(position_ms);
        }
        self.put(&format!("/me/player/play?device_id={}", enc(&id)), body)
    }

    fn play_in(&self, context: &str, track: &str, device: &str, fallback: &[String]) -> Result<(), String> {
        let id = self.device_id(device).ok_or("device not found")?;
        let path = format!("/me/player/play?device_id={}", enc(&id));
        let r = self.put(&path, json!({"context_uri": context, "offset": {"uri": track}}));
        if r.is_ok() || fallback.is_empty() {
            return r;
        }
        // e.g. Liked Songs: play a window of track uris instead
        let idx = fallback.iter().position(|u| u == track).unwrap_or(0);
        let window: Vec<&String> = fallback.iter().skip(idx).take(100).collect();
        self.put(&path, json!({"uris": window}))
    }
}

fn run(cfg: Shared, ev: Sender<Ev>, ctx: egui::Context, rx: Receiver<Req>) {
    let api = Api { cfg: cfg.clone(), ev: ev.clone(), ctx: ctx.clone() };
    // Show cached playlists instantly, refresh in the background.
    if let Some(cached) = load_cached_playlists() {
        let _ = ev.send(Ev::Playlists(cached));
        ctx.request_repaint();
    }
    while let Ok(req) = rx.recv() {
        match req {
            Req::SignIn => {
                // Runs on its own thread so a stuck/abandoned attempt never blocks a new one.
                let api = api.clone();
                let ev = ev.clone();
                std::thread::spawn(move || match api.sign_in() {
                    Ok(()) => {
                        api.say("");
                        let _ = ev.send(Ev::SignedIn(true));
                        api.me();
                        if let Ok(p) = api.playlists() {
                            save_cached_playlists(&p);
                            let _ = ev.send(Ev::Playlists(p));
                        }
                        api.ctx.request_repaint();
                    }
                    Err(e) if e.starts_with("replaced") => {}
                    Err(e) => api.say(&format!("SIGN-IN FAILED: {}", e)),
                });
            }
            Req::LoadPlaylists => {
                if cfg.lock().unwrap().refresh_token.is_empty() {
                    continue;
                }
                match api.playlists() {
                    Ok(p) => {
                        save_cached_playlists(&p);
                        let _ = ev.send(Ev::Playlists(p));
                    }
                    Err(e) => api.say(&format!("PLAYLISTS: {}", e)),
                }
            }
            Req::LoadTracks(p) => api.tracks(&p),
            Req::LoadMe => api.me(),
            Req::CheckLiked(uri) => {
                if let Ok(v) = api.get(&format!("/me/library/contains?uris={}", enc(&uri))) {
                    let liked = v.as_array().and_then(|a| a.first()).and_then(|x| x.as_bool()).unwrap_or(false);
                    let _ = ev.send(Ev::Liked { uri, liked });
                }
            }
            Req::SetLiked(uri, on) => {
                let r = api.send(if on { "PUT" } else { "DELETE" }, &format!("/me/library?uris={}", enc(&uri)), None);
                match r {
                    Ok(()) => {
                        let _ = ev.send(Ev::Liked { uri, liked: on });
                        let _ = ev.send(Ev::Osd(if on { "SAVED TO LIKED SONGS".into() } else { "REMOVED FROM LIKED SONGS".into() }));
                    }
                    Err(e) if e.contains("403") || e.contains("401") => {
                        let _ = ev.send(Ev::Osd("SIGN IN AGAIN (MENU) TO ENABLE LIKES".into()));
                    }
                    Err(e) => {
                        let _ = ev.send(Ev::Osd(format!("LIKE FAILED: {}", e)));
                    }
                }
            }
            Req::AddTo { playlist, uri } => {
                let body = json!({"uris": [uri.clone()]});
                // new endpoint first, then the older one if this account still uses it
                let mut r = api.send("POST", &format!("/playlists/{}/items", playlist.id), Some(body.clone()));
                if matches!(&r, Err(e) if e.contains("404") || e.contains("405") || e.contains("400")) {
                    r = api.send("POST", &format!("/playlists/{}/tracks", playlist.id), Some(body));
                }
                let _ = ev.send(Ev::Osd(match &r {
                    Ok(()) => format!("ADDED TO {}", playlist.name.to_uppercase()),
                    Err(e) if e.contains("403") => "SPOTIFY SAID NO (403): SIGN IN AGAIN OR NOT YOUR PLAYLIST".into(),
                    Err(e) if e.contains("401") => "SIGN IN AGAIN (MENU)".into(),
                    Err(e) => format!("ADD FAILED: {}", e.to_uppercase()),
                }));
                if r.is_ok() {
                    let _ = ev.send(Ev::Added { playlist: playlist.uri.clone(), uri });
                }
            }
            Req::RemoveFrom { playlist, uri, key } => {
                // Feb 2026 API: /items with "items"; older accounts: /tracks with "tracks"
                let mut r = api.send("DELETE", &format!("/playlists/{}/items", playlist.id), Some(json!({"items": [{"uri": uri.clone()}]})));
                if matches!(&r, Err(e) if e.contains("400")) {
                    r = api.send("DELETE", &format!("/playlists/{}/items", playlist.id), Some(json!({"tracks": [{"uri": uri.clone()}]})));
                }
                if matches!(&r, Err(e) if e.contains("404") || e.contains("405") || e.contains("400")) {
                    r = api.send("DELETE", &format!("/playlists/{}/tracks", playlist.id), Some(json!({"tracks": [{"uri": uri.clone()}]})));
                }
                let _ = ev.send(Ev::Osd(match &r {
                    Ok(()) => format!("REMOVED FROM {}", playlist.name.to_uppercase()),
                    Err(e) if e.contains("403") => "SPOTIFY SAID NO (403): SIGN IN AGAIN".into(),
                    Err(e) if e.contains("401") => "SIGN IN AGAIN (MENU)".into(),
                    Err(e) => format!("REMOVE FAILED: {}", e.to_uppercase()),
                }));
                if r.is_ok() {
                    let _ = ev.send(Ev::Removed { playlist: playlist.uri.clone(), uri, key });
                }
            }
            Req::LoadTracksQuiet(p) => {
                let (tracks, ok) = api.fetch_all(&p);
                if ok {
                    let _ = ev.send(Ev::Tracks { playlist: p.uri.clone(), tracks, listable: true, done: true });
                }
            }
            Req::PlayIn { context, track, device_name, fallback_uris } => {
                if let Err(e) = api.play_in(&context, &track, &device_name, &fallback_uris) {
                    let _ = ev.send(Ev::Status(format!("PLAY FAILED: {}", e)));
                }
            }
        }
        ctx.request_repaint();
    }
}

fn cache_file() -> std::path::PathBuf {
    config::cache_dir().join("playlists.json")
}
fn save_cached_playlists(p: &[Playlist]) {
    let v: Vec<Value> = p.iter().map(|p| json!([p.uri, p.id, p.name, p.total, p.owned])).collect();
    let _ = std::fs::create_dir_all(config::cache_dir());
    let _ = std::fs::write(cache_file(), Value::Array(v).to_string());
}
fn load_cached_playlists() -> Option<Vec<Playlist>> {
    let v: Value = serde_json::from_slice(&std::fs::read(cache_file()).ok()?).ok()?;
    Some(
        v.as_array()?
            .iter()
            .filter_map(|e| {
                Some(Playlist {
                    uri: e.get(0)?.as_str()?.to_string(),
                    id: e.get(1)?.as_str()?.to_string(),
                    name: e.get(2)?.as_str()?.to_string(),
                    total: e.get(3)?.as_u64()? as u32,
                    owned: e.get(4)?.as_bool()?,
                })
            })
            .collect(),
    )
}

/// Small log for Spotify errors (cache\\web.log), to see why something didn't load.
static SEARCHES: std::sync::Mutex<Vec<i64>> = std::sync::Mutex::new(Vec::new());

/// Artist -> (when asked, their songs), loaded from artists.json once.
fn artist_cache() -> &'static std::sync::Mutex<std::collections::HashMap<String, (i64, Vec<Track>)>> {
    static C: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<String, (i64, Vec<Track>)>>> = std::sync::OnceLock::new();
    C.get_or_init(|| {
        let mut m = std::collections::HashMap::new();
        if let Some(Value::Object(o)) = std::fs::read(crate::config::config_dir().join("artists.json")).ok().and_then(|b| serde_json::from_slice(&b).ok()) {
            for (a, e) in o {
                let at = e[0].as_i64().unwrap_or(0);
                let ts = e[1]
                    .as_array()
                    .map(|l| {
                        l.iter()
                            .map(|x| Track {
                                uri: x[0].as_str().unwrap_or("").into(),
                                name: x[1].as_str().unwrap_or("").into(),
                                artists: x[2].as_str().unwrap_or("").into(),
                                ..Default::default()
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                m.insert(a, (at, ts));
            }
        }
        std::sync::Mutex::new(m)
    })
}

/// The profile picture as 12x12 (menu header) and 8x8 (menu button) pixel art.
fn pfp_images(bytes: Option<Vec<u8>>) -> (Option<egui::ColorImage>, Option<egui::ColorImage>) {
    let Some(im) = bytes.and_then(|b| image::load_from_memory(&b).ok()).map(|i| i.to_rgba8()) else { return (None, None) };
    let make = |n: u32| {
        let px = crate::art::pixelate(&im, n, 8);
        egui::ColorImage::from_rgba_unmultiplied([n as usize, n as usize], &px)
    };
    (Some(make(12)), Some(make(8)))
}

pub fn web_log(line: &str) {
    use std::io::Write as _;
    let p = crate::config::cache_dir().join("web.log");
    if std::fs::metadata(&p).map(|m| m.len() > 64 * 1024).unwrap_or(false) {
        let _ = std::fs::remove_file(&p);
    }
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(p) {
        let _ = writeln!(f, "{} {}", crate::config::now_ms(), line);
    }
}

/// "2025-03-01T12:34:56Z" -> unix seconds (0 if it doesn't look like that).
pub fn iso_secs(s: &str) -> i64 {
    let n = |a: usize, b: usize| s.get(a..b).and_then(|x| x.parse::<i64>().ok());
    let (Some(y), Some(m), Some(d)) = (n(0, 4), n(5, 7), n(8, 10)) else { return 0 };
    let (hh, mm, ss) = (n(11, 13).unwrap_or(0), n(14, 16).unwrap_or(0), n(17, 19).unwrap_or(0));
    // days from 1970-01-01 (proleptic Gregorian)
    let (y2, m2) = if m <= 2 { (y - 1, m + 9) } else { (y, m - 3) };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let doy = (153 * m2 + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    days * 86400 + hh * 3600 + mm * 60 + ss
}

#[cfg(test)]
mod iso_tests {
    #[test]
    fn iso() {
        assert_eq!(super::iso_secs("1970-01-01T00:00:00Z"), 0);
        assert_eq!(super::iso_secs("2026-11-25T01:02:03Z"), 1795568523);
    }
}


// ---------------- Spotify's request limit ----------------
// Spotify answers "429: too many requests, wait N s" when an app asks too much (SpoX on the PC
// and the phone share one limit); then every request fails for a while, also "play". After a 429
// nothing is sent until the wait is over, and background work goes one at a time, spaced out.

use std::sync::atomic::AtomicI64;
static COOL_UNTIL: AtomicI64 = AtomicI64::new(0);
static LAST_BG: AtomicI64 = AtomicI64::new(0);
static BG_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn now_ms() -> i64 {
    crate::config::now_ms()
}

/// Spotify asked SpoX to slow down and the wait isn't over (for the logs).
pub fn cooling_now() -> bool {
    cooling()
}

fn cooling() -> bool {
    now_ms() < COOL_UNTIL.load(Ordering::SeqCst)
}

fn limited(r: &ureq::Response) {
    let wait: i64 = r.header("Retry-After").and_then(|s| s.parse().ok()).unwrap_or(5).clamp(2, 600);
    COOL_UNTIL.fetch_max(now_ms() + wait * 1000, Ordering::SeqCst);
    web_log(&format!("Spotify asked to slow down: waiting {}s", wait));
}

/// Waits out a cool-down (at most `max_s` seconds) before something you pressed.
fn wait_cool(max_s: i64) {
    let until = COOL_UNTIL.load(Ordering::SeqCst).min(now_ms() + max_s * 1000);
    while now_ms() < until {
        std::thread::sleep(Duration::from_millis(250));
    }
}

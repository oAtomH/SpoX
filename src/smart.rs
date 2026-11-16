//! SpoX smart shuffle: Spotify shuffles the playing playlist as usual, and SpoX slips songs
//! that are NOT in your playlists or Liked Songs into the queue between them. New songs come from Spotify's own autoplay
//! suggestions (collected from the queue) and, if you add a key, from Last.fm "similar tracks".
//! Runs on its own thread, so it keeps working while SpoX is hidden in the tray.

use crate::config::{self, now_ms, Shared};
use crate::net::{self, enc};
use crate::soloist::{command, command_with};
use crate::state::{Ev, Play, Playlist, Track};
use eframe::egui;
use serde_json::{json, Value};
use std::collections::{HashSet, VecDeque};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub enum Cmd {
    Start { playlist: Playlist, tracks: Vec<Track> },
    Stop,
    Ev(Value),
}

/// "Song Title (Remastered 2011) - Live" + "Artist, Feat" -> "song title|artist"
pub fn key(name: &str, artists: &str) -> String {
    let mut n = name.to_lowercase();
    for cut in [" (", " [", " - "] {
        if let Some(i) = n.find(cut) {
            n.truncate(i);
        }
    }
    let a = artists.split(", ").next().unwrap_or("").to_lowercase();
    format!("{}|{}", n.trim(), a.trim())
}

// ---- songs you know, according to Spotify ------------------------------------------------------
// Liked Songs + your playlists (the ones SpoX has loaded) + your last 50 plays. Nothing is
// recorded by SpoX itself: it all comes from your Spotify account. Up next marks the rest as
// "new to you", and smart shuffle only slips in songs from outside this set.

static KNOWN: std::sync::OnceLock<Mutex<(bool, HashSet<String>)>> = std::sync::OnceLock::new();
fn known_set() -> &'static Mutex<(bool, HashSet<String>)> {
    KNOWN.get_or_init(|| Mutex::new((false, HashSet::new())))
}

/// Is this a song you know? None until the list has loaded from Spotify.
pub fn knows(name: &str, artists: &str) -> Option<bool> {
    let k = known_set().lock().unwrap();
    if !k.0 {
        return None;
    }
    Some(k.1.contains(&key(name, artists)))
}

/// (Re)loads the list in the background from your Spotify account.
pub fn load_known(api: crate::web::Api) {
    std::thread::spawn(move || {
        let mut keys: HashSet<String> = HashSet::new();
        if let Ok(rd) = std::fs::read_dir(config::cache_dir().join("tracks")) {
            for f in rd.flatten() {
                if let Ok(b) = std::fs::read(f.path()) {
                    if let Ok(Value::Array(a)) = serde_json::from_slice::<Value>(&b) {
                        for e in a {
                            if let (Some(n), Some(ar)) = (e.get(1).and_then(|x| x.as_str()), e.get(2).and_then(|x| x.as_str())) {
                                keys.insert(key(n, ar));
                            }
                        }
                    }
                }
            }
        }
        for t in api.liked_all().iter().chain(api.recent().iter()) {
            keys.insert(key(&t.name, &t.artists));
        }
        let mut k = known_set().lock().unwrap();
        k.1.extend(keys);
        k.0 = true;
    });
}

/// A song just played for a while: it's not new any more.
pub fn note_known(name: &str, artists: &str) {
    known_set().lock().unwrap().1.insert(key(name, artists));
}

/// Popular songs by artists similar to `artist` (Deezer's public API: related artists + their
/// top songs), shuffled. (title, artist) pairs.
fn similar_songs(artist: &str) -> Vec<(String, String)> {
    let get = |url: String| -> Option<Value> { net::agent().get(&url).timeout(Duration::from_secs(10)).call().ok()?.into_json().ok() };
    let Some(v) = get(format!("https://api.deezer.com/search/artist?limit=1&q={}", enc(artist))) else { return Vec::new() };
    let Some(id) = v.pointer("/data/0/id").and_then(|x| x.as_u64()) else { return Vec::new() };
    let Some(rel) = get(format!("https://api.deezer.com/artist/{}/related?limit=20", id)) else { return Vec::new() };
    let mut rel: Vec<u64> = rel.get("data").and_then(|d| d.as_array()).map(|a| a.iter().filter_map(|x| x.get("id").and_then(|i| i.as_u64())).collect()).unwrap_or_default();
    let mut rng = Rng::new();
    let mut out = Vec::new();
    for _ in 0..3 {
        if rel.is_empty() {
            break;
        }
        let r = rel.swap_remove(rng.below(rel.len()));
        if let Some(top) = get(format!("https://api.deezer.com/artist/{}/top?limit=8", r)) {
            for t in top.get("data").and_then(|d| d.as_array()).cloned().unwrap_or_default() {
                if let (Some(title), Some(a)) = (t.get("title").and_then(|x| x.as_str()), t.pointer("/artist/name").and_then(|x| x.as_str())) {
                    out.push((title.to_string(), a.to_string()));
                }
            }
        }
    }
    // shuffle
    for i in (1..out.len()).rev() {
        let j = rng.below(i + 1);
        out.swap(i, j);
    }
    out
}

struct Rng(u64);
impl Rng {
    fn new() -> Self {
        let mut b = [0u8; 8];
        let _ = getrandom::getrandom(&mut b);
        Rng(u64::from_le_bytes(b) | 1)
    }
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n.max(1) as u64) as usize
    }
}

struct Engine {
    cfg: Shared,
    ev: Sender<Ev>,
    ctx: egui::Context,
    solo: Sender<String>,
    api: crate::web::Api,
    on: bool,
    playlist: Option<Playlist>,
    tracks: Vec<Track>,
    acc: u32,
    pool: Arc<Mutex<VecDeque<Track>>>,
    library: Arc<Mutex<HashSet<String>>>,
    used: HashSet<String>,
    new_uris: Vec<String>,
    queued: VecDeque<String>,
    play: Play,
    rng: Rng,
    last_fetch: Option<Instant>,
    fetching: Arc<Mutex<bool>>,
}

pub fn start(cfg: Shared, ev: Sender<Ev>, ctx: egui::Context, solo: Sender<String>) -> (Sender<Cmd>, Sender<Value>) {
    let (tx, rx) = std::sync::mpsc::channel::<Cmd>();
    let (vtx, vrx) = std::sync::mpsc::channel::<Value>();
    let fwd = tx.clone();
    std::thread::spawn(move || {
        while let Ok(v) = vrx.recv() {
            if fwd.send(Cmd::Ev(v)).is_err() {
                break;
            }
        }
    });
    let api = crate::web::Api::new(cfg.clone(), ev.clone(), ctx.clone());
    std::thread::Builder::new()
        .name("smart-shuffle".into())
        .spawn(move || {
            let mut e = Engine {
                cfg,
                ev,
                ctx,
                solo,
                api,
                on: false,
                playlist: None,
                tracks: Vec::new(),
                acc: 0,
                pool: Default::default(),
                library: Default::default(),
                used: HashSet::new(),
                new_uris: Vec::new(),
                queued: VecDeque::new(),
                play: Play::default(),
                rng: Rng::new(),
                last_fetch: None,
                fetching: Default::default(),
            };
            e.run(rx);
        })
        .ok();
    (tx, vtx)
}

impl Engine {
    fn run(&mut self, rx: Receiver<Cmd>) {
        loop {
            match rx.recv_timeout(Duration::from_secs(5)) {
                Ok(Cmd::Start { playlist, tracks }) => self.begin(playlist, tracks),
                Ok(Cmd::Stop) => {
                    self.on = false;
                    self.queued.clear();
                    self.report();
                }
                Ok(Cmd::Ev(v)) => {
                    let changed = self.play.apply(&v);
                    if self.on {
                        self.harvest();
                        if changed {
                            self.on_track();
                        }
                    }
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                    if self.on {
                        self.maybe_discover();
                    }
                }
                Err(_) => break,
            }
        }
    }

    fn send(&self, c: String) {
        let _ = self.solo.send(c);
    }

    fn report(&self) {
        let _ = self.ev.send(Ev::Smart { on: self.on, new_uris: self.new_uris.clone(), pool: self.pool.lock().unwrap().len() });
        self.ctx.request_repaint();
    }

    fn begin(&mut self, playlist: Playlist, tracks: Vec<Track>) {
        // merge duplicates (same song on different albums)
        let mut seen = HashSet::new();
        self.tracks = tracks.into_iter().filter(|t| t.uri.starts_with("spotify:track:") && seen.insert(key(&t.name, &t.artists))).collect();
        if self.tracks.is_empty() {
            let _ = self.ev.send(Ev::Osd("SMART SHUFFLE NEEDS A PLAYLIST WITH SONGS".into()));
            return;
        }
        self.on = true;
        self.playlist = Some(playlist);
        self.used.clear();
        self.queued.clear();
        self.new_uris.clear();
        {
            let mut lib = self.library.lock().unwrap();
            lib.extend(self.tracks.iter().map(|t| key(&t.name, &t.artists)));
        }
        self.load_library();
        // Spotify shuffles the playlist as usual (so Up next shows the whole list);
        // SpoX slips songs you don't know yet into the queue between them.
        let uri = self.playlist.as_ref().map(|p| p.uri.clone()).unwrap_or_default();
        if self.play.context_uri != uri && !uri.is_empty() {
            self.send(command("activate"));
            self.send(command_with("play", "uri", json!(uri)));
        }
        self.send(command_with("set_shuffle", "enabled", json!(true)));
        self.acc = 0;
        self.maybe_discover();
        self.report();
        let ratio = self.cfg.lock().unwrap().smart_ratio;
        let _ = self.ev.send(Ev::Osd(format!("SMART SHUFFLE ON \u{b7} {}% NEW", ratio)));
    }


    /// Library = Liked Songs + every playlist SpoX has cached. Loaded in the background.
    fn load_library(&self) {
        let lib = self.library.clone();
        let api = self.api.clone();
        std::thread::spawn(move || {
            let mut keys: HashSet<String> = HashSet::new();
            if let Ok(rd) = std::fs::read_dir(config::cache_dir().join("tracks")) {
                for f in rd.flatten() {
                    if let Ok(b) = std::fs::read(f.path()) {
                        if let Ok(Value::Array(a)) = serde_json::from_slice::<Value>(&b) {
                            for e in a {
                                if let (Some(n), Some(ar)) = (e.get(1).and_then(|x| x.as_str()), e.get(2).and_then(|x| x.as_str())) {
                                    keys.insert(key(n, ar));
                                }
                            }
                        }
                    }
                }
            }
            lib.lock().unwrap().extend(keys);
            let liked = api.liked_all();
            lib.lock().unwrap().extend(liked.iter().map(|t| key(&t.name, &t.artists)));
        });
    }

    fn is_known(&self, t: &Track) -> bool {
        let k = key(&t.name, &t.artists);
        self.used.contains(&k) || self.library.lock().unwrap().contains(&k) || known_set().lock().unwrap().1.contains(&k)
    }

    /// A new song (not in your playlists or Liked Songs), not by the artist playing right now.
    fn pick_new(&mut self) -> Option<Track> {
        let avoid = self.cfg.lock().unwrap().smart_avoid_artist;
        let now_artist = self.play.track.as_ref().map(|t| first_artist(&t.artists)).unwrap_or_default();
        let mut pool = self.pool.lock().unwrap();
        let mut tries = pool.len();
        while tries > 0 {
            tries -= 1;
            let Some(t) = pool.pop_front() else { break };
            let k = key(&t.name, &t.artists);
            if self.used.contains(&k) || self.library.lock().unwrap().contains(&k) || known_set().lock().unwrap().1.contains(&k) {
                continue;
            }
            if avoid && first_artist(&t.artists) == now_artist && !pool.is_empty() {
                pool.push_back(t);
                continue;
            }
            self.used.insert(k);
            self.new_uris.push(t.uri.clone());
            return Some(t);
        }
        None
    }

    fn on_track(&mut self) {
        // you're jumping ahead in Up next: don't slip songs in until you land
        if now_ms() < HOLD_UNTIL.load(std::sync::atomic::Ordering::SeqCst) {
            let cur = self.play.track.as_ref().map(|t| t.uri.clone()).unwrap_or_default();
            if let Some(i) = self.queued.iter().position(|u| *u == cur) {
                self.queued.drain(..=i);
            }
            return;
        }
        let cur = self.play.track.as_ref().map(|t| t.uri.clone()).unwrap_or_default();
        if let Some(i) = self.queued.iter().position(|u| *u == cur) {
            // one of our picks is playing: the playlist continues after it
            self.queued.drain(..=i);
            self.report();
            return;
        }
        // a playlist song: every so often (the "new songs" %), queue one new song after it
        if self.queued.is_empty() {
            self.acc += self.cfg.lock().unwrap().smart_ratio.min(100);
            if self.acc >= 100 {
                if let Some(t) = self.pick_new() {
                    self.acc -= 100;
                    self.send(command_with("add_to_queue", "uri", json!(t.uri)));
                    self.queued.push_back(t.uri);
                    self.send(command_with("get_queue", "limit", json!(50)));
                } else {
                    self.acc = 100;
                }
            }
        }
        if self.pool.lock().unwrap().len() < 6 {
            self.maybe_discover();
        }
        self.report();
    }

    /// Spotify's autoplay suggestions show up in the queue: keep the ones you don't know.
    fn harvest(&mut self) {
        let ups: Vec<Track> = self.play.upcoming.iter().filter(|t| t.provider.contains("autoplay") || t.provider.contains("recommend")).cloned().collect();
        if ups.is_empty() {
            return;
        }
        let fresh: Vec<Track> = ups.into_iter().filter(|t| !self.is_known(t)).collect();
        let mut pool = self.pool.lock().unwrap();
        for t in fresh {
            if !pool.iter().any(|p| p.uri == t.uri) {
                pool.push_back(t);
            }
        }
    }

    /// Last.fm "similar tracks" for a random song of the playlist (only with a Last.fm key).
    fn maybe_discover(&mut self) {
        let key_ = self.cfg.lock().unwrap().lastfm_key.trim().to_string();
        if *self.fetching.lock().unwrap() || self.tracks.is_empty() {
            return;
        }
        if self.pool.lock().unwrap().len() >= 12 {
            return;
        }
        if self.last_fetch.map(|t| t.elapsed() < Duration::from_secs(20)).unwrap_or(false) {
            return;
        }
        self.last_fetch = Some(Instant::now());
        let seed = self.tracks[self.rng.below(self.tracks.len())].clone();
        let (pool, lib, api, fetching) = (self.pool.clone(), self.library.clone(), self.api.clone(), self.fetching.clone());
        let used = self.used.clone();
        *fetching.lock().unwrap() = true;
        std::thread::spawn(move || {
            if key_.is_empty() {
                // no Last.fm key: songs by artists SIMILAR to the playlist's (Deezer's free "related
                // artists", no key needed), found on Spotify; else more songs by the playlist's artists
                let mut added = 0;
                for (title, artist) in similar_songs(&first_artist_raw(&seed.artists)) {
                    if added >= 3 {
                        break;
                    }
                    let k = key(&title, &artist);
                    if used.contains(&k) || lib.lock().unwrap().contains(&k) || known_set().lock().unwrap().1.contains(&k) {
                        continue;
                    }
                    if let Some(t) = api.search_track(&artist, &title) {
                        let mut p = pool.lock().unwrap();
                        if !p.iter().any(|x| x.uri == t.uri) {
                            p.push_back(t);
                            added += 1;
                        }
                    }
                }
                if added > 0 {
                    *fetching.lock().unwrap() = false;
                    return;
                }
                for t in api.search_artist(&first_artist_raw(&seed.artists)) {
                    let k = key(&t.name, &t.artists);
                    if used.contains(&k) || lib.lock().unwrap().contains(&k) {
                        continue;
                    }
                    let mut p = pool.lock().unwrap();
                    if !p.iter().any(|x| x.uri == t.uri) {
                        p.push_back(t);
                        added += 1;
                    }
                    if added >= 4 {
                        break;
                    }
                }
                *fetching.lock().unwrap() = false;
                return;
            }
            let url = format!(
                "https://ws.audioscrobbler.com/2.0/?method=track.getsimilar&autocorrect=1&limit=40&format=json&api_key={}&artist={}&track={}",
                enc(&key_), enc(&first_artist(&seed.artists)), enc(&seed.name)
            );
            let list: Vec<(String, String)> = net::agent()
                .get(&url)
                .call()
                .ok()
                .and_then(|r| r.into_json::<Value>().ok())
                .and_then(|v| v.pointer("/similartracks/track").and_then(|x| x.as_array()).cloned())
                .unwrap_or_default()
                .iter()
                .filter_map(|t| Some((t.get("name")?.as_str()?.to_string(), t.pointer("/artist/name")?.as_str()?.to_string())))
                .collect();
            let mut added = 0;
            for (name, artist) in list {
                if added >= 8 {
                    break;
                }
                let k = key(&name, &artist);
                if used.contains(&k) || lib.lock().unwrap().contains(&k) {
                    continue;
                }
                if let Some(t) = api.search_track(&artist, &name) {
                    let k2 = key(&t.name, &t.artists);
                    if lib.lock().unwrap().contains(&k2) {
                        continue;
                    }
                    let mut p = pool.lock().unwrap();
                    if !p.iter().any(|x| x.uri == t.uri) {
                        p.push_back(t);
                        added += 1;
                    }
                }
            }
            *fetching.lock().unwrap() = false;
        });
    }
}

fn first_artist_raw(a: &str) -> String {
    a.split(", ").next().unwrap_or("").to_string()
}

fn first_artist(a: &str) -> String {
    a.split(", ").next().unwrap_or("").to_lowercase()
}

static HOLD_UNTIL: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(0);

/// Pause smart picks for a moment (while skipping through Up next).
pub fn hold_for(d: Duration) {
    HOLD_UNTIL.store(now_ms() + d.as_millis() as i64, std::sync::atomic::Ordering::SeqCst);
}

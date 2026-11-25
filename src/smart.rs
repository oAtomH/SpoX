//! SpoX shuffle (same as SpoX for Android). SpoX decides the order itself instead of Spotify:
//!  - your list shuffled properly: every song once, duplicates merged, artists spread out;
//!  - new songs (not in your playlists, Liked Songs or recent plays) slipped in by the "new songs"
//!    share: 0% (just your list), 25, 50, 75 or 100% (only new songs). They come from Spotify's own
//!    autoplay picks, artists similar to what's playing (Deezer) and, with a key, Last.fm;
//!  - the order is saved, so closing SpoX (or the PC restarting) carries on where you were.
//! The same "deck" also plays a list in your own sort order with shuffle off (no new songs).
//! Spotify gets the next part of the deck as a list of up to 100 songs; SpoX hands it the next
//! part when you get there. Runs on its own thread, so it works while SpoX is hidden.

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
    /// Start on a list. `first`: the song to begin with; `shuffle` false: in the given order.
    Start { playlist: Playlist, tracks: Vec<Track>, first: Option<String>, shuffle: bool, position: u64 },
    Stop,
    Ev(Value),
    /// take songs out of what's coming
    Remove(Vec<String>),
    /// put songs right after the one playing
    PlayNext(Vec<Track>),
    /// you queued a song (library, link): keep it in the order
    Queued(Track),
    /// jump to a song in Up next
    JumpTo(String),
    /// nothing playing: carry on where you were
    Resume,
    /// the new-songs share changed
    Ratio,
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

#[derive(Clone)]
struct Entry {
    t: Track,
    kind: String,
    skip: bool,
}

/// How many songs Spotify gets ahead of the one playing (through its queue).
const AHEAD: usize = 3;
/// How far ahead new songs are planned into the order.
const HORIZON: usize = 60;

struct Engine {
    cfg: Shared,
    ev: Sender<Ev>,
    ctx: egui::Context,
    solo: Sender<String>,
    api: crate::web::Api,
    on: bool,
    shuffled: bool,
    playlist: Option<Playlist>,
    seeds: Vec<Track>,
    deck: Vec<Entry>,
    index: usize,
    /// deck[fed_to] is the first song Spotify hasn't been given yet
    fed_to: usize,
    /// new songs are planned in up to here
    planned_to: usize,
    /// new songs still owed at planned_to
    need_new: usize,
    started: Option<Instant>,
    last_track: String,
    last_tick: Instant,
    acc: f64,
    pool: Arc<Mutex<VecDeque<Track>>>,
    library: Arc<Mutex<HashSet<String>>>,
    used: Arc<Mutex<HashSet<String>>>,
    new_uris: Vec<String>,
    play: Play,
    rng: Rng,
    last_fetch: Option<Instant>,
    fetching: Arc<Mutex<bool>>,
    busy: String,
    /// songs Spotify still has queued that aren't wanted: skipped when they start (count, since)
    skipped: std::collections::HashMap<String, (u32, Instant)>,
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
        .name("spox-shuffle".into())
        .spawn(move || {
            let mut e = Engine {
                cfg,
                ev,
                ctx,
                solo,
                api,
                on: false,
                shuffled: true,
                playlist: None,
                seeds: Vec::new(),
                deck: Vec::new(),
                index: 0,
                fed_to: 0,
                planned_to: 0,
                need_new: 0,
                started: None,
                last_track: String::new(),
                last_tick: Instant::now(),
                acc: 0.0,
                pool: Default::default(),
                library: Default::default(),
                used: Default::default(),
                new_uris: Vec::new(),
                play: Play::default(),
                rng: Rng::new(),
                last_fetch: None,
                fetching: Default::default(),
                busy: String::new(),
                skipped: Default::default(),
            };
            e.restore();
            e.report();
            e.run(rx);
        })
        .ok();
    (tx, vtx)
}

fn state_path() -> std::path::PathBuf {
    config::config_dir().join("shuffle.json")
}

impl Engine {
    fn run(&mut self, rx: Receiver<Cmd>) {
        loop {
            match rx.recv_timeout(Duration::from_secs(2)) {
                Ok(Cmd::Start { playlist, tracks, first, shuffle, position }) => self.begin(playlist, tracks, first, shuffle, position),
                Ok(Cmd::Stop) => self.stop(),
                Ok(Cmd::Ev(v)) => {
                    let changed = self.play.apply(&v);
                    if changed {
                        let cur = self.play.track.as_ref().map(|t| t.uri.clone()).unwrap_or_default();
                        if self.take_skip(&cur) {
                            self.send(command("skip_next"));
                            continue;
                        }
                    }
                    if self.on {
                        self.harvest();
                        if changed {
                            self.on_track();
                        }
                    }
                }
                Ok(Cmd::Remove(uris)) => self.remove(&uris),
                Ok(Cmd::PlayNext(ts)) => self.play_next(ts),
                Ok(Cmd::Queued(t)) => self.queued(t),
                Ok(Cmd::JumpTo(uri)) => self.jump_to(&uri),
                Ok(Cmd::Resume) => {
                    if self.on && !self.deck.is_empty() {
                        self.play_at(self.index, 0);
                    }
                }
                Ok(Cmd::Ratio) => {
                    let r = self.ratio();
                    let only_new = !self.deck.is_empty() && self.deck.iter().all(|e| e.kind == "new");
                    if self.on && self.shuffled {
                        if (r >= 100) != only_new {
                            if let Some(p) = self.playlist.clone() {
                                let cur = self.play.track.as_ref().map(|t| t.uri.clone()).filter(|u| self.seeds.iter().any(|s| &s.uri == u));
                                let at = if cur.is_some() { self.play.position(now_ms()) as u64 } else { 0 };
                                let seeds = self.seeds.clone();
                                self.begin(p, seeds, cur, true, at);
                            }
                        } else {
                            // re-plan what Spotify doesn't have yet
                            let mut j = self.deck.len();
                            while j > self.fed_to {
                                j -= 1;
                                if self.deck[j].kind == "new" {
                                    self.deck.remove(j);
                                }
                            }
                            self.planned_to = self.fed_to;
                            self.need_new = 0;
                            self.acc = 0.0;
                            self.work(false);
                        }
                    }
                    self.report();
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                Err(_) => break,
            }
            if self.on && self.last_tick.elapsed() > Duration::from_secs(4) {
                self.last_tick = Instant::now();
                // the song is ending and Spotify has nothing after it: carry on even without the planned new song
                let left = self.play.track.as_ref().map(|t| t.duration_ms as f64 - self.play.position(now_ms())).unwrap_or(1e9);
                let force = self.play.status == "playing" && left < 25_000.0;
                self.work(force);
            }
        }
    }

    fn ratio(&self) -> u32 {
        self.cfg.lock().unwrap().smart_ratio.min(100)
    }

    fn send(&self, c: String) {
        let _ = self.solo.send(c);
    }

    fn osd(&self, s: impl Into<String>) {
        let _ = self.ev.send(Ev::Osd(s.into()));
        self.ctx.request_repaint();
    }

    fn report(&self) {
        let upcoming: Vec<(Track, String)> = if self.on {
            self.deck.iter().skip(self.index + 1).filter(|e| !e.skip).take(300).map(|e| (e.t.clone(), e.kind.clone())).collect()
        } else {
            Vec::new()
        };
        let _ = self.ev.send(Ev::Smart {
            on: self.on,
            shuffled: self.shuffled,
            list: self.playlist.as_ref().map(|p| p.name.clone()).unwrap_or_default(),
            new_uris: self.new_uris.clone(),
            upcoming,
            busy: self.busy.clone(),
        });
        self.ctx.request_repaint();
    }

    fn device(&self) -> String {
        if !self.play.device_name.is_empty() {
            return self.play.device_name.clone();
        }
        let n = self.cfg.lock().unwrap().device_name.clone();
        if n.is_empty() { "SpoX".into() } else { n }
    }

    // ---- skipping songs Spotify still has queued ----

    fn skip_later(&mut self, uris: Vec<String>) {
        for u in uris {
            let e = self.skipped.entry(u).or_insert((0, Instant::now()));
            if e.1.elapsed() > Duration::from_secs(15 * 60) {
                e.0 = 0;
            }
            e.0 += 1;
            e.1 = Instant::now();
        }
    }

    fn take_skip(&mut self, uri: &str) -> bool {
        let Some(e) = self.skipped.get_mut(uri) else { return false };
        if e.1.elapsed() > Duration::from_secs(15 * 60) || e.0 == 0 {
            self.skipped.remove(uri);
            return false;
        }
        e.0 -= 1;
        if e.0 == 0 {
            self.skipped.remove(uri);
        }
        true
    }

    /// Songs Spotify still has queued from SpoX that aren't wanted any more.
    fn drop_fed(&mut self, from: usize) {
        let left: Vec<String> = (from..self.fed_to.min(self.deck.len())).filter(|&k| !self.deck[k].skip).map(|k| self.deck[k].t.uri.clone()).collect();
        self.skip_later(left);
    }

    fn begin(&mut self, playlist: Playlist, tracks: Vec<Track>, first: Option<String>, shuffle: bool, position: u64) {
        // merge duplicates (same song on different albums), only playable songs
        let mut seen = HashSet::new();
        let clean: Vec<Track> = tracks.into_iter().filter(|t| t.uri.starts_with("spotify:track:") && seen.insert(key(&t.name, &t.artists))).collect();
        if clean.is_empty() {
            self.osd("SPOX SHUFFLE NEEDS A LIST WITH SONGS");
            return;
        }
        if self.on {
            self.drop_fed(self.index + 1);
        }
        self.on = true;
        self.shuffled = shuffle;
        self.playlist = Some(playlist);
        self.new_uris.clear();
        self.pool.lock().unwrap().clear();
        self.acc = 0.0;
        self.need_new = 0;
        self.last_track.clear();
        {
            let mut used = self.used.lock().unwrap();
            used.clear();
            used.extend(clean.iter().map(|t| key(&t.name, &t.artists)));
            self.library.lock().unwrap().extend(used.iter().cloned());
        }
        self.load_library();
        self.seeds = clean.clone();
        let mut order = if shuffle { spread(&clean, &mut self.rng) } else { clean };
        let mut same = false;
        if let Some(f) = &first {
            if let Some(i) = order.iter().position(|t| &t.uri == f) {
                same = true;
                if shuffle {
                    let t = order.remove(i);
                    order.insert(0, t);
                } else {
                    order.drain(..i);
                }
            }
        }
        let r = self.ratio();
        self.deck = if shuffle && r >= 100 {
            // only new songs: find a few first
            self.busy = "Finding new songs...".into();
            self.report();
            for _ in 0..6 {
                if self.pool.lock().unwrap().len() >= 8 {
                    break;
                }
                self.discover_now();
            }
            self.busy.clear();
            same = false;
            let mut fresh = Vec::new();
            while fresh.len() < 10 {
                match self.pick_new("") {
                    Some(t) => fresh.push(Entry { t, kind: "new".into(), skip: false }),
                    None => break,
                }
            }
            if fresh.is_empty() {
                self.osd("NO NEW SONGS FOUND, PLAYING YOUR LIST");
                order.into_iter().map(|t| Entry { t, kind: "list".into(), skip: false }).collect()
            } else {
                fresh
            }
        } else {
            order.into_iter().map(|t| Entry { t, kind: "list".into(), skip: false }).collect()
        };
        self.index = 0;
        self.fed_to = 0;
        self.planned_to = 0;
        self.send(command_with("set_shuffle", "enabled", json!(false)));
        self.play_at(0, if same { position } else { 0 });
        if shuffle {
            self.osd(if r == 0 { "SPOX SHUFFLE ON".to_string() } else { format!("SPOX SHUFFLE ON \u{b7} {}% NEW", r) });
        }
    }

    fn stop(&mut self) {
        if self.on {
            self.drop_fed(self.index + 1);
        }
        self.on = false;
        self.deck.clear();
        self.index = 0;
        self.fed_to = 0;
        self.planned_to = 0;
        self.busy.clear();
        self.save();
        self.report();
    }

    /// Starts deck[i] right now; the next songs follow through Spotify's queue.
    fn play_at(&mut self, i: usize, position_ms: u64) {
        if self.deck.is_empty() {
            return;
        }
        self.index = i.min(self.deck.len() - 1);
        self.fed_to = self.index + 1;
        if self.planned_to < self.fed_to {
            self.planned_to = self.fed_to;
            self.need_new = 0;
        }
        self.started = Some(Instant::now());
        if !self.play.is_active {
            self.send(command("activate"));
        }
        let uri = self.deck[self.index].t.uri.clone();
        if let Err(e) = self.api.play_uris(&self.device(), std::slice::from_ref(&uri), position_ms) {
            crate::web::web_log(&format!("SpoX shuffle: play failed: {}", e));
            self.send(command_with("play", "uri", json!(uri)));
        }
        self.work(false);
    }

    /// Plan, hand Spotify what it needs, look for new songs if more are wanted.
    fn work(&mut self, force: bool) {
        let hungry = self.plan();
        self.feed(force);
        if self.shuffled && self.ratio() > 0 && (hungry || self.pool.lock().unwrap().len() < 6) {
            self.maybe_discover(hungry);
        }
        self.save();
        self.report();
    }

    /// Plans new songs into the order, up to HORIZON songs ahead (never into what Spotify already
    /// has). Returns true while it wants more new songs than it has.
    fn plan(&mut self) -> bool {
        let r = self.ratio();
        if self.planned_to < self.fed_to {
            self.planned_to = self.fed_to;
            self.need_new = 0;
        }
        if !self.shuffled || r == 0 {
            self.planned_to = self.deck.len();
            return false;
        }
        if r >= 100 {
            while self.deck.len() - self.index < HORIZON {
                let last = self.deck.last().map(|e| e.t.artists.clone()).unwrap_or_default();
                let Some(t) = self.pick_new(&last) else { return true };
                self.deck.push(Entry { t, kind: "new".into(), skip: false });
            }
            self.planned_to = self.deck.len();
            return false;
        }
        while self.planned_to < self.deck.len() && self.planned_to < self.index + HORIZON {
            if self.need_new > 0 {
                let prev = self.deck.get(self.planned_to.wrapping_sub(1)).map(|e| e.t.artists.clone()).unwrap_or_default();
                let Some(t) = self.pick_new(&prev) else { return true };
                self.deck.insert(self.planned_to, Entry { t, kind: "new".into(), skip: false });
                self.planned_to += 1;
                self.need_new -= 1;
                continue;
            }
            let is_list = self.deck[self.planned_to].kind == "list";
            self.planned_to += 1;
            if is_list {
                self.acc += r as f64 / (100 - r) as f64;
                while self.acc >= 1.0 {
                    self.need_new += 1;
                    self.acc -= 1.0;
                }
            }
        }
        self.need_new > 0
    }

    /// Gives Spotify the next songs (up to AHEAD after the one playing). `force`: the song is
    /// ending and nothing follows it: carry on without the new song that was planned there.
    fn feed(&mut self, force: bool) {
        let mut limit = self.planned_to.min(self.deck.len());
        if force && self.fed_to <= self.index + 1 && self.fed_to >= limit && self.fed_to < self.deck.len() {
            self.planned_to = self.fed_to + 1;
            self.need_new = 0;
            limit = self.planned_to;
        }
        let mut sent = false;
        while self.fed_to < limit && self.fed_to - self.index <= AHEAD {
            if !self.deck[self.fed_to].skip {
                self.send(command_with("add_to_queue", "uri", json!(self.deck[self.fed_to].t.uri)));
                sent = true;
            }
            self.fed_to += 1;
        }
        if sent {
            self.send(command_with("get_queue", "limit", json!(0)));
        }
    }

    fn jump_to(&mut self, uri: &str) {
        let Some(i) = (self.index + 1..self.deck.len()).find(|&k| self.deck[k].t.uri == uri) else { return };
        if i < self.fed_to {
            // already with Spotify: skip there through its queue (one at a time, it drops fast ones)
            let n = (self.index + 1..i).filter(|&k| !self.deck[k].skip).count() + 1;
            hold_for(Duration::from_millis(450 * n as u64 + 3000));
            let tx = self.solo.clone();
            std::thread::spawn(move || {
                for _ in 0..n {
                    let _ = tx.send(command("skip_next"));
                    std::thread::sleep(Duration::from_millis(450));
                }
            });
        } else {
            hold_for(Duration::from_secs(3));
            self.drop_fed(self.index + 1);
            self.play_at(i, 0);
        }
    }

    fn remove(&mut self, uris: &[String]) {
        if !self.on {
            // Spotify can't take songs out of its queue: skip them the moment they start
            self.skip_later(uris.to_vec());
            return;
        }
        let set: HashSet<&String> = uris.iter().collect();
        let mut j = self.deck.len();
        while j > self.index + 1 {
            j -= 1;
            if set.contains(&self.deck[j].t.uri) {
                if j < self.fed_to {
                    self.deck[j].skip = true;
                } else {
                    self.deck.remove(j);
                    if j < self.planned_to {
                        self.planned_to -= 1;
                    }
                }
            }
        }
        self.work(false);
    }

    /// Put songs next, right after the few Spotify already has lined up.
    fn play_next(&mut self, ts: Vec<Track>) {
        if !self.on || self.deck.is_empty() {
            return;
        }
        let set: HashSet<String> = ts.iter().map(|t| t.uri.clone()).collect();
        let mut j = self.deck.len();
        while j > self.index + 1 {
            j -= 1;
            if set.contains(&self.deck[j].t.uri) {
                if j < self.fed_to {
                    self.deck[j].skip = true;
                } else {
                    self.deck.remove(j);
                    if j < self.planned_to {
                        self.planned_to -= 1;
                    }
                }
            }
        }
        for (k, t) in ts.iter().enumerate() {
            let kind = if self.new_uris.contains(&t.uri) { "new" } else { "user" };
            self.deck.insert(self.fed_to + k, Entry { t: t.clone(), kind: kind.into(), skip: false });
        }
        if self.planned_to >= self.fed_to {
            self.planned_to += ts.len();
        }
        self.work(false);
    }

    /// You queued a song yourself: Spotify plays it after what SpoX lined up.
    fn queued(&mut self, t: Track) {
        if !self.on {
            return;
        }
        let at = self.fed_to.min(self.deck.len());
        self.deck.insert(at, Entry { t, kind: "user".into(), skip: false });
        if self.planned_to >= at {
            self.planned_to += 1;
        }
        self.fed_to = at + 1;
        self.save();
        self.report();
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
        self.used.lock().unwrap().contains(&k) || self.library.lock().unwrap().contains(&k) || known_set().lock().unwrap().1.contains(&k)
    }

    /// A new song (not in your playlists or Liked Songs), not by the artist next to it.
    fn pick_new(&mut self, next_to: &str) -> Option<Track> {
        let avoid = self.cfg.lock().unwrap().smart_avoid_artist;
        let near = first_artist(next_to);
        let mut tries = self.pool.lock().unwrap().len();
        while tries > 0 {
            tries -= 1;
            let Some(t) = self.pool.lock().unwrap().pop_front() else { break };
            if self.is_known(&t) {
                continue;
            }
            if avoid && first_artist(&t.artists) == near && !self.pool.lock().unwrap().is_empty() {
                self.pool.lock().unwrap().push_back(t);
                continue;
            }
            self.used.lock().unwrap().insert(key(&t.name, &t.artists));
            self.new_uris.push(t.uri.clone());
            return Some(t);
        }
        None
    }

    fn on_track(&mut self) {
        let cur = self.play.track.as_ref().map(|t| t.uri.clone()).unwrap_or_default();
        if cur.is_empty() || cur == self.last_track {
            return;
        }
        self.last_track = cur.clone();
        // nearest match around where we are (a song can be in the order twice)
        let from = self.index.saturating_sub(10);
        let to = (self.fed_to + 2).min(self.deck.len());
        let pos = (from..to).filter(|&k| self.deck[k].t.uri == cur).min_by_key(|&k| (k as i64 - self.index as i64 - 1).abs());
        let Some(pos) = pos else {
            // just started / jumping: Spotify may still report the old song for a moment
            let fresh = self.started.map(|t| t.elapsed() < Duration::from_secs(6)).unwrap_or(false);
            if fresh || now_ms() < HOLD_UNTIL.load(std::sync::atomic::Ordering::SeqCst) {
                return;
            }
            // a song queued on another device: carry on after it
            if let Some(next) = self.deck.get(self.index + 1) {
                if self.play.upcoming.iter().any(|u| u.uri == next.t.uri) {
                    return;
                }
            }
            // you played something else: SpoX shuffle hands back to Spotify
            self.stop();
            return;
        };
        self.index = pos;
        if self.deck[pos].skip {
            self.send(command("skip_next"));
            return;
        }
        self.work(false);
    }

    /// Spotify's autoplay suggestions show up in the queue: also used as new songs.
    fn harvest(&mut self) {
        if !self.shuffled || self.ratio() == 0 {
            return;
        }
        let ups: Vec<Track> = self.play.upcoming.iter().filter(|t| t.provider.contains("autoplay") || t.provider.contains("recommend")).cloned().collect();
        let fresh: Vec<Track> = ups.into_iter().filter(|t| !self.is_known(t)).collect();
        let mut pool = self.pool.lock().unwrap();
        for t in fresh {
            if !pool.iter().any(|p| p.uri == t.uri) {
                pool.push_front(t);
            }
        }
    }

    /// Like a radio: mostly the song where the next new song goes, sometimes what's playing or any list song.
    fn seed(&mut self) -> Option<Track> {
        let roll = self.rng.below(10);
        if roll < 6 {
            if let Some(e) = self.deck.get(self.planned_to.wrapping_sub(1)).filter(|e| e.kind == "list") {
                return Some(e.t.clone());
            }
        }
        if roll < 8 {
            if let Some(t) = self.play.track.clone() {
                return Some(t);
            }
        }
        if self.seeds.is_empty() {
            return self.play.track.clone();
        }
        Some(self.seeds[self.rng.below(self.seeds.len())].clone())
    }

    fn maybe_discover(&mut self, hungry: bool) {
        if *self.fetching.lock().unwrap() || self.pool.lock().unwrap().len() >= 16 {
            return;
        }
        // while new songs are owed, look again right away; otherwise now and then
        let wait = if hungry { Duration::from_secs(2) } else { Duration::from_secs(20) };
        if self.last_fetch.map(|t| t.elapsed() < wait).unwrap_or(false) {
            return;
        }
        self.last_fetch = Some(Instant::now());
        let Some(seed) = self.seed() else { return };
        let job = self.job(seed);
        std::thread::spawn(move || job.run());
    }

    fn discover_now(&mut self) {
        if let Some(seed) = self.seed() {
            self.job(seed).run();
        }
    }

    fn job(&self, seed: Track) -> Discover {
        Discover {
            seed,
            lastfm: self.cfg.lock().unwrap().lastfm_key.trim().to_string(),
            pool: self.pool.clone(),
            lib: self.library.clone(),
            used: self.used.clone(),
            api: self.api.clone(),
            fetching: self.fetching.clone(),
        }
    }

    // ---- saved between runs ----

    fn save(&self) {
        let deck: Vec<Value> = self.deck.iter().map(|e| json!([e.t.uri, e.t.name, e.t.artists, e.t.album, e.t.duration_ms, e.kind, e.skip])).collect();
        let seeds: Vec<Value> = self.seeds.iter().take(500).map(|t| json!([t.uri, t.name, t.artists])).collect();
        let p = self.playlist.clone().unwrap_or_default();
        let v = json!({
            "on": self.on, "shuffled": self.shuffled, "index": self.index, "fed": self.fed_to, "planned": self.planned_to,
            "need": self.need_new, "acc": self.acc,
            "list": [p.uri, p.id, p.name, p.total, p.owned], "deck": deck, "seeds": seeds,
        });
        let path = state_path();
        let tmp = path.with_extension("tmp");
        if std::fs::write(&tmp, v.to_string()).is_ok() {
            let _ = std::fs::rename(&tmp, &path);
        }
    }

    fn restore(&mut self) {
        let Some(v) = std::fs::read(state_path()).ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok()) else { return };
        if !v["on"].as_bool().unwrap_or(false) {
            return;
        }
        let s = |x: &Value| x.as_str().unwrap_or("").to_string();
        self.deck = v["deck"]
            .as_array()
            .map(|a| {
                a.iter()
                    .map(|e| Entry {
                        t: Track { uri: s(&e[0]), name: s(&e[1]), artists: s(&e[2]), album: s(&e[3]), duration_ms: e[4].as_u64().unwrap_or(0), ..Default::default() },
                        kind: e[5].as_str().unwrap_or("list").to_string(),
                        skip: e[6].as_bool().unwrap_or(false),
                    })
                    .collect()
            })
            .unwrap_or_default();
        if self.deck.is_empty() {
            return;
        }
        let n = self.deck.len();
        self.index = (v["index"].as_u64().unwrap_or(0) as usize).min(n - 1);
        self.fed_to = (v["fed"].as_u64().unwrap_or(self.index as u64 + 1) as usize).clamp(self.index + 1, n.max(self.index + 1));
        self.planned_to = (v["planned"].as_u64().unwrap_or(self.fed_to as u64) as usize).clamp(self.fed_to, n.max(self.fed_to));
        self.need_new = v["need"].as_u64().unwrap_or(0) as usize;
        self.acc = v["acc"].as_f64().unwrap_or(0.0);
        self.on = true;
        self.shuffled = v["shuffled"].as_bool().unwrap_or(true);
        let l = &v["list"];
        self.playlist = Some(Playlist { uri: s(&l[0]), id: s(&l[1]), name: s(&l[2]), total: l[3].as_u64().unwrap_or(0) as u32, owned: l[4].as_bool().unwrap_or(false) });
        self.seeds = v["seeds"].as_array().map(|a| a.iter().map(|e| Track { uri: s(&e[0]), name: s(&e[1]), artists: s(&e[2]), ..Default::default() }).collect()).unwrap_or_default();
        self.new_uris = self.deck.iter().filter(|e| e.kind == "new").map(|e| e.t.uri.clone()).collect();
        let mut used = self.used.lock().unwrap();
        used.extend(self.deck.iter().map(|e| key(&e.t.name, &e.t.artists)));
        used.extend(self.seeds.iter().map(|t| key(&t.name, &t.artists)));
        drop(used);
        self.load_library();
    }
}

/// Finding new songs for the pool (runs on its own thread, or inline at 100% new).
struct Discover {
    seed: Track,
    lastfm: String,
    pool: Arc<Mutex<VecDeque<Track>>>,
    lib: Arc<Mutex<HashSet<String>>>,
    used: Arc<Mutex<HashSet<String>>>,
    api: crate::web::Api,
    fetching: Arc<Mutex<bool>>,
}

impl Discover {
    fn known(&self, k: &str) -> bool {
        self.used.lock().unwrap().contains(k) || self.lib.lock().unwrap().contains(k) || known_set().lock().unwrap().1.contains(k)
    }

    fn add(&self, t: Track) -> bool {
        if self.known(&key(&t.name, &t.artists)) {
            return false;
        }
        let mut p = self.pool.lock().unwrap();
        if p.iter().any(|x| x.uri == t.uri) {
            return false;
        }
        p.push_back(t);
        true
    }

    fn run(self) {
        {
            let mut f = self.fetching.lock().unwrap();
            if *f {
                return;
            }
            *f = true;
        }
        let mut added = 0;
        if !self.lastfm.is_empty() {
            // Last.fm "similar tracks" for the seed song
            let url = format!(
                "https://ws.audioscrobbler.com/2.0/?method=track.getsimilar&autocorrect=1&limit=40&format=json&api_key={}&artist={}&track={}",
                enc(&self.lastfm), enc(&first_artist_raw(&self.seed.artists)), enc(&self.seed.name)
            );
            let list: Vec<(String, String)> = net::agent()
                .get(&url)
                .timeout(Duration::from_secs(10))
                .call()
                .ok()
                .and_then(|r| r.into_json::<Value>().ok())
                .and_then(|v| v.pointer("/similartracks/track").and_then(|x| x.as_array()).cloned())
                .unwrap_or_default()
                .iter()
                .filter_map(|t| Some((t.get("name")?.as_str()?.to_string(), t.pointer("/artist/name")?.as_str()?.to_string())))
                .collect();
            for (name, artist) in list {
                if added >= 6 {
                    break;
                }
                if self.known(&key(&name, &artist)) {
                    continue;
                }
                if let Some(t) = self.api.search_track(&artist, &name) {
                    if self.add(t) {
                        added += 1;
                    }
                }
            }
        }
        if added < 3 {
            // songs by artists SIMILAR to the seed's (Deezer's free "related artists")
            for (title, artist) in similar_songs(&first_artist_raw(&self.seed.artists)) {
                if added >= 4 {
                    break;
                }
                if self.known(&key(&title, &artist)) {
                    continue;
                }
                if let Some(t) = self.api.search_track(&artist, &title) {
                    if self.add(t) {
                        added += 1;
                    }
                }
            }
        }
        if added == 0 {
            // nothing similar found: other songs by the seed's own artist
            for t in self.api.search_artist(&first_artist_raw(&self.seed.artists)) {
                if self.add(t) {
                    added += 1;
                }
                if added >= 3 {
                    break;
                }
            }
        }
        *self.fetching.lock().unwrap() = false;
    }
}

/// Shuffle that spreads each artist out: every artist's songs are placed at even gaps across the
/// list with a random start (and a little wobble), then everything is merged by position.
fn spread(songs: &[Track], rng: &mut Rng) -> Vec<Track> {
    let mut groups: std::collections::HashMap<String, Vec<Track>> = std::collections::HashMap::new();
    for t in songs {
        groups.entry(first_artist(&t.artists)).or_default().push(t.clone());
    }
    let unit = |r: &mut Rng| (r.next() % 1_000_000) as f64 / 1_000_000.0;
    let mut placed: Vec<(f64, Track)> = Vec::with_capacity(songs.len());
    for (_, mut g) in groups {
        for i in (1..g.len()).rev() {
            let j = rng.below(i + 1);
            g.swap(i, j);
        }
        let gap = 1.0 / g.len() as f64;
        let start = unit(rng) * gap;
        for (i, t) in g.into_iter().enumerate() {
            let wobble = (unit(rng) - 0.5) * gap * 0.3;
            placed.push((start + i as f64 * gap + wobble, t));
        }
    }
    placed.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    placed.into_iter().map(|p| p.1).collect()
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn spread_keeps_every_song_once_and_spaces_artists() {
        let mut songs = Vec::new();
        for i in 0..30 {
            songs.push(Track { uri: format!("u{}", i), name: format!("s{}", i), artists: format!("A{}", i % 3), ..Default::default() });
        }
        let mut rng = Rng::new();
        let out = spread(&songs, &mut rng);
        assert_eq!(out.len(), 30);
        let set: HashSet<_> = out.iter().map(|t| t.uri.clone()).collect();
        assert_eq!(set.len(), 30);
        // 3 artists, 10 songs each: never 3 of the same in a row
        for w in out.windows(3) {
            assert!(!(w[0].artists == w[1].artists && w[1].artists == w[2].artists));
        }
    }
}

//! SpoX shuffle (same as SpoX for Android). SpoX decides the order itself instead of Spotify:
//!  - your list shuffled properly: every song once, duplicates merged, artists spread out;
//!  - new songs (not in your playlists, Liked Songs or recent plays) slipped in by the "new songs"
//!    share: 0% (just your list), 25, 50, 75 or 100% (only new songs). They come from Spotify's own
//!    autoplay picks, artists similar to what's playing (Deezer) and, with a key, Last.fm;
//!  - the order is saved, so closing SpoX (or the PC restarting) carries on where you were.
//! The same "deck" also plays a list in your own sort order with shuffle off (no new songs).
//! Like Spotify's own app, the order lives in SpoX: Soloist is told which song to play with local
//! commands (no Web API, so no request limits), next / previous move in SpoX's order, and about
//! 20 s before a song ends the next one goes into Spotify's queue so it follows gaplessly.
//! Runs on its own thread, so it works while SpoX is hidden.

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
    /// `order`: the same songs in the order you see them (sort / search), for "in order".
    Start { playlist: Playlist, tracks: Vec<Track>, order: Vec<Track>, first: Option<String>, shuffle: bool, position: u64 },
    /// next / previous in SpoX's order
    Next,
    Prev,
    /// the shuffle button while SpoX plays: shuffled or in order (the share decides new songs)
    Mode(bool),
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
/// Artists similar to `artist` (Deezer's free "related artists": no Spotify requests), shuffled.
fn similar_artists(artist: &str) -> Vec<String> {
    let get = |url: String| -> Option<Value> {
        net::agent()
            .get(&url)
            .timeout(Duration::from_secs(10))
            .call()
            .ok()?
            .into_json()
            .ok()
    };
    let Some(v) = get(format!(
        "https://api.deezer.com/search/artist?limit=1&q={}",
        enc(artist)
    )) else {
        return Vec::new();
    };
    let Some(id) = v.pointer("/data/0/id").and_then(|x| x.as_u64()) else {
        return Vec::new();
    };
    let Some(rel) = get(format!(
        "https://api.deezer.com/artist/{}/related?limit=25",
        id
    )) else {
        return Vec::new();
    };
    let mut out: Vec<String> = rel
        .get("data")
        .and_then(|d| d.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|x| {
                    x.get("name")
                        .and_then(|n| n.as_str())
                        .map(|s| s.to_string())
                })
                .collect()
        })
        .unwrap_or_default();
    let mut rng = Rng::new();
    for i in (1..out.len()).rev() {
        let j = rng.below(i + 1);
        out.swap(i, j);
    }
    out
}

#[allow(dead_code)]
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
}

/// SpoX shuffle's own log (what it played and why), next to web.log.
fn slog(line: &str) {
    use std::io::Write as _;
    let p = config::cache_dir().join("shuffle.log");
    if std::fs::metadata(&p).map(|m| m.len() > 256 * 1024).unwrap_or(false) {
        let _ = std::fs::rename(&p, p.with_extension("old.log"));
    }
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(p) {
        let _ = writeln!(f, "{} {}", now_ms(), line);
    }
}

/// Songs planned ahead (Up next shows this many); planning tops it up as it gets lower.
const AHEAD: usize = 100;
/// Songs kept behind the one playing (for going back).
const BEHIND: usize = 100;
/// The next song goes into Spotify's queue this long before the end (gapless, also while hidden).
const ARM_MS: f64 = 20_000.0;

static ON: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
static TX: std::sync::OnceLock<Mutex<Sender<Cmd>>> = std::sync::OnceLock::new();

/// Next / previous from anywhere (buttons, taskbar, media keys, hotkeys) while SpoX plays a list:
/// SpoX moves in its own order instead of Spotify's. True if it took the command.
pub fn divert(cmd: &str) -> bool {
    if !ON.load(std::sync::atomic::Ordering::SeqCst) {
        return false;
    }
    let c = if cmd == command("skip_next") {
        Cmd::Next
    } else if cmd == command("skip_prev") {
        Cmd::Prev
    } else {
        return false;
    };
    TX.get().map(|t| t.lock().unwrap().send(c).is_ok()).unwrap_or(false)
}

/// SpoX's own commands to Soloist carry this mark, so they're never diverted back.
pub const OWN: char = '\u{1}';

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
    /// the list in your order (sort / search), for "in order"
    ordered: Vec<Track>,
    /// the order: played songs (for going back) and ~100 planned ahead
    deck: Vec<Entry>,
    index: usize,
    /// the list's songs still to come in this round
    bag: VecDeque<Track>,
    need_new: usize,
    acc: f64,
    /// where SpoX told Soloist to go (deck index), when, and the spot to seek to
    target: Option<usize>,
    target_uri: String,
    target_at: Instant,
    target_pos: u64,
    /// the move is sent this long after the last press (quick presses become one move)
    go_at: Option<Instant>,
    fell_back: bool,
    /// the next song, already put in Spotify's queue
    armed: Option<String>,
    /// songs SpoX put in Spotify's queue but no longer wants: skipped if they start
    stale: std::collections::HashMap<String, usize>,
    /// songs queued from somewhere else (another device): Spotify plays them in between
    queued: Vec<Track>,
    /// with SpoX off: songs taken out of Spotify's own queue, skipped when they start
    removed: HashSet<String>,
    last_track: String,
    ended_for: String,
    last_tick: Instant,
    last_sec: Instant,
    pool: Arc<Mutex<VecDeque<Track>>>,
    library: Arc<Mutex<HashSet<String>>>,
    used: Arc<Mutex<HashSet<String>>>,
    new_uris: Vec<String>,
    play: Play,
    /// Soloist's own always-complete state (this thread only sees events from when it started,
    /// so "is SpoX the active device", the context etc. are taken from here)
    live: Arc<Mutex<Play>>,
    rng: Rng,
    finding: Arc<std::sync::atomic::AtomicUsize>,
    busy: String,
    /// the first seconds of a list with no new songs yet
    warm_until: Option<Instant>,
    /// spots in the plan still owed a new song: the song it goes after (uri, and its place then)
    owed: Vec<String>,
    last_disc: Instant,
}

pub fn start(cfg: Shared, ev: Sender<Ev>, ctx: egui::Context, solo: Sender<String>, live: Arc<Mutex<Play>>) -> (Sender<Cmd>, Sender<Value>) {
    let (tx, rx) = std::sync::mpsc::channel::<Cmd>();
    let (vtx, vrx) = std::sync::mpsc::channel::<Value>();
    let _ = TX.set(Mutex::new(tx.clone()));
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
                ordered: Vec::new(),
                deck: Vec::new(),
                index: 0,
                bag: VecDeque::new(),
                need_new: 0,
                acc: 0.0,
                target: None,
                target_uri: String::new(),
                target_at: Instant::now(),
                target_pos: 0,
                go_at: None,
                fell_back: false,
                armed: None,
                stale: Default::default(),
                queued: Vec::new(),
                removed: HashSet::new(),
                last_track: String::new(),
                ended_for: String::new(),
                last_tick: Instant::now(),
                last_sec: Instant::now(),
                pool: Default::default(),
                library: Default::default(),
                used: Default::default(),
                new_uris: Vec::new(),
                play: Play::default(),
                live,
                rng: Rng::new(),
                finding: Default::default(),
                busy: String::new(),
                warm_until: None,
                owed: Vec::new(),
                last_disc: Instant::now() - Duration::from_secs(60),
            };
            e.play = e.live.lock().unwrap().clone();
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
            match rx.recv_timeout(Duration::from_millis(200)) {
                Ok(Cmd::Start { playlist, tracks, order, first, shuffle, position }) => self.begin(playlist, tracks, order, first, shuffle, position),
                Ok(Cmd::Stop) => self.stop(),
                Ok(Cmd::Ev(v)) => {
                    let old = self.play.track.as_ref().map(|t| t.uri.clone());
                    self.play.apply(&v);
                    // the full picture (is_active, context, queue...) from Soloist's own state
                    let full = self.live.lock().unwrap().clone();
                    if full.track.is_some() {
                        self.play = full;
                    }
                    let changed = old != self.play.track.as_ref().map(|t| t.uri.clone());
                    if self.on {
                        self.harvest();
                        if changed {
                            self.on_track();
                        }
                    } else if changed {
                        // SpoX off: songs taken out of Spotify's own queue are skipped
                        let cur = self.play.track.as_ref().map(|t| t.uri.clone()).unwrap_or_default();
                        if self.removed.remove(&cur) {
                            self.send(command("skip_next"));
                        }
                    }
                }
                Ok(Cmd::Remove(uris)) => self.remove(&uris),
                Ok(Cmd::PlayNext(ts)) => self.play_next(ts),
                Ok(Cmd::Queued(t)) => self.play_next(vec![t]),
                Ok(Cmd::JumpTo(uri)) => self.jump_to(&uri),
                Ok(Cmd::Resume) => {
                    if self.on && !self.deck.is_empty() {
                        self.go(self.index, 0, true);
                    }
                }
                Ok(Cmd::Next) => self.next(),
                Ok(Cmd::Prev) => self.prev(),
                Ok(Cmd::Mode(shuffle)) => self.apply_mode(shuffle),
                Ok(Cmd::Ratio) => {
                    if self.on && self.shuffled {
                        self.replan(true);
                    }
                    self.report();
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                Err(_) => break,
            }
            ON.store(self.on, std::sync::atomic::Ordering::SeqCst);
            if self.go_at.map(|t| Instant::now() >= t).unwrap_or(false) {
                self.go_at = None;
                self.send_go();
            }
            if self.on && self.last_sec.elapsed() >= Duration::from_secs(1) {
                self.last_sec = Instant::now();
                self.arm();
                self.ended();
                self.fall_back();
            }
            if self.on && self.last_tick.elapsed() > Duration::from_secs(3) {
                self.last_tick = Instant::now();
                self.work();
            }
        }
    }

    /// The new-songs share in use: only with the shuffle button on "SpoX shuffle".
    fn ratio(&self) -> u32 {
        if !self.shuffled {
            return 0;
        }
        let c = self.cfg.lock().unwrap();
        if c.shuffle_mode != "spox" {
            return 0;
        }
        c.smart_ratio.min(100)
    }

    /// To Soloist, marked as SpoX's own (never diverted back here).
    fn send(&self, c: String) {
        let _ = self.solo.send(format!("{}{}", OWN, c));
    }

    fn osd(&self, s: impl Into<String>) {
        let _ = self.ev.send(Ev::Osd(s.into()));
        self.ctx.request_repaint();
    }

    fn base(&self) -> usize {
        self.target.unwrap_or(self.index)
    }

    fn report(&self) {
        let upcoming: Vec<(Track, String)> = if self.on {
            self.queued
                .iter()
                .map(|t| (t.clone(), "user".to_string()))
                .chain(self.deck.iter().skip(self.base() + 1).take(AHEAD).map(|e| (e.t.clone(), e.kind.clone())))
                .collect()
        } else {
            Vec::new()
        };
        let _ = self.ev.send(Ev::Smart {
            on: self.on,
            shuffled: self.shuffled,
            list: self.playlist.as_ref().map(|p| p.name.clone()).unwrap_or_default(),
            list_uri: self.playlist.as_ref().map(|p| p.uri.clone()).unwrap_or_default(),
            // what's marked new: the new songs still in the plan (none after switching shuffle off)
            new_uris: self.deck.iter().skip(self.index).filter(|e| e.kind == "new").map(|e| e.t.uri.clone()).collect(),
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

    fn work(&mut self) {
        if self.warm_until.is_some()
            && (self.pool.lock().unwrap().len() >= 6
                || self.warm_until.map(|t| Instant::now() >= t).unwrap_or(true))
        {
            self.warm_until = None;
            self.plan();
            self.rearm();
        }
        let hungry = self.plan();
        // only while this SpoX plays (not when the phone or another device does), and at most
        // every 15 s: the Spotify requests are shared with everything else SpoX does
        if self.ratio() > 0 && self.play.is_active && (hungry || self.pool.lock().unwrap().len() < 20) && self.last_disc.elapsed() > Duration::from_secs(15) {
            self.last_disc = Instant::now();
            self.discover();
        }
        self.save();
        self.report();
    }

    /// Fills the round: shuffled (spread, recent songs last), or your order after `from`.
    fn fill_bag(&mut self, from: Option<&str>) {
        self.bag.clear();
        if self.shuffled {
            let n = (self.seeds.len() / 2).min(self.deck.len());
            let mut recent: HashSet<String> = self.deck[self.deck.len() - n..].iter().map(|e| e.t.uri.clone()).collect();
            if let Some(f) = from {
                recent.insert(f.to_string());
            }
            let fresh = spread(&self.seeds, &mut self.rng);
            let (later, now): (Vec<Track>, Vec<Track>) = fresh.into_iter().partition(|t| recent.contains(&t.uri));
            self.bag.extend(now);
            self.bag.extend(later.into_iter().filter(|t| Some(t.uri.as_str()) != from));
        } else {
            let at = from.and_then(|f| self.ordered.iter().position(|t| t.uri == f));
            match at {
                Some(i) => self.bag.extend(self.ordered[i + 1..].iter().cloned()),
                None => self.bag.extend(self.ordered.iter().cloned()),
            }
        }
    }

    fn begin(&mut self, playlist: Playlist, tracks: Vec<Track>, order: Vec<Track>, first: Option<String>, shuffle: bool, position: u64) {
        slog(&format!("start {} ({} songs given, first {:?}, shuffle {}, ratio {})", playlist.name, tracks.len(), first, shuffle, self.ratio()));
        // a list played from the library without its songs loaded: load them here
        let (tracks, order) = if tracks.is_empty() {
            self.busy = format!("Loading {}...", playlist.name);
            self.report();
            let (t, _) = self.api.fetch_all(&playlist);
            self.busy.clear();
            let mut o = t.clone();
            o.sort_by_key(|x| x.order);
            (t, o)
        } else {
            (tracks, order)
        };
        let mut seen = HashSet::new();
        let clean: Vec<Track> = tracks.into_iter().filter(|t| t.uri.starts_with("spotify:track:") && seen.insert(key(&t.name, &t.artists))).collect();
        if clean.is_empty() {
            self.osd("NO SONGS TO PLAY IN THIS LIST");
            return;
        }
        let uris: HashSet<String> = clean.iter().map(|t| t.uri.clone()).collect();
        let mut oseen = HashSet::new();
        let ordered: Vec<Track> = order.into_iter().filter(|t| uris.contains(&t.uri) && oseen.insert(t.uri.clone())).collect();
        let playing_now = self.play.track.as_ref().map(|t| t.uri.clone());
        let carry_on = first.is_some() && first == playing_now;
        // keep what's been played (going back still works)
        self.deck.truncate((self.index + 1).min(self.deck.len()));
        self.on = true;
        self.shuffled = shuffle;
        self.playlist = Some(playlist);
        self.queued.clear();
        self.removed.clear();
        self.new_uris.clear();
        self.pool.lock().unwrap().clear();
        self.acc = 0.0;
        self.need_new = 0;
        self.owed.clear();
        self.drop_armed();
        {
            let mut used = self.used.lock().unwrap();
            used.clear();
            used.extend(clean.iter().map(|t| key(&t.name, &t.artists)));
            used.extend(self.deck.iter().map(|e| key(&e.t.name, &e.t.artists)));
            self.library.lock().unwrap().extend(used.iter().cloned());
        }
        self.load_library();
        self.ordered = if ordered.is_empty() { clean.clone() } else { ordered };
        self.seeds = clean;
        let start_with = first.as_ref().and_then(|f| self.seeds.iter().find(|t| &t.uri == f).cloned());
        self.fill_bag(start_with.as_ref().map(|t| t.uri.as_str()));
        let r = self.ratio();
        self.load_found();
        self.warm_until = if r > 0 && self.pool.lock().unwrap().len() < 10 { Some(Instant::now() + Duration::from_secs(10)) } else { None };
        let t = start_with.clone().or_else(|| if r >= 100 { self.pick_new("") } else { None }).or_else(|| self.bag.pop_front());
        let Some(t) = t else {
            self.stop();
            return;
        };
        if self.play.shuffle {
            self.send(command_with("set_shuffle", "enabled", json!(false)));
        }
        if self.play.repeat == "context" {
            self.send(command_with("set_repeat_context", "enabled", json!(false)));
        }
        let same_spot = carry_on && self.deck.get(self.index).map(|e| e.t.uri == t.uri).unwrap_or(false);
        if !same_spot {
            let kind = if self.new_uris.contains(&t.uri) { "new" } else { "list" };
            self.deck.push(Entry { t: t.clone(), kind: kind.into() });
            self.index = self.deck.len() - 1;
        }
        self.plan();
        if carry_on {
            // the song playing is the start: it simply carries on, only what follows changes
            self.target = None;
            self.last_track = t.uri.clone();
        } else {
            self.go(self.index, if start_with.is_some() { position } else { 0 }, true);
        }
        self.osd(if !shuffle {
            "IN ORDER".to_string()
        } else if r == 0 {
            "SHUFFLE ON".to_string()
        } else {
            format!("SPOX SHUFFLE \u{b7} {}% NEW", r)
        });
        self.work();
    }

    /// The shuffle button while SpoX plays a list: what's ahead is planned again at once.
    fn apply_mode(&mut self, shuffle: bool) {
        if !self.on || self.seeds.is_empty() {
            return;
        }
        self.shuffled = shuffle;
        if self.ratio() > 0 {
            self.load_found();
            self.warm_until = if self.pool.lock().unwrap().len() < 10 { Some(Instant::now() + Duration::from_secs(10)) } else { None };
        }
        self.replan(false);
    }

    /// Throws away the plan after the song playing (your own picks stay first) and plans again.
    fn replan(&mut self, keep_order: bool) {
        let ahead: Vec<Entry> = self.deck.drain(self.index + 1..).collect();
        self.deck.extend(ahead.iter().filter(|e| e.kind == "user").cloned());
        self.need_new = 0;
        self.acc = 0.0;
        self.owed.clear();
        if keep_order {
            self.bag = ahead.into_iter().filter(|e| e.kind == "list").map(|e| e.t).collect();
        } else {
            let cur = self.deck.get(self.index).map(|e| e.t.uri.clone());
            self.fill_bag(cur.as_deref());
        }
        self.plan();
        self.rearm();
        self.save();
        self.report();
    }

    fn stop(&mut self) {
        slog("stopped");
        self.on = false;
        ON.store(false, std::sync::atomic::Ordering::SeqCst);
        self.deck.clear();
        self.index = 0;
        self.bag.clear();
        self.queued.clear();
        self.target = None;
        self.go_at = None;
        self.owed.clear();
        self.drop_armed();
        self.busy.clear();
        self.save();
        self.report();
    }

    // ---- moving ----

    /// Go to deck[i]: sent 250 ms after the last press, so quick presses become one move.
    fn go(&mut self, i: usize, position_ms: u64, now: bool) {
        if self.deck.is_empty() {
            return;
        }
        let j = i.min(self.deck.len() - 1);
        self.target = Some(j);
        self.target_uri = self.deck[j].t.uri.clone();
        self.target_at = Instant::now();
        self.target_pos = position_ms;
        self.fell_back = false;
        hold_for(Duration::from_secs(8));
        self.go_at = Some(Instant::now() + if now { Duration::ZERO } else { Duration::from_millis(250) });
        self.report();
    }

    fn send_go(&mut self) {
        let Some(t) = self.target else { return };
        let u = self.target_uri.clone();
        // the next song is already first in Spotify's queue: just skip to it (gapless, instant)
        let head = self.play.upcoming.iter().find(|x| x.provider.contains("queue")).map(|x| x.uri.clone());
        let skip = self.armed.as_deref() == Some(u.as_str()) && t == self.index + 1 && head.as_deref() == Some(u.as_str()) && self.queued.is_empty();
        if skip {
            self.armed = None;
        } else {
            self.drop_armed();
        }
        self.target_at = Instant::now();
        if !self.play.is_active {
            self.send(command("activate"));
        }
        slog(&format!("go {} -> {} ({})", t, u, if skip { "skip to the queued one" } else { "play" }));
        if skip {
            self.send(command("skip_next"));
        } else {
            self.send(command_with("play", "uri", json!(u)));
        }
    }

    /// Soloist didn't take the move within 5 s (not the active device yet...): once through Spotify.
    fn fall_back(&mut self) {
        if self.fell_back || self.go_at.is_some() {
            return;
        }
        if self.target.is_some() && self.target_at.elapsed() > Duration::from_secs(5) {
            self.fell_back = true;
            slog(&format!("Soloist didn't start {} in 5 s: asking Spotify", self.target_uri));
            if let Err(e) = self.api.play_uris(&self.device(), &[self.target_uri.clone()], self.target_pos, 0) {
                crate::web::web_log(&format!("SpoX: play failed: {}", e));
            }
        }
    }

    fn next(&mut self) {
        // songs queued from another device play first, like Spotify
        if !self.queued.is_empty() && self.play.upcoming.first().map(|t| self.queued.iter().any(|q| q.uri == t.uri)).unwrap_or(false) {
            self.send(command("skip_next"));
            return;
        }
        let b = self.base();
        if b + 1 >= self.deck.len() {
            self.plan();
        }
        if b + 1 < self.deck.len() {
            self.go(b + 1, 0, false);
        }
    }

    /// Back: to the start of the song, or (in its first 3 s) the song before.
    fn prev(&mut self) {
        let pos = self.play.position(now_ms());
        let b = self.base();
        if (self.target.is_none() && pos > 3000.0) || b == 0 {
            self.send(command_with("seek", "position_ms", json!(0)));
            return;
        }
        self.go(b - 1, 0, false);
    }

    /// Jump to a song in Up next: the songs before it are passed over (like Spotify).
    fn jump_to(&mut self, uri: &str) {
        if let Some(i) = self.queued.iter().position(|t| t.uri == uri) {
            let t = self.queued.remove(i);
            self.deck.insert(self.index + 1, Entry { t, kind: "user".into() });
        }
        let Some(i) = (self.index + 1..self.deck.len()).find(|&k| self.deck[k].t.uri == uri) else { return };
        self.deck.drain(self.index + 1..i);
        self.go(self.index + 1, 0, true);
        self.work();
    }

    fn remove(&mut self, uris: &[String]) {
        let set: HashSet<&String> = uris.iter().collect();
        if !self.on {
            self.removed.extend(uris.iter().cloned());
            return;
        }
        let mut j = self.deck.len();
        while j > self.index + 1 {
            j -= 1;
            if set.contains(&self.deck[j].t.uri) {
                self.deck.remove(j);
            }
        }
        self.queued.retain(|t| !set.contains(&t.uri));
        self.rearm();
        self.work();
    }

    /// Play next: right after this song (after songs you picked before), in SpoX's own order.
    fn play_next(&mut self, ts: Vec<Track>) {
        if !self.on || self.deck.is_empty() {
            return;
        }
        let set: HashSet<String> = ts.iter().map(|t| t.uri.clone()).collect();
        let mut j = self.deck.len();
        while j > self.index + 1 {
            j -= 1;
            if set.contains(&self.deck[j].t.uri) {
                self.deck.remove(j);
            }
        }
        let mut at = self.index + 1;
        while at < self.deck.len() && self.deck[at].kind == "user" {
            at += 1;
        }
        for (k, t) in ts.into_iter().enumerate() {
            self.deck.insert(at + k, Entry { t, kind: "user".into() });
        }
        self.rearm();
        self.work();
    }

    // ---- Spotify's queue: only the very next song, only near the end ----

    fn drop_armed(&mut self) {
        if let Some(a) = self.armed.take() {
            *self.stale.entry(a).or_insert(0) += 1;
        }
    }

    fn rearm(&mut self) {
        let want = self.deck.get(self.index + 1).map(|e| e.t.uri.clone());
        if self.armed.is_some() && self.armed != want {
            self.drop_armed();
        }
        self.arm();
    }

    fn arm(&mut self) {
        if self.target.is_some() || self.play.repeat == "track" || !self.play.is_active {
            return;
        }
        let Some(t) = self.play.track.as_ref() else { return };
        let (tu, ck) = (t.uri.clone(), key(&t.name, &t.artists));
        if !self.is_at(self.index, &tu, &ck) {
            return;
        }
        let Some(want) = self.deck.get(self.index + 1).map(|e| e.t.uri.clone()) else { return };
        let left = t.duration_ms as f64 - self.play.position(now_ms());
        if t.duration_ms == 0 || left > ARM_MS || self.armed.as_deref() == Some(want.as_str()) {
            return;
        }
        self.drop_armed();
        slog(&format!("queued the next song: {}", want));
        self.armed = Some(want.clone());
        self.send(command_with("add_to_queue", "uri", json!(want)));
    }

    /// The song ended with nothing after it (or Spotify stopped): play the next one.
    fn ended(&mut self) {
        if self.target.is_some() || !self.play.is_active {
            return;
        }
        let Some(t) = self.play.track.clone() else { return };
        if t.uri == self.ended_for || self.play.status == "playing" || self.play.status == "buffering" {
            return;
        }
        let at_end = t.duration_ms > 0 && self.play.position(now_ms()) >= t.duration_ms as f64 - 1200.0;
        if !at_end || !self.is_at(self.index, &t.uri, &key(&t.name, &t.artists)) {
            return;
        }
        self.ended_for = t.uri;
        self.next();
    }

    // ---- following what plays ----

    /// Is deck[k] the song playing? By its link, or by title + artist: Spotify sometimes plays a
    /// song under another link than the one asked for (the same song on another release).
    fn is_at(&self, k: usize, uri: &str, ck: &str) -> bool {
        self.deck.get(k).map(|e| e.t.uri == uri || key(&e.t.name, &e.t.artists) == ck).unwrap_or(false)
    }

    fn cur_key(&self) -> String {
        self.play.track.as_ref().map(|t| key(&t.name, &t.artists)).unwrap_or_default()
    }

    fn nearest(&self, uri: &str) -> Option<usize> {
        let ck = self.cur_key();
        let lo = self.index.saturating_sub(BEHIND);
        (lo..self.deck.len()).filter(|&k| self.is_at(k, uri, &ck)).min_by_key(|&k| (k as i64 - self.index as i64 - 1).abs())
    }

    fn fallthrough(&self) -> bool {
        let c = &self.play.context_uri;
        c.is_empty()
            || c.contains("station")
            || c.contains("autoplay")
            || c.contains("radio")
            || (c.starts_with("spotify:track:") && self.deck.iter().any(|e| &e.t.uri == c))
            || self.playlist.as_ref().map(|p| &p.uri == c).unwrap_or(false)
    }

    /// You started another list / album / artist (in SpoX or another Spotify app): SpoX hands over.
    /// Anything else unexpected (a stray song, a slow report) never throws the order away.
    fn other_list(&self) -> bool {
        let c = &self.play.context_uri;
        let list = ["spotify:playlist:", "spotify:album:", "spotify:artist:", "spotify:show:", ":collection"].iter().any(|k| c.contains(k));
        list && self.playlist.as_ref().map(|p| &p.uri != c).unwrap_or(true)
    }

    fn on_track(&mut self) {
        let cur = self.play.track.as_ref().map(|t| t.uri.clone()).unwrap_or_default();
        if cur.is_empty() || cur == self.last_track {
            return;
        }
        self.last_track = cur.clone();
        self.ended_for.clear();
        // another device plays (SpoX on the phone, the Spotify app...): its songs are none of this
        // SpoX's business; reacting to them made the two take the music from each other
        if self.target.is_none() && !self.play.is_active {
            slog(&format!("another device plays {}: leaving it alone", cur));
            return;
        }
        let ck = self.cur_key();
        slog(&format!("now: {} (target {:?}, index {}, context {})", cur, self.target, self.index, self.play.context_uri));
        let mut fix = false;
        if let Some(t) = self.target {
            if cur == self.target_uri || self.is_at(t, &cur, &ck) {
                self.index = t;
                self.target = None;
                if self.target_pos > 0 {
                    self.send(command_with("seek", "position_ms", json!(self.target_pos)));
                    self.target_pos = 0;
                }
            } else if self.target_at.elapsed() < Duration::from_secs(8) {
                // a move is on its way: Spotify may still report the old song for a moment
                return;
            } else {
                self.target = None;
            }
        }
        if self.target.is_none() && self.index < self.deck.len() && !self.is_at(self.index, &cur, &ck) {
            if let Some(i) = self.queued.iter().position(|t| t.uri == cur) {
                self.queued.remove(i);
            } else if self.armed.is_some() && (self.armed.as_deref() == Some(cur.as_str()) || self.is_at(self.index + 1, &cur, &ck)) {
                self.armed = None;
                if let Some(i) = self.nearest(&cur) {
                    self.index = i;
                }
            } else if let Some(n) = self.stale.get_mut(&cur) {
                *n -= 1;
                if *n == 0 {
                    self.stale.remove(&cur);
                }
                fix = true;
            } else if let Some(i) = self.nearest(&cur) {
                self.index = i;
                let want = self.deck.get(i + 1).map(|e| e.t.uri.clone());
                if self.armed.is_some() && self.armed != want {
                    self.drop_armed();
                }
            } else if self.fallthrough() {
                // Spotify's own pick after a song, or next pressed on another device
                fix = true;
            } else if self.other_list() && now_ms() >= HOLD_UNTIL.load(std::sync::atomic::Ordering::SeqCst) {
                slog(&format!("another list started ({}): SpoX hands over", self.play.context_uri));
                // you played something else: SpoX hands back to Spotify
                self.stop();
                return;
            }
        }
        if self.index > BEHIND + 50 {
            let cut = self.index - BEHIND;
            self.deck.drain(..cut);
            self.index -= cut;
        }
        if fix {
            self.go(self.index + 1, 0, true);
        }
        self.work();
    }

    /// Fills the order up to AHEAD songs ahead. True while new songs are owed but not found yet.
    /// Puts found new songs into the owed spots, only further ahead than the next song: nothing
    /// already planned moves or disappears, new songs just appear between them.
    fn fill_owed(&mut self) {
        if self.owed.is_empty() {
            return;
        }
        if self.ratio() == 0 {
            self.owed.clear();
            return;
        }
        let mut added = false;
        while let Some(u) = self.owed.first().cloned() {
            let pos = (self.index + 2..self.deck.len()).find(|&k| self.deck[k].t.uri == u);
            let Some(pos) = pos else {
                self.owed.remove(0);
                continue;
            };
            let artist = self.deck[pos].t.artists.clone();
            let Some(t) = self.pick_new(&artist) else { break };
            self.deck.insert(pos + 1, Entry { t, kind: "new".into() });
            self.owed.remove(0);
            added = true;
        }
        if added {
            let want = self.deck.get(self.index + 1).map(|e| e.t.uri.clone());
            if self.armed.is_some() && self.armed != want {
                self.drop_armed();
            }
        }
    }

    fn plan(&mut self) -> bool {
        let r = self.ratio();
        self.fill_owed();
        let mut hungry = !self.owed.is_empty();
        while self.deck.len() - self.index - 1 < AHEAD {
            if r >= 100 || self.need_new > 0 {
                let last = self.deck.last().map(|e| e.t.artists.clone()).unwrap_or_default();
                if let Some(t) = self.pick_new(&last) {
                    self.deck.push(Entry { t, kind: "new".into() });
                    self.need_new = self.need_new.saturating_sub(1);
                    continue;
                }
                hungry = true;
                // none found yet: the list carries on (never waits), and the new songs owed here
                // are slotted in at this spot once found (the share stays even)
                if let Some(e) = self.deck.last().filter(|_| self.owed.len() < 40) {
                    for _ in 0..self.need_new.max(1) {
                        self.owed.push(e.t.uri.clone());
                    }
                }
                self.need_new = 0;
                if r >= 100 && self.bag.is_empty() && self.seeds.is_empty() {
                    break;
                }
            }
            if self.bag.is_empty() {
                if self.seeds.is_empty() {
                    break;
                }
                // the whole round played: another one (shuffled again, or your order from the top)
                if self.shuffled {
                    self.fill_bag(None);
                } else {
                    self.bag.extend(self.ordered.iter().cloned());
                }
            }
            let Some(t) = self.bag.pop_front() else { break };
            self.deck.push(Entry { t, kind: "list".into() });
            if r > 0 && r < 100 {
                self.acc += r as f64 / (100 - r) as f64;
                while self.acc >= 1.0 {
                    self.need_new += 1;
                    self.acc -= 1.0;
                }
            }
        }
        hungry
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

    fn found_path(&self) -> Option<std::path::PathBuf> {
        let p = self.playlist.as_ref()?;
        let name: String = p
            .uri
            .chars()
            .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
            .collect();
        Some(
            config::config_dir()
                .join("found")
                .join(format!("{}.json", name)),
        )
    }

    /// New songs found for this list before (kept so starting it again is instant).
    fn load_found(&mut self) {
        let Some(path) = self.found_path() else {
            return;
        };
        let Some(Value::Array(a)) = std::fs::read(&path)
            .ok()
            .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        else {
            return;
        };
        let mut ts: Vec<Track> = a
            .iter()
            .map(|e| Track {
                uri: e[0].as_str().unwrap_or("").into(),
                name: e[1].as_str().unwrap_or("").into(),
                artists: e[2].as_str().unwrap_or("").into(),
                ..Default::default()
            })
            .collect();
        for i in (1..ts.len()).rev() {
            let j = self.rng.below(i + 1);
            ts.swap(i, j);
        }
        let fresh: Vec<Track> = ts.into_iter().filter(|t| !self.is_known(t)).collect();
        let mut pool = self.pool.lock().unwrap();
        for t in fresh {
            if !pool.iter().any(|p| p.uri == t.uri) {
                pool.push_back(t);
            }
        }
    }

    /// Spotify's autoplay suggestions show up in the queue: also used as new songs.
    fn harvest(&mut self) {
        if self.ratio() == 0 {
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

    /// Like a radio: mostly the last list song planned (new songs go after it), sometimes what's
    /// playing or any song of the list.
    fn seed(&mut self) -> Option<Track> {
        let roll = self.rng.below(10);
        if roll < 6 {
            if let Some(e) = self.deck.iter().rev().find(|e| e.kind == "list") {
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

    /// One worker looks for new songs (its Spotify requests use the slow background lane).
    fn discover(&mut self) {
        use std::sync::atomic::Ordering;
        if self.finding.load(Ordering::SeqCst) > 0 || self.pool.lock().unwrap().len() >= 30 {
            return;
        }
        let Some(seed) = self.seed() else { return };
        self.finding.fetch_add(1, Ordering::SeqCst);
        let job = self.job(seed);
        std::thread::spawn(move || job.run());
    }

    fn job(&self, seed: Track) -> Discover {
        Discover {
            seed,
            lastfm: self.cfg.lock().unwrap().lastfm_key.trim().to_string(),
            pool: self.pool.clone(),
            lib: self.library.clone(),
            used: self.used.clone(),
            api: self.api.clone(),
            finding: self.finding.clone(),
            found: self.found_path(),
        }
    }

    // ---- saved between runs ----

    fn save(&self) {
        let deck: Vec<Value> = self.deck.iter().map(|e| json!([e.t.uri, e.t.name, e.t.artists, e.t.album, e.t.duration_ms, e.kind])).collect();
        let bag: Vec<Value> = self.bag.iter().map(|t| json!([t.uri, t.name, t.artists, t.album, t.duration_ms])).collect();
        let seeds: Vec<Value> = self.seeds.iter().take(3000).map(|t| json!([t.uri, t.name, t.artists])).collect();
        let ordered: Vec<&String> = self.ordered.iter().take(3000).map(|t| &t.uri).collect();
        let p = self.playlist.clone().unwrap_or_default();
        let v = json!({
            "v": 4, "on": self.on, "shuffled": self.shuffled, "index": self.index, "need": self.need_new, "acc": self.acc,
            "list": [p.uri, p.id, p.name, p.total, p.owned], "deck": deck, "bag": bag, "seeds": seeds, "ordered": ordered,
        });
        let path = state_path();
        let tmp = path.with_extension("tmp");
        if std::fs::write(&tmp, v.to_string()).is_ok() {
            let _ = std::fs::rename(&tmp, &path);
        }
    }

    fn restore(&mut self) {
        let Some(v) = std::fs::read(state_path()).ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok()) else { return };
        if !v["on"].as_bool().unwrap_or(false) || v["v"].as_u64().unwrap_or(0) < 3 {
            return;
        }
        let s = |x: &Value| x.as_str().unwrap_or("").to_string();
        let track = |e: &Value| Track { uri: s(&e[0]), name: s(&e[1]), artists: s(&e[2]), album: s(&e[3]), duration_ms: e[4].as_u64().unwrap_or(0), ..Default::default() };
        self.deck = v["deck"].as_array().map(|a| a.iter().map(|e| Entry { t: track(e), kind: e[5].as_str().unwrap_or("list").to_string() }).collect()).unwrap_or_default();
        if self.deck.is_empty() {
            return;
        }
        self.bag = v["bag"].as_array().map(|a| a.iter().map(track).collect()).unwrap_or_default();
        self.index = (v["index"].as_u64().unwrap_or(0) as usize).min(self.deck.len() - 1);
        self.need_new = v["need"].as_u64().unwrap_or(0) as usize;
        self.acc = v["acc"].as_f64().unwrap_or(0.0);
        self.on = true;
        ON.store(true, std::sync::atomic::Ordering::SeqCst);
        self.shuffled = v["shuffled"].as_bool().unwrap_or(true);
        let l = &v["list"];
        self.playlist = Some(Playlist { uri: s(&l[0]), id: s(&l[1]), name: s(&l[2]), total: l[3].as_u64().unwrap_or(0) as u32, owned: l[4].as_bool().unwrap_or(false) });
        self.seeds = v["seeds"].as_array().map(|a| a.iter().map(|e| Track { uri: s(&e[0]), name: s(&e[1]), artists: s(&e[2]), ..Default::default() }).collect()).unwrap_or_default();
        let by: std::collections::HashMap<String, Track> = self.seeds.iter().map(|t| (t.uri.clone(), t.clone())).collect();
        self.ordered = v["ordered"].as_array().map(|a| a.iter().filter_map(|u| by.get(u.as_str().unwrap_or("")).cloned()).collect()).unwrap_or_default();
        if self.ordered.is_empty() {
            self.ordered = self.seeds.clone();
        }
        self.last_track = self.deck[self.index].t.uri.clone();
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
    /// how many look-ups run (the caller counted this one in already)
    finding: Arc<std::sync::atomic::AtomicUsize>,
    /// where the songs found for this list are kept
    found: Option<std::path::PathBuf>,
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
        let mut found: Vec<Track> = Vec::new();
        // five artists similar to the seed (Deezer), then ONE Spotify search per artist that
        // brings ten of their songs; up to three you don't know are kept per artist. (Before,
        // every song cost its own search, which ran into Spotify's request limit.)
        let seed_artist = first_artist_raw(&self.seed.artists);
        let mut artists = similar_artists(&seed_artist);
        artists.truncate(5);
        slog(&format!("looking for new songs like {}: {:?}", seed_artist, artists));
        if artists.is_empty() {
            artists.push(seed_artist);
        }
        for a in artists {
            let mut ts: Vec<Track> = self
                .api
                .search_artist(&a)
                .into_iter()
                .filter(|t| first_artist(&t.artists) == a.to_lowercase())
                .collect();
            let mut rng = Rng::new();
            for i in (1..ts.len()).rev() {
                let j = rng.below(i + 1);
                ts.swap(i, j);
            }
            let mut n = 0;
            for t in ts {
                if n >= 3 {
                    break;
                }
                if self.add(t.clone()) {
                    found.push(t);
                    n += 1;
                }
            }
        }
        if !self.lastfm.is_empty() && found.len() < 3 {
            // optional: Last.fm "similar tracks" for the seed song
            let url = format!(
                "https://ws.audioscrobbler.com/2.0/?method=track.getsimilar&autocorrect=1&limit=20&format=json&api_key={}&artist={}&track={}",
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
            for (name, artist) in list.into_iter().take(4) {
                if self.known(&key(&name, &artist)) {
                    continue;
                }
                if let Some(t) = self.api.search_track(&artist, &name) {
                    if self.add(t.clone()) {
                        found.push(t);
                    }
                }
            }
        }
        slog(&format!("found {} new songs{}", found.len(), if crate::web::cooling_now() { " (Spotify asked SpoX to wait)" } else { "" }));
        // keep what was found for this list (up to 400), so the next start is instant
        if let (Some(path), false) = (&self.found, found.is_empty()) {
            let mut all: Vec<Value> = std::fs::read(path)
                .ok()
                .and_then(|b| serde_json::from_slice::<Vec<Value>>(&b).ok())
                .unwrap_or_default();
            for t in &found {
                if !all.iter().any(|e| e[0].as_str() == Some(t.uri.as_str())) {
                    all.push(json!([t.uri, t.name, t.artists]));
                }
            }
            let skip = all.len().saturating_sub(400);
            let _ = std::fs::create_dir_all(path.parent().unwrap());
            let _ = std::fs::write(
                path,
                Value::Array(all.into_iter().skip(skip).collect()).to_string(),
            );
        }
        self.finding.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
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

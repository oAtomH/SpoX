use crate::config::{self, now_ms, Config, Shared};
use crate::skin::Skin;
use crate::soloist::{self, command, command_with};
use crate::state::{Ev, Lyrics, Play, Playlist, Track};
use crate::vis::Vis;
use crate::web::{self, Req};
use crate::{art, lyrics, net};
use eframe::egui::{self, Color32, Pos2, Rect, Vec2};
use serde_json::json;
use std::sync::mpsc::{Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, PartialEq, Debug)]
enum Tab {
    Playlist,
    Queue,
    Lyrics,
    History,
}

#[derive(Clone, Copy, PartialEq)]
enum Drag {
    None,
    Seek,
    Vol,
    Scroll,
}

/// Pointer state in skin pixels.
#[derive(Default, Clone)]
struct Inp {
    pos: Option<Pos2>,
    pressed: bool,
    released: bool,
    down: bool,
    dbl: bool,
    right: bool,
    middle: bool,
    wheel: f32,
    ctrl: bool,
}

impl Inp {
    fn over(&self, x: f32, y: f32, w: f32, h: f32) -> bool {
        self.pos.map(|p| p.x >= x && p.x < x + w && p.y >= y && p.y < y + h).unwrap_or(false)
    }
}

struct Collapse {
    t0: Instant,
    kind: String,
    /// true: hide to tray, false: minimize
    to_tray: bool,
}

pub struct App {
    cfg: Shared,
    skin: std::sync::Arc<Skin>,
    rx: Receiver<Ev>,
    tx: Sender<Ev>,
    ctx: egui::Context,
    solo: soloist::Ctl,
    web: Sender<Req>,
    play: Play,
    connected: bool,
    status: String,
    osd: Option<(String, Instant)>,
    art_tex: Option<egui::TextureHandle>,
    art_url: String,
    art_key: String,
    lyrics: Option<Lyrics>,
    lyrics_uri: String,
    lyrics_loading: bool,
    lyrics_since: Instant,
    liked_checked: Instant,
    /// the next song whose lyrics were looked up ahead of time
    lyrics_next: String,
    lyric_offset: i64,
    lyric_rows: Vec<(usize, String)>,
    lyric_rows_key: String,
    manual_lyrics_until: Option<Instant>,
    playlists: Vec<Playlist>,
    sel: Option<Playlist>,
    tracks: Vec<Track>,
    tracks_for: String,
    tracks_listable: bool,
    tracks_loading: bool,
    picker: bool,
    focus: Option<Tab>,
    pinned: [bool; 4],
    history: Vec<(Track, i64)>,
    hist_scroll_sel: Option<usize>,
    mem_text: String,
    mem_at: Option<Instant>,
    contents: std::collections::HashMap<String, std::collections::HashSet<String>>,
    drag_scroll: usize,
    hover_now: Option<String>,
    hover_id: Option<String>,
    hover_since: Instant,
    liked: Option<bool>,
    liked_uri: String,
    add_menu: bool,
    add_scroll: usize,
    profile_name: String,
    pfp: Option<egui::TextureHandle>,
    pfp_small: Option<egui::TextureHandle>,
    refresh_at: Option<Instant>,
    seek_hover: Option<f64>,
    muted_from: Option<u8>,
    collapse: Option<Collapse>,
    install_page: bool,
    update_ready: Option<String>,
    pl_drop: bool,
    drop_scroll: usize,
    drop_fresh: bool,
    quitting_for_install: bool,
    smart: Option<Sender<crate::smart::Cmd>>,
    smart_on: bool,
    smart_new: std::collections::HashSet<String>,
    smart_shuffled: bool,
    smart_list: String,
    smart_upcoming: Vec<(Track, String)>,
    smart_busy: String,
    /// Up next as shown (SpoX shuffle's own order, or Soloist's queue)
    q_list: Vec<Track>,
    /// Up next songs picked with Ctrl+click (remove / play next)
    q_picked: std::collections::HashSet<String>,
    /// songs taken out of Up next (hidden; skipped when they come up)
    q_removed: std::collections::HashSet<String>,
    /// the open playlist's songs in its own order (self.tracks = sorted + searched view of it)
    tracks_all: Vec<Track>,
    /// how the open playlist is sorted: added / title / artist / album / length / custom
    pl_sort: String,
    /// typing to find in the open playlist (None = not finding)
    pl_find: Option<String>,
    /// last time the whole Up next list was asked for (it's refreshed while you look at it)
    queue_asked: Instant,
    tb: Option<crate::tb::TbShared>,
    hold_shuffle: Option<(bool, Instant)>,
    hold_repeat: Option<(String, Instant)>,
    settings: bool,
    settings_scroll: f32,
    ctx_menu: Option<CtxMenu>,
    add_target: Option<String>,
    hide_on_start: bool,
    q_selected: Option<usize>,
    right_used: bool,
    scroll: [f32; 5],
    selected: Option<usize>,
    pressed: Option<&'static str>,
    drag: Drag,
    seek_preview: f64,
    vol_preview: f32,
    vol_sent: Instant,
    menu: bool,
    setup: bool,
    setup_focus: Option<usize>,
    setup_fields: [String; 3],
    vis: Vis,
    sized: Option<(f32, f32)>,
    t0: Instant,
    win_pos: Option<[f32; 2]>,
    native_ppp: f32,
    signed_in: bool,
    _tray: Option<crate::desktop::Tray>,
}

#[derive(Clone)]
enum CtxAct {
    PlayTrack(usize),
    /// take these out of Up next
    RemoveQ(Vec<String>),
    /// sort the open playlist the next way
    Sort,
    /// type to find in the open playlist
    Find,
    /// play these right after the current song
    PlayNextQ(Vec<String>),
    PlayUri(String),
    QueueTrack(String),
    SkipTo(usize),
    Like(String),
    AddTo(String),
    CopyLink(String),
}

struct CtxMenu {
    x: f32,
    y: f32,
    items: Vec<(String, CtxAct)>,
}

const SCROLL_PL: usize = 0;
const SCROLL_Q: usize = 1;
const SCROLL_LY: usize = 2;
const SCROLL_PICK: usize = 3;
const SCROLL_HIST: usize = 4;

fn fmt_time(ms: f64) -> String {
    let s = (ms / 1000.0).max(0.0) as u64;
    format!("{}:{:02}", s / 60, s % 60)
}

impl App {
    pub fn new(cc: &eframe::CreationContext) -> Self {
        let ctx = cc.egui_ctx.clone();
        let cfg_v = Config::load();
        let first_run = cfg_v.soloist_key.trim().is_empty();
        let signed_in = !cfg_v.refresh_token.is_empty();
        let tab = match cfg_v.tab.as_str() {
            "queue" => Tab::Queue,
            "lyrics" => Tab::Lyrics,
            _ => Tab::Playlist,
        };
        let setup_fields = [cfg_v.soloist_key.clone(), cfg_v.client_id.clone(), cfg_v.lastfm_key.clone()];
        let pinned = [cfg_v.pinned[0], cfg_v.pinned[1], cfg_v.pinned[2], cfg_v.pinned_history];
        let saved_name = cfg_v.profile_name.clone();
        let cfg: Shared = Arc::new(Mutex::new(cfg_v));
        let (tx, rx) = std::sync::mpsc::channel();
        let skin = std::sync::Arc::new(Skin::load(&ctx));
        let bars = skin.num("/vis/bars", 18.0) as usize;
        let demo = std::env::var("SPOX_DEMO").is_ok();
        crate::desktop::set_hwnd(cc);
        let solo = if demo {
            soloist::Ctl { cmd: std::sync::mpsc::channel().0, tap: Default::default(), live: Default::default() }
        } else {
            soloist::start(cfg.clone(), tx.clone(), ctx.clone())
        };
        let mut tray = None;
        let mut smart_tx = None;
        let hide_on_start = !demo && (std::env::args().any(|a| a == "--tray") || cfg.lock().unwrap().start_in_tray);
        if !demo {
            let (stx, vtx) = crate::smart::start(cfg.clone(), tx.clone(), ctx.clone(), solo.cmd.clone());
            solo.tap.lock().unwrap().push(vtx);
            smart_tx = Some(stx);
            // headset / keyboard media keys + Windows media flyout
            if let Some(tap) = crate::desktop::start_media(solo.cmd.clone()) {
                solo.tap.lock().unwrap().push(tap);
            }
            if let Some(icon) = crate::skin::read_image("icon.png") {
                tray = crate::desktop::start_tray(&icon, solo.cmd.clone(), ctx.clone());
            }
        }
        let web = if demo { std::sync::mpsc::channel().0 } else { web::start(cfg.clone(), tx.clone(), ctx.clone()) };
        if signed_in && !demo {
            let _ = web.send(Req::LoadPlaylists);
            let _ = web.send(Req::LoadMe);
            // which songs you know (Liked Songs, playlists, recent plays): for "new to you" marks
            crate::smart::load_known(crate::web::Api::new(cfg.clone(), tx.clone(), ctx.clone()));
        }
        if cfg.lock().unwrap().always_on_top {
            ctx.send_viewport_cmd(egui::ViewportCommand::WindowLevel(egui::WindowLevel::AlwaysOnTop));
        }
        let tb_shared = Some(crate::tb::new_shared(skin.clone(), solo.live.clone(), solo.cmd.clone(), web.clone(), cfg.clone()));
        crate::discord::start(cfg.clone(), solo.live.clone(), solo.cmd.clone());
        crate::update::start(cfg.clone(), tx.clone());
        crate::desktop::start_pacer(ctx.clone(), &crate::desktop::PACE_MS, egui::ViewportId::ROOT);
        crate::desktop::start_pacer(ctx.clone(), &crate::desktop::TB_PACE_MS, crate::tb::id());
        if let Some(t) = &tb_shared {
            crate::tb::start_watcher(t.clone(), ctx.clone());
        }
        let mut app = Self {
            cfg,
            skin,
            rx,
            tx,
            ctx,
            solo,
            web,
            play: Play::default(),
            connected: false,
            status: "STARTING...".into(),
            osd: None,
            art_tex: None,
            art_url: String::new(),
            art_key: String::new(),
            lyrics: None,
            lyrics_uri: String::new(),
            lyrics_loading: false,
            lyrics_since: Instant::now(),
            liked_checked: Instant::now(),
            lyrics_next: String::new(),
            lyric_offset: 0,
            lyric_rows: Vec::new(),
            lyric_rows_key: String::new(),
            manual_lyrics_until: None,
            playlists: Vec::new(),
            sel: None,
            tracks: Vec::new(),
            tracks_for: String::new(),
            tracks_listable: true,
            tracks_loading: false,
            picker: false,
            focus: Some(tab),
            pinned,
            history: load_history(),
            hist_scroll_sel: None,
            mem_text: String::new(),
            mem_at: None,
            contents: Default::default(),
            drag_scroll: 0,
            hover_now: None,
            hover_id: None,
            hover_since: Instant::now(),
            liked: None,
            liked_uri: String::new(),
            add_menu: false,
            add_scroll: 0,
            profile_name: saved_name,
            pfp: None,
            pfp_small: None,
            refresh_at: None,
            seek_hover: None,
            muted_from: None,
            collapse: None,
            install_page: false,
            update_ready: None,
            pl_drop: false,
            drop_scroll: 0,
            drop_fresh: false,
            quitting_for_install: false,
            smart: smart_tx,
            smart_on: false,
            smart_new: Default::default(),
            smart_shuffled: true,
            smart_list: String::new(),
            smart_upcoming: Vec::new(),
            smart_busy: String::new(),
            q_list: Vec::new(),
            q_picked: Default::default(),
            q_removed: Default::default(),
            tracks_all: Vec::new(),
            pl_sort: String::new(),
            pl_find: None,
            queue_asked: Instant::now(),
            tb: tb_shared,
            hold_shuffle: None,
            hold_repeat: None,
            settings: false,
            settings_scroll: 0.0,
            ctx_menu: None,
            add_target: None,
            hide_on_start,
            q_selected: None,
            right_used: false,
            scroll: [0.0; 5],
            selected: None,
            pressed: None,
            drag: Drag::None,
            seek_preview: 0.0,
            vol_preview: 0.0,
            vol_sent: Instant::now(),
            menu: false,
            setup: first_run,
            setup_focus: if first_run { Some(0) } else { None },
            setup_fields,
            vis: Vis::new(bars),
            _tray: tray,
            sized: None,
            t0: Instant::now(),
            win_pos: None,
            native_ppp: 1.0,
            signed_in,
        };
        if first_run {
            app.cfg.lock().unwrap().panel_open = true;
        }
        // offer the built-in installer once, when SpoX runs from e.g. Downloads
        if crate::install::should_offer() && !app.cfg.lock().unwrap().installed_asked {
            app.install_page = true;
            app.cfg.lock().unwrap().panel_open = true;
        }
        if demo {
            app.demo();
        }
        app
    }

    /// Fake data for screenshots / skin work: run with SPOX_DEMO=1 (or =lyrics, =queue, =setup, =menu, =picker, =paused).
    fn demo(&mut self) {
        let mode = std::env::var("SPOX_DEMO").unwrap_or_default();
        self.install_page = mode == "install";
        let t = |i: usize, n: &str, a: &str, al: &str, d: u64| Track { uri: format!("spotify:track:demo{}", i), name: n.into(), artists: a.into(), album: al.into(), duration_ms: d, ..Default::default() };
        self.tracks = vec![
            t(0, "Trouble", "Cage The Elephant", "Tell Me I'm Pretty", 225_000),
            t(1, "cough drops", "almost monday", "cough drops", 164_000),
            t(2, "That's Life", "Still Woozy", "Loveseat", 148_000),
            t(3, "oh yeah?", "Steve Lacy", "Gemini Rights", 171_000),
            t(4, "Just Like a Movie", "Wallows", "Model", 248_000),
            t(5, "Reptilia", "The Strokes", "Room On Fire", 220_000),
            t(6, "Për ty, çdo ditë", "Demo Artist", "Gjithçka", 199_000),
            t(7, "Seven Nation Army", "The White Stripes", "Elephant", 232_000),
            t(8, "Electric Feel", "MGMT", "Oracular Spectacular", 229_000),
            t(9, "Do I Wanna Know?", "Arctic Monkeys", "AM", 272_000),
        ];
        let pl = Playlist { uri: "spotify:playlist:demo".into(), id: "demo".into(), name: "Gym mix".into(), total: 48, owned: true };
        self.playlists = vec![
            Playlist { uri: "liked".into(), id: "liked".into(), name: "Liked Songs".into(), total: 0, owned: true },
            pl.clone(),
            Playlist { uri: "x2".into(), id: "x2".into(), name: "Late night drive".into(), total: 112, owned: true },
            Playlist { uri: "x3".into(), id: "x3".into(), name: "Studying (no lyrics)".into(), total: 63, owned: false },
        ];
        self.sel = Some(pl);
        self.tracks_for = "spotify:playlist:demo".into();
        self.tracks_loading = false;
        self.signed_in = true;
        self.connected = true;
        self.status.clear();
        self.play.logged_in = true;
        self.play.is_active = true;
        self.play.status = if mode == "paused" { "paused".into() } else { "playing".into() };
        self.play.track = Some(self.tracks[0].clone());
        self.play.pos_ms = 35_000.0;
        self.play.pos_ts = now_ms();
        self.play.volume = 65;
        self.play.shuffle = true;
        self.play.repeat = "context".into();
        self.play.upcoming = self.tracks[1..].to_vec();
        for (i, t) in self.play.upcoming.iter_mut().enumerate() {
            t.provider = if i < 2 { "queue".into() } else if i < 7 { "context".into() } else { "autoplay".into() };
        }
        self.play.context_name = "Gym mix".into();
        self.smart_new.insert(self.tracks[2].uri.clone());
        self.lyrics_uri = self.tracks[0].uri.clone();
        self.lyrics = Some(Lyrics {
            synced: true,
            lines: vec![
                (20_000, "Got a feeling that I'm going under".into()),
                (26_000, "But I know that I'll make it out alive".into()),
                (32_000, "If I quit calling you my lover".into()),
                (38_000, "Move on".into()),
                (44_000, "".into()),
                (50_000, "You walk around with your head down low".into()),
                (56_000, "Accents like ë and ç render fine too".into()),
            ],
        });
        self.setup = false;
        self.setup_focus = None;
        let n = self.vis.bars.len();
        self.vis.bars = (0..n).map(|i| ((i as f32 * 0.7).sin().abs() * 0.8 + 0.1).min(1.0)).collect();
        self.vis.peaks = self.vis.bars.iter().map(|b| (b + 0.12).min(1.0)).collect();
        match mode.as_str() {
            "lyrics" => self.focus = Some(Tab::Lyrics),
            "queue" => self.focus = Some(Tab::Queue),
            "setup" => {
                self.setup = true;
                self.setup_focus = Some(1);
                self.setup_fields = ["8f2c1e9a7b3d4c5e".into(), String::new(), String::new()];
            }
            "menu" => self.menu = true,
            "picker" => {
                self.pl_drop = true;
                self.focus = Some(Tab::Playlist);
            }
            "settings" => {
                self.settings = true;
            }
            "lab" => {
                self.settings = true;
                self.cfg.lock().unwrap().dev_mode = true;
                self.settings_scroll = 20.0;
            }
            _ => self.focus = Some(Tab::Playlist),
        }
        self.selected = Some(2);
        if let Some(t) = &self.tb {
            *t.live.lock().unwrap() = self.play.clone();
        }
    }


    fn send(&self, s: String) {
        let _ = self.solo.cmd.send(s);
    }
    fn scale(&self) -> f32 {
        self.cfg.lock().unwrap().scale.clamp(1, 6) as f32
    }
    fn panel_open(&self) -> bool {
        self.cfg.lock().unwrap().panel_open
    }
    fn osd(&mut self, s: impl Into<String>) {
        self.osd = Some((s.into(), Instant::now()));
    }
    fn device_name(&self) -> String {
        if !self.play.device_name.is_empty() {
            return self.play.device_name.clone();
        }
        let n = self.cfg.lock().unwrap().device_name.clone();
        if n.is_empty() { "SpoX".into() } else { n }
    }
    fn liked_uri(&self) -> String {
        web::liked_uri(&self.cfg.lock().unwrap().user_id)
    }
    fn playlist_uri(&self, p: &Playlist) -> String {
        if p.id == "liked" { self.liked_uri() } else { p.uri.clone() }
    }

    // ---------------- events ----------------
    fn on_event(&mut self, e: Ev) {
        match e {
            Ev::Status(s) => self.status = s,
            Ev::SoloistConnected(c) => {
                self.connected = c;
                if c && self.queue_visible() {
                    self.send(command_with("get_queue", "limit", json!(0)));
                }
            }
            Ev::SoloistMsg(v) => {
                if v.get("type").and_then(|t| t.as_str()) == Some("error") {
                    let msg = v.get("message").and_then(|m| m.as_str()).unwrap_or("error").to_uppercase();
                    self.osd(msg);
                }
                let changed = self.play.apply(&v);
                // Soloist's own queue updates only carry the next 10 songs: when the queue changes
                // while you look at it, fetch the whole list again (not more than every 2 s)
                if v.get("type").and_then(|t| t.as_str()) == Some("queue_changed") && self.queue_visible() && self.queue_asked.elapsed() > Duration::from_secs(2) {
                    self.queue_asked = Instant::now();
                    self.send(command_with("get_queue", "limit", json!(0)));
                }
                // keep a just-clicked shuffle/repeat state for a moment so a slow update can't flicker it back
                if let Some((val, t)) = self.hold_shuffle {
                    if t.elapsed() < Duration::from_millis(2500) {
                        self.play.shuffle = val;
                    } else {
                        self.hold_shuffle = None;
                    }
                }
                if let Some((val, t)) = self.hold_repeat.clone() {
                    if t.elapsed() < Duration::from_millis(2500) {
                        self.play.repeat = val;
                    } else {
                        self.hold_repeat = None;
                    }
                }
                if changed {
                    self.on_track_changed();
                } else if self.play.status == "playing" {
                    self.prefetch_lyrics();
                }
            }
            Ev::Playlists(p) => {
                self.playlists = p;
                if self.sel.is_none() {
                    let last = self.cfg.lock().unwrap().last_playlist.clone();
                    if let Some(p) = self.playlists.iter().find(|p| p.uri == last || (p.id == "liked" && last == "liked")).cloned() {
                        self.select_playlist(p);
                    }
                }
            }
            Ev::UpdateReady(v) => {
                self.update_ready = Some(v.clone());
                self.osd(format!("SPOX {} READY: MENU > RESTART", v));
            }
            Ev::Removed { playlist, uri, key } => {
                if let Some(set) = self.contents.get_mut(&playlist) {
                    set.remove(&uri);
                    set.remove(&key);
                }
                // keep the cached list in step, so the button stays right after a restart
                if let Some(mut ts) = load_tracks_cache(&playlist) {
                    ts.retain(|t| t.uri != uri && crate::smart::key(&t.name, &t.artists) != key);
                    save_tracks_cache(&playlist, &ts);
                }
            }
            Ev::Added { playlist, uri } => {
                if let (Some(t), Some(mut ts)) = (self.play.track.clone().filter(|t| t.uri == uri), load_tracks_cache(&playlist)) {
                    ts.push(t);
                    save_tracks_cache(&playlist, &ts);
                }
                let k = self.track_key_of(&uri);
                let set = self.contents.entry(playlist).or_default();
                set.insert(uri);
                if let Some(k) = k {
                    set.insert(k);
                }
            }
            Ev::Tracks { playlist, tracks, listable, done } => {
                if done && listable {
                    // remember what's in every playlist (marks songs that are already in a playlist)
                    let set = self.contents.entry(playlist.clone()).or_default();
                    for t in &tracks {
                        set.insert(t.uri.clone());
                        set.insert(crate::smart::key(&t.name, &t.artists));
                    }
                    save_tracks_cache(&playlist, &tracks);
                }
                if self.sel.as_ref().map(|p| p.uri == playlist).unwrap_or(false) {
                    self.tracks_all = tracks;
                    self.tracks_for = playlist.clone();
                    self.tracks_listable = listable;
                    self.tracks_loading = !done;
                    if done && listable {
                        save_tracks_cache(&playlist, &self.tracks_all);
                    }
                    self.apply_view();
                }
            }
            Ev::Lyrics { uri, lyrics } => {
                if uri == self.lyrics_uri {
                    self.lyrics = lyrics;
                    self.lyrics_loading = false;
                    self.lyric_rows_key.clear();
                    self.manual_lyrics_until = None;
                }
            }
            Ev::Art { url, img } => {
                if url == self.art_url {
                    self.art_tex = Some(self.ctx.load_texture("art", img, egui::TextureOptions::NEAREST));
                }
            }
            Ev::Osd(m) => self.osd(m),
            Ev::Smart { on, shuffled, list, new_uris, upcoming, busy } => {
                if on != self.smart_on {
                    self.play.shuffle = on && shuffled;
                    self.hold_shuffle = Some((on && shuffled, Instant::now()));
                }
                self.smart_on = on;
                self.smart_shuffled = shuffled;
                self.smart_list = list;
                self.smart_new = new_uris.into_iter().collect();
                self.smart_upcoming = upcoming;
                self.smart_busy = busy;
            }
            Ev::Profile { name, img, small } => {
                if !name.is_empty() {
                    let mut c = self.cfg.lock().unwrap();
                    if c.profile_name != name {
                        c.profile_name = name.clone();
                        c.save();
                    }
                }
                if !name.is_empty() || self.profile_name.is_empty() {
                    self.profile_name = name;
                }
                self.pfp = img.map(|i| self.ctx.load_texture("pfp", i, egui::TextureOptions::NEAREST));
                self.pfp_small = small.map(|i| self.ctx.load_texture("pfp8", i, egui::TextureOptions::NEAREST));
            }
            Ev::Liked { uri, liked } => {
                if let Some(t) = &self.tb {
                    *t.liked.lock().unwrap() = (uri.clone(), liked);
                }
                if self.play.track.as_ref().map(|t| t.uri == uri).unwrap_or(false) {
                    self.liked = Some(liked);
                    self.liked_uri = uri;
                }
            }
            Ev::SignedIn(ok) => {
                self.signed_in = ok;
                if ok && self.profile_name.is_empty() {
                    let _ = self.web.send(Req::LoadMe);
                }
                if !ok {
                    self.osd("SIGN IN AGAIN (MENU)");
                }
            }
        }
    }

    fn request_art(&mut self) {
        let Some(t) = &self.play.track else {
            self.art_tex = None;
            self.art_url.clear();
            return;
        };
        let pixel = self.cfg.lock().unwrap().art_mode != "sharp";
        let url = if pixel { t.cover_small.clone() } else { t.cover_large.clone() };
        let Some(url) = url.or_else(|| t.cover_large.clone()) else {
            self.art_tex = None;
            self.art_url.clear();
            return;
        };
        let size = if pixel {
            self.skin.num("/art_pixels", 24.0) as u32
        } else {
            self.skin.rect("art")[2].max(8.0) as u32
        };
        let key = format!("{}|{}|{}", url, size, pixel);
        if key == self.art_key {
            return;
        }
        self.art_key = key;
        self.art_url = url.clone();
        let colors = self.cfg.lock().unwrap().art_colors.clamp(2, 32) as usize;
        art::request(url, size.max(4), if pixel { colors } else { 0 }, self.tx.clone(), self.ctx.clone());
    }

    /// Recently played: a song counts after 10 s of playing (skipped-through songs don't),
    /// newest first, max 100, written in the background.
    fn note_history(&mut self) {
        let Some(t) = &self.play.track else { return };
        if self.play.status != "playing" || self.play.position(now_ms()) < 10_000.0 {
            return;
        }
        if self.history.first().map(|h| h.0.uri == t.uri).unwrap_or(false) {
            return;
        }
        crate::smart::note_known(&t.name, &t.artists);
        self.history.retain(|h| h.0.uri != t.uri);
        self.history.insert(0, (t.clone(), config::now_secs()));
        self.history.truncate(100);
        let h = self.history.clone();
        std::thread::spawn(move || save_history(&h));
    }

    fn on_track_changed(&mut self) {
        self.request_art();
        if let Some(t) = &self.play.track {
            if t.uri != self.liked_uri {
                self.liked = None;
                self.liked_uri = t.uri.clone();
                if self.signed_in && t.uri.starts_with("spotify:track:") {
                    let _ = self.web.send(Req::CheckLiked(t.uri.clone()));
                }
            }
        }
        if let Some(t) = self.play.track.clone() {
            if t.uri != self.lyrics_uri {
                self.lyrics_uri = t.uri.clone();
                self.lyric_offset = load_lyric_offset(&t.uri);
                self.lyrics = None;
                self.lyrics_loading = true;
                self.lyrics_since = Instant::now();
                self.scroll[SCROLL_LY] = 0.0;
                self.lyric_rows_key.clear();
                lyrics::request(t, self.tx.clone(), self.ctx.clone());
            }
        }
        if self.queue_visible() {
            self.send(command_with("get_queue", "limit", json!(0)));
        }
        self.prefetch_lyrics();
    }

    /// Looks up the next song's lyrics while this one plays, so they show the moment it starts.
    fn prefetch_lyrics(&mut self) {
        let next = if self.smart_on { self.smart_upcoming.first().map(|x| x.0.clone()) } else { self.play.upcoming.first().cloned() };
        if let Some(t) = next {
            if t.uri != self.lyrics_next && t.uri != self.lyrics_uri && t.duration_ms > 0 {
                self.lyrics_next = t.uri.clone();
                lyrics::request(t, self.tx.clone(), self.ctx.clone());
            }
        }
    }

    fn queue_visible(&self) -> bool {
        self.focus == Some(Tab::Queue) || self.pinned[Tab::Queue as usize]
    }

    fn select_playlist(&mut self, p: Playlist) {
        {
            let mut c = self.cfg.lock().unwrap();
            c.last_playlist = if p.id == "liked" { "liked".into() } else { p.uri.clone() };
        }
        self.tracks_all = load_tracks_cache(&p.uri).unwrap_or_default();
        self.pl_sort = self.cfg.lock().unwrap().sorts.get(&p.uri).cloned().unwrap_or_else(|| "added".into());
        if p.id != "liked" && self.pl_find.is_some() {
            self.pl_find = None;
        }
        self.tracks_for = p.uri.clone();
        self.tracks_listable = true;
        self.tracks_loading = true;
        self.scroll[SCROLL_PL] = 0.0;
        self.selected = None;
        self.sel = Some(p.clone());
        self.apply_view();
        let _ = self.web.send(Req::LoadTracks(p));
    }

    /// The open playlist as shown: found (type to find), then sorted.
    fn apply_view(&mut self) {
        let words: Vec<String> = self.pl_find.as_deref().unwrap_or("").to_lowercase().split_whitespace().map(|w| w.to_string()).collect();
        let mut v: Vec<Track> = self
            .tracks_all
            .iter()
            .filter(|t| {
                if words.is_empty() {
                    return true;
                }
                let hay = format!("{} {} {}", t.name, t.artists, t.album).to_lowercase();
                words.iter().all(|w| hay.contains(w.as_str()))
            })
            .cloned()
            .collect();
        let low = |s: &str| s.to_lowercase();
        match self.pl_sort.as_str() {
            "added" => v.sort_by(|a, b| b.added.cmp(&a.added).then(a.order.cmp(&b.order))),
            "title" => v.sort_by_key(|t| low(&t.name)),
            "artist" => v.sort_by(|a, b| low(&a.artists).cmp(&low(&b.artists)).then(low(&a.album).cmp(&low(&b.album))).then(a.order.cmp(&b.order))),
            "album" => v.sort_by(|a, b| low(&a.album).cmp(&low(&b.album)).then(a.order.cmp(&b.order))),
            "length" => v.sort_by_key(|t| t.duration_ms),
            _ => v.sort_by_key(|t| t.order),
        }
        // keep the same song selected after re-sorting
        let sel_uri = self.selected.and_then(|i| self.tracks.get(i)).map(|t| t.uri.clone());
        self.tracks = v;
        self.selected = sel_uri.and_then(|u| self.tracks.iter().position(|t| t.uri == u));
    }

    /// Start typing to find songs in the open playlist.
    fn start_find(&mut self) {
        if self.sel.is_none() {
            self.osd("OPEN A PLAYLIST FIRST");
            return;
        }
        self.picker = false;
        self.pl_drop = false;
        self.set_tab(Tab::Playlist);
        self.pl_find = Some(String::new());
        self.osd("TYPE TO FIND \u{b7} ENTER PLAYS \u{b7} ESC CLOSES");
    }

    /// Sort the open playlist the next way (remembered per playlist).
    fn cycle_sort(&mut self) {
        let order = ["added", "title", "artist", "album", "length", "custom"];
        let i = order.iter().position(|o| *o == self.pl_sort).unwrap_or(0);
        self.pl_sort = order[(i + 1) % order.len()].to_string();
        if let Some(p) = &self.sel {
            self.cfg.lock().unwrap().sorts.insert(p.uri.clone(), self.pl_sort.clone());
        }
        self.scroll[SCROLL_PL] = 0.0;
        self.apply_view();
        let name = sort_name(&self.pl_sort);
        self.osd(format!("SORTED BY {}", name.to_uppercase()));
    }

    fn play_playlist(&mut self, p: &Playlist) {
        if self.smart_on {
            self.stop_smart();
        }
        let uri = self.playlist_uri(p);
        if !self.play.is_active {
            self.send(command("activate"));
        }
        self.send(command_with("play", "uri", json!(uri)));
        self.osd(format!("PLAYING {}", p.name.to_uppercase()));
    }

    fn play_track(&mut self, idx: usize) {
        let Some(t) = self.tracks.get(idx).cloned() else { return };
        let Some(p) = self.sel.clone() else { return };
        // shuffle on: SpoX shuffle from this song. Off and sorted or searched: the list exactly as
        // you see it (like Spotify's own sorting). Off in the list's own order: Spotify plays the list.
        let shuffle = (self.smart_on && self.smart_shuffled) || self.play.shuffle;
        let as_shown = self.pl_sort != "custom" || self.pl_find.as_deref().map(|f| !f.is_empty()).unwrap_or(false);
        if self.signed_in && (shuffle || as_shown) {
            if let Some(sm) = &self.smart {
                let tracks = if shuffle { self.tracks_all.clone() } else { self.tracks.clone() };
                let _ = sm.send(crate::smart::Cmd::Start { playlist: p, tracks, first: Some(t.uri.clone()), shuffle, position: 0 });
                return;
            }
        }
        if self.smart_on {
            self.stop_smart();
        }
        if self.signed_in {
            let fallback = if p.id == "liked" { self.tracks.iter().map(|t| t.uri.clone()).collect() } else { Vec::new() };
            let _ = self.web.send(Req::PlayIn {
                context: self.playlist_uri(&p),
                track: t.uri.clone(),
                device_name: self.device_name(),
                fallback_uris: fallback,
            });
        } else {
            self.send(command_with("play", "uri", json!(t.uri)));
        }
    }

    fn toggle_play(&mut self) {
        if self.play.status == "playing" {
            self.send(command("pause"));
        } else {
            self.resume();
        }
    }

    fn resume(&mut self) {
        if !self.play.is_active {
            self.send(command("activate"));
        }
        if self.play.track.is_none() && self.smart_on {
            // SpoX shuffle saved from last time: carry on where you were
            if let Some(s) = &self.smart {
                let _ = s.send(crate::smart::Cmd::Resume);
                return;
            }
        }
        if self.play.track.is_none() {
            if let Some(p) = self.sel.clone() {
                self.play_playlist(&p);
                return;
            }
        }
        self.send(command("play"));
    }

    fn seek_by(&mut self, delta: f64) {
        let p = (self.play.position(now_ms()) + delta).max(0.0);
        self.send(command_with("seek", "position_ms", json!(p as u64)));
    }

    fn set_volume(&mut self, v: i32) {
        let v = v.clamp(0, 100);
        self.play.volume = v as u8;
        self.send(command_with("set_volume", "volume", json!(v)));
        self.osd(format!("VOLUME {}%", v));
    }

    /// Repeat is a simple on/off for the playlist.
    fn cycle_repeat(&mut self) {
        // order as documented by Soloist: turn track-repeat off first, then set context-repeat
        self.send(command_with("set_repeat_track", "enabled", json!(false)));
        if self.play.repeat == "off" {
            self.send(command_with("set_repeat_context", "enabled", json!(true)));
            self.play.repeat = "context".into();
            self.hold_repeat = Some(("context".into(), Instant::now()));
            self.osd("REPEAT ON");
        } else {
            self.send(command_with("set_repeat_context", "enabled", json!(false)));
            self.play.repeat = "off".into();
            self.hold_repeat = Some(("off".into(), Instant::now()));
            self.osd("REPEAT OFF");
        }
    }

    /// The playlist smart shuffle (and the +/- button) works with: the one playing, else the open one.
    fn smart_source(&self) -> Option<(Playlist, Vec<Track>)> {
        let ctx = self.play.context_uri.clone();
        if let Some(pl) = self.playlists.iter().find(|p| self.playlist_uri(p) == ctx && !ctx.is_empty()) {
            if self.sel.as_ref().map(|s| s.id == pl.id).unwrap_or(false) && !self.tracks_all.is_empty() {
                return Some((pl.clone(), self.tracks_all.clone()));
            }
            if let Some(t) = load_tracks_cache(&ctx).filter(|t| !t.is_empty()) {
                return Some((pl.clone(), t));
            }
        }
        let pl = self.sel.clone()?;
        (self.tracks_listable && !self.tracks_all.is_empty()).then(|| (pl, self.tracks_all.clone()))
    }

    fn vis_running(&self) -> bool {
        self.cfg.lock().unwrap().vis_on && (self.play.status == "playing" || self.vis.active())
    }

    fn stop_smart(&mut self) {
        if let Some(s) = &self.smart {
            let _ = s.send(crate::smart::Cmd::Stop);
        }
        self.smart_on = false;
    }

    fn toggle_smart(&mut self) {
        if self.smart_on {
            self.stop_smart();
            self.osd("SPOX SHUFFLE OFF");
            return;
        }
        // inspiration = the playlist you're listening to (else the one open in the list)
        let Some((pl, tracks)) = self.smart_source() else {
            self.osd("PLAY ONE OF YOUR PLAYLISTS FIRST");
            return;
        };
        if let Some(s) = &self.smart {
            let cur = self.play.track.as_ref().map(|t| t.uri.clone());
            let at = if cur.as_ref().map(|u| tracks.iter().any(|t| &t.uri == u)).unwrap_or(false) { self.play.position(now_ms()) as u64 } else { 0 };
            let _ = s.send(crate::smart::Cmd::Start { playlist: pl, tracks, first: cur, shuffle: true, position: at });
            self.smart_on = true;
            self.smart_shuffled = true;
            self.play.shuffle = true;
            self.hold_shuffle = Some((true, Instant::now()));
        }
    }

    /// Seek bar box: skin rect "seek" (stretchable) or the old pos + PNG size.
    fn seek_rect(&self) -> (f32, f32, f32, f32) {
        if self.skin.json["rect"]["seek"].is_array() {
            let r = self.skin.rect("seek");
            return (r[0], r[1], r[2], r[3]);
        }
        let (x, y) = self.skin.pos("seek");
        let (w, h) = self.skin.size("seek_track");
        (x, y, w, h)
    }

    fn toggle_mute(&mut self) {
        match self.muted_from.take() {
            Some(v) => {
                self.set_volume(v as i32);
            }
            None => {
                self.muted_from = Some(self.play.volume.max(5));
                self.set_volume(0);
                self.osd("MUTED");
            }
        }
    }

    /// Shuffle button: off <-> SpoX shuffle (signed in, on one of your lists); else Spotify's.
    fn cycle_shuffle(&mut self) {
        if self.smart_on && self.smart_shuffled {
            // back to the list in its own order, carrying on with the song playing now
            match (self.smart_source(), &self.smart) {
                (Some((pl, tracks)), Some(sm)) => {
                    let mut tracks = tracks;
                    tracks.sort_by_key(|t| t.order);
                    let cur = self.play.track.as_ref().map(|t| t.uri.clone());
                    let at = self.play.position(now_ms()) as u64;
                    let _ = sm.send(crate::smart::Cmd::Start { playlist: pl, tracks, first: cur, shuffle: false, position: at });
                }
                _ => self.stop_smart(),
            }
            self.play.shuffle = false;
            self.hold_shuffle = Some((false, Instant::now()));
            self.osd("SHUFFLE OFF");
        } else if self.signed_in && self.smart_source().is_some() {
            if self.play.shuffle {
                self.send(command_with("set_shuffle", "enabled", json!(false)));
            }
            self.smart_on = false;
            self.toggle_smart();
        } else {
            self.toggle_shuffle();
        }
        self.refresh_at = Some(Instant::now() + Duration::from_millis(700));
    }

    fn toggle_shuffle(&mut self) {
        let v = !self.play.shuffle;
        self.send(command_with("set_shuffle", "enabled", json!(v)));
        self.play.shuffle = v; // show it right away; Soloist's real state follows
        self.hold_shuffle = Some((v, Instant::now()));
        if v && self.smart_on {
            self.stop_smart();
        }
        self.osd(if v { "SHUFFLE ON" } else { "SHUFFLE OFF" });
    }

    fn track_link(&self) -> Option<String> {
        let t = self.play.track.as_ref()?;
        let mut it = t.uri.split(':');
        let (_, kind, id) = (it.next()?, it.next()?, it.next()?);
        Some(format!("https://open.spotify.com/{}/{}", kind, id))
    }

    fn toggle_like(&mut self) {
        let Some(t) = self.play.track.clone() else { return };
        if !self.signed_in {
            self.osd("SIGN IN FIRST (MENU)");
            return;
        }
        let scopes = self.cfg.lock().unwrap().scopes.clone();
        if !web::NEW_SCOPES.iter().all(|sc| scopes.contains(sc)) {
            self.osd("SIGN IN AGAIN (MENU) TO ENABLE LIKES");
            return;
        }
        let on = !self.liked.unwrap_or(false);
        self.liked = Some(on);
        let _ = self.web.send(Req::SetLiked(t.uri, on));
    }

    fn tooltip_text(&self, id: &str) -> Option<String> {
        if let Some(t) = self.skin.json["tooltips"][id].as_str() {
            return Some(t.to_string());
        }
        Some(
            match id {
                "btn_menu" => "Menu",
                "btn_pin" => if self.cfg.lock().unwrap().always_on_top { "Always on top: on" } else { "Always on top: off" },
                "btn_min" => if self.cfg.lock().unwrap().tray { "Hide to tray" } else { "Minimize" },
                "btn_close" => match self.cfg.lock().unwrap().on_close.as_str() {
                    "keep" => "Close (music keeps playing)",
                    "quit" => "Close and stop the music",
                    _ => "Close (pauses the music)",
                },
                "btn_prev" => "Previous",
                "btn_play" => "Play",
                "btn_pause" => "Pause",
                "btn_next" => "Next",
                "btn_panel" => "Panel",
                "btn_shuffle" => if self.smart_on { "SpoX smart shuffle" } else if self.play.shuffle { "Shuffle" } else { "Shuffle: off" },
                "btn_repeat" => if self.play.repeat != "off" { "Repeat" } else { "Repeat: off" },
                "btn_like" => if self.liked == Some(true) { "Liked" } else { "Like" },
                "btn_add" => {
                    return Some(match self.in_playing_playlist_peek() {
                        Some((n, true)) => format!("Remove from {}", n),
                        Some((n, false)) => format!("Add to {}", n),
                        None => "Add to playlist".into(),
                    })
                }
                "btn_link" => "Copy link",
                "art" => "Pixel / sharp",
                "timer" => "Time played / left",
                "vis" => if self.cfg.lock().unwrap().vis_on { "Visualizer (double-click: style)" } else { "Visualizer: paused" },
                "vol" => "Volume",
                "tab_playlist" | "tab_queue" | "tab_lyrics" | "tab_history" => return None,
                "chooser" => "Playlists",
                "pin" => "Keep open",
                "unpin" => "Unpin",
                "setting" => "Click: next  \u{b7}  Right-click: back",
                "btn_mini" => "Taskbar player",
                "infobox" => {
                    let n = self.sel.as_ref().map(|p| p.name.clone()).unwrap_or_default();
                    return Some(if n.is_empty() { "Show in list".into() } else { format!("Show in {}", n) });
                }
                "seek" => return Some(match self.seek_hover { Some(t) => fmt_time(t), None => "Seek".into() }),
                _ => return None,
            }
            .to_string(),
        )
    }

    fn draw_tooltip(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, win_w: f32, win_h: f32) {
        let now = self.hover_now.take();
        if now != self.hover_id {
            self.hover_id = now;
            self.hover_since = Instant::now();
        }
        let Some(id) = self.hover_id.clone() else { return };
        if let Some(t) = id.strip_prefix("row:") {
            // song rows: full text (when cut off) + what clicking does
            if self.hover_since.elapsed() < Duration::from_millis(450) || inp.down || self.menu || self.add_menu {
                self.ctx.request_repaint_after(Duration::from_millis(500));
                return;
            }
            let t = t.to_string();
            self.draw_tip_box(p, o, s, inp, win_w, win_h, &t);
            return;
        }
        if inp.down || self.menu || self.add_menu {
            return;
        }
        if self.hover_since.elapsed() < Duration::from_millis(450) {
            self.ctx.request_repaint_after(Duration::from_millis(500));
            return;
        }
        let Some(text) = self.tooltip_text(&id) else { return };
        self.draw_tip_box(p, o, s, inp, win_w, win_h, &text);
    }

    fn draw_tip_box(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, win_w: f32, win_h: f32, text: &str) {
        let Some(pos) = inp.pos else { return };
        let text = self.fit(text, win_w - 12.0);
        let w = self.tw(&text) + 8.0;
        let h = 12.0;
        let x = (pos.x + 6.0).min(win_w - w - 2.0).max(2.0);
        let mut y = pos.y + 12.0;
        if y + h > win_h - 2.0 {
            y = pos.y - h - 4.0;
        }
        self.fill(p, o, s, x - 1.0, y - 1.0, w + 2.0, h + 2.0, Color32::from_rgb(8, 8, 9));
        self.fill(p, o, s, x, y, w, h, self.skin.color("menu_bg"));
        self.text(p, o, s, x + 4.0, y + 3.0, &text, self.skin.color("menu_text"));
    }

    fn in_playing_playlist_peek(&self) -> Option<(String, bool)> {
        let t = self.play.track.as_ref()?;
        let pl = self.playlists.iter().find(|p| p.owned && p.id != "liked" && p.uri == self.play.context_uri)?;
        let set = self.contents.get(&pl.uri)?;
        Some((pl.name.clone(), set.contains(&t.uri) || set.contains(&crate::smart::key(&t.name, &t.artists))))
    }

    /// The playing playlist (if it's yours) and whether the current song is already in it.
    fn in_playing_playlist(&mut self) -> Option<(Playlist, bool)> {
        let t = self.play.track.clone()?;
        let ctx = self.play.context_uri.clone();
        let pl = self.playlists.iter().find(|p| p.owned && p.id != "liked" && p.uri == ctx).cloned()?;
        if !self.contents.contains_key(&pl.uri) {
            let set = self.contents.entry(pl.uri.clone()).or_default();
            match load_tracks_cache(&pl.uri) {
                Some(ts) => {
                    for x in ts {
                        set.insert(crate::smart::key(&x.name, &x.artists));
                        set.insert(x.uri);
                    }
                }
                None => {
                    let _ = self.web.send(Req::LoadTracksQuiet(pl.clone()));
                }
            }
        }
        let set = self.contents.get(&pl.uri)?;
        let inside = set.contains(&t.uri) || set.contains(&crate::smart::key(&t.name, &t.artists));
        Some((pl, inside))
    }

    /// "song|artist" key for a uri we know about (current song, lists, queue, history).
    fn track_key_of(&self, uri: &str) -> Option<String> {
        let t = self
            .play
            .track
            .iter()
            .chain(self.tracks.iter())
            .chain(self.play.upcoming.iter())
            .chain(self.history.iter().map(|h| &h.0))
            .find(|t| t.uri == uri)?;
        Some(crate::smart::key(&t.name, &t.artists))
    }

    fn draw_add_menu(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp) {
        let last = self.cfg.lock().unwrap().last_add_playlist.clone();
        let mut lists: Vec<Playlist> = self.playlists.iter().filter(|p| p.owned && p.id != "liked").cloned().collect();
        // load what's inside each playlist (from cache, or quietly from Spotify) to mark duplicates
        for pl in &lists {
            if !self.contents.contains_key(&pl.uri) {
                let cached = load_tracks_cache(&pl.uri);
                let set = self.contents.entry(pl.uri.clone()).or_default();
                match cached {
                    Some(ts) => {
                        for t in ts {
                            set.insert(crate::smart::key(&t.name, &t.artists));
                            set.insert(t.uri);
                        }
                    }
                    None => {
                        let _ = self.web.send(Req::LoadTracksQuiet(pl.clone()));
                    }
                }
            }
        }
        let target_uri = self.add_target.clone().or_else(|| self.play.track.as_ref().map(|t| t.uri.clone())).unwrap_or_default();
        let target_key = self.track_key_of(&target_uri).unwrap_or_default();
        if let Some(i) = lists.iter().position(|p| p.id == last) {
            let l = lists.remove(i);
            lists.insert(0, l);
        }
        let lh = 10.0;
        let show = 8usize;
        let (mx, my) = (100.0, 18.0);
        let w = 180.0;
        let n = lists.len().min(show);
        let h = (n.max(1) as f32 + 1.0) * lh + 4.0;
        if inp.wheel != 0.0 && inp.over(mx, my, w, h) {
            if inp.wheel < 0.0 {
                self.add_scroll = (self.add_scroll + 1).min(lists.len().saturating_sub(show));
            } else {
                self.add_scroll = self.add_scroll.saturating_sub(1);
            }
        }
        self.fill(p, o, s, mx - 1.0, my - 1.0, w + 2.0, h + 2.0, Color32::from_rgb(10, 10, 11));
        self.fill(p, o, s, mx, my, w, h, self.skin.color("menu_bg"));
        self.text(p, o, s, mx + 6.0, my + 3.0, "Add to playlist:", self.skin.color("list_dim"));
        if lists.is_empty() {
            self.text(p, o, s, mx + 6.0, my + 3.0 + lh, "No playlists of yours yet", self.skin.color("menu_text"));
        }
        let mut chosen = None;
        for (i, pl) in lists.iter().enumerate().skip(self.add_scroll).take(show) {
            let y = my + 2.0 + (i - self.add_scroll + 1) as f32 * lh;
            let inside = self.contents.get(&pl.uri).map(|set| set.contains(&target_uri) || (!target_key.is_empty() && set.contains(&target_key))).unwrap_or(false);
            if inp.over(mx, y, w, lh) {
                if inside {
                    self.hover_now = Some("row:Already in this playlist".into());
                } else {
                    self.fill(p, o, s, mx, y, w, lh, self.skin.color("menu_hover"));
                    if inp.released {
                        chosen = Some(pl.clone());
                    }
                }
            }
            let t = self.fit(&pl.name, w - 30.0);
            let col = if inside { self.skin.color("list_dim") } else { self.skin.color("menu_text") };
            self.text(p, o, s, mx + 6.0, y + 1.0, &t, col);
            if inside {
                let iw = self.tw("in it");
                self.text(p, o, s, mx + w - iw - 6.0, y + 1.0, "in it", self.skin.color("list_dim"));
            }
        }
        if let Some(pl) = chosen {
            let target = self.add_target.take().or_else(|| self.play.track.as_ref().map(|t| t.uri.clone()));
            if let Some(u) = target {
                let _ = self.web.send(Req::AddTo { playlist: pl.clone(), uri: u });
                self.cfg.lock().unwrap().last_add_playlist = pl.id.clone();
            }
            self.add_menu = false;
        } else if inp.released && !inp.over(mx, my, w, h) && self.pressed != Some("btn_add") {
            self.add_menu = false;
        }
    }

    fn set_tab(&mut self, t: Tab) {
        self.focus = Some(t);
        self.setup = false;
        self.cfg.lock().unwrap().tab = match t {
            Tab::Playlist => "playlist",
            Tab::Queue => "queue",
            Tab::Lyrics => "lyrics",
            Tab::History => "history",
        }
        .into();
        if t == Tab::Queue {
            self.send(command_with("get_queue", "limit", json!(0)));
        }
        if !self.panel_open() {
            self.cfg.lock().unwrap().panel_open = true;
        }
    }

    /// Hide (tray) or minimize, after the chosen pixel animation.
    fn start_collapse(&mut self, to_tray: bool) {
        let kind = self.cfg.lock().unwrap().collapse_anim.clone();
        if kind == "none" || self.collapse.is_some() {
            self.collapse = None;
            self.finish_collapse(to_tray);
        } else {
            self.menu = false;
            self.add_menu = false;
            self.ctx_menu = None;
            self.collapse = Some(Collapse { t0: Instant::now(), kind, to_tray });
            self.ctx.request_repaint();
        }
    }

    fn finish_collapse(&mut self, to_tray: bool) {
        if to_tray {
            crate::desktop::hide_window();
        } else {
            self.ctx.send_viewport_cmd(egui::ViewportCommand::Minimized(true));
        }
    }

    /// Collapse animation overlay: chunky, stepped (about 12 frames a second), like old hardware.
    fn draw_collapse(&mut self, p: &egui::Painter) -> bool {
        let Some(c) = &self.collapse else { return false };
        let ms = self.skin.num("/anim_ms", 420.0).max(100.0) as f32;
        let raw = (c.t0.elapsed().as_secs_f32() * 1000.0 / ms).min(1.0);
        let steps = (ms / 83.0).round().max(3.0);
        let t = (raw * steps).floor() / steps;
        let r = self.ctx.screen_rect();
        let (w, h) = (r.width(), r.height());
        let s = self.scale();
        let snap = |v: f32| (v / s).floor() * s;
        let bg = self.skin.json["colors"]["anim_bg"].as_str().and_then(crate::skin::parse_color).unwrap_or(Color32::from_rgb(10, 10, 11));
        let line = self.skin.color("title");
        let block = |x0: f32, y0: f32, x1: f32, y1: f32| {
            if x1 > x0 && y1 > y0 {
                p.rect_filled(Rect::from_min_max(Pos2::new(x0, y0), Pos2::new(x1, y1)), 0.0, bg);
            }
        };
        match c.kind.as_str() {
            "dissolve" => {
                // 8x8 blocks wink out in a fixed random order
                let cell = 8.0 * s;
                let (cols, rows) = ((w / cell).ceil() as u32, (h / cell).ceil() as u32);
                for j in 0..rows {
                    for i in 0..cols {
                        let mut hsh = (i.wrapping_mul(374761393) ^ j.wrapping_mul(668265263)).wrapping_add(0x9E37_79B9);
                        hsh = (hsh ^ (hsh >> 13)).wrapping_mul(1274126177);
                        let rnd = (hsh >> 8) as f32 / (1u32 << 24) as f32;
                        if rnd < t * 1.05 {
                            block(i as f32 * cell, j as f32 * cell, (i + 1) as f32 * cell, (j + 1) as f32 * cell);
                        }
                    }
                }
            }
            "shutter" => {
                // blinds: every 8 px band closes from its top
                let band = 8.0 * s;
                let fill = snap(band * t);
                let mut y = 0.0;
                while y < h {
                    block(0.0, y, w, (y + fill).min(h));
                    y += band;
                }
            }
            _ => {
                // TV switch-off: squash to a bright line, the line shrinks to a dot, the dot goes out
                if t < 0.5 {
                    let k = t / 0.5;
                    let half = snap((h / 2.0 - s) * k);
                    block(0.0, 0.0, w, half);
                    block(0.0, h - half, w, h);
                    if k >= 0.75 {
                        p.rect_filled(Rect::from_min_max(Pos2::new(0.0, snap(h / 2.0) - s), Pos2::new(w, snap(h / 2.0) + s)), 0.0, line);
                    }
                } else {
                    block(0.0, 0.0, w, h);
                    let k = ((t - 0.5) / 0.35).min(1.0);
                    let lw = snap((w / 2.0) * (1.0 - k)).max(2.0 * s);
                    if t < 0.95 {
                        let cx = snap(w / 2.0);
                        p.rect_filled(Rect::from_min_max(Pos2::new(cx - lw, snap(h / 2.0) - s), Pos2::new(cx + lw, snap(h / 2.0) + s)), 0.0, line);
                    }
                }
            }
        }
        if raw >= 1.0 {
            let c = self.collapse.take().unwrap();
            self.finish_collapse(c.to_tray);
            return false;
        }
        self.ctx.request_repaint_after(Duration::from_millis(20));
        true
    }

    fn reload_skin(&mut self) {
        if let Some(t) = &self.tb {
            self.ctx.request_repaint_of(crate::tb::id());
            let _ = t;
        }
        self.skin = std::sync::Arc::new(Skin::load(&self.ctx));
        if let Some(t) = &self.tb {
            *t.skin.lock().unwrap() = self.skin.clone();
        }
        self.vis.resize(self.skin.num("/vis/bars", 16.0) as usize);
        self.sized = None;
        self.art_key.clear();
        self.request_art();
        self.lyric_rows_key.clear();
        self.osd("SKIN RELOADED");
    }

    fn save_setup(&mut self) {
        let key = self.setup_fields[0].trim().to_string();
        let client = self.setup_fields[1].trim().to_string();
        self.cfg.lock().unwrap().lastfm_key = self.setup_fields[2].trim().to_string();
        let (key_changed, client_changed, has_token) = {
            let mut c = self.cfg.lock().unwrap();
            let kc = c.soloist_key != key;
            let cc = c.client_id != client;
            c.soloist_key = key;
            c.client_id = client.clone();
            if cc {
                c.refresh_token.clear();
                c.access_token.clear();
            }
            c.save();
            (kc, cc, !c.refresh_token.is_empty())
        };
        if key_changed {
            self.send("__restart__".into());
        }
        if !client.is_empty() && (client_changed || !has_token) {
            let _ = self.web.send(Req::SignIn);
                            self.osd("OPENING BROWSER...");
        }
        self.setup = false;
        self.setup_focus = None;
        self.osd("SAVED");
    }

    // ---------------- drawing helpers ----------------
    fn spr(&self, p: &egui::Painter, o: Pos2, s: f32, name: &str, x: f32, y: f32) -> (f32, f32) {
        if let Some(sp) = self.skin.sprite(name) {
            let r = Rect::from_min_size(Pos2::new(o.x + x * s, o.y + y * s), Vec2::new(sp.w * s, sp.h * s));
            p.image(sp.tex.id(), r, Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)), Color32::WHITE);
            return (sp.w, sp.h);
        }
        (0.0, 0.0)
    }
    fn fill(&self, p: &egui::Painter, o: Pos2, s: f32, x: f32, y: f32, w: f32, h: f32, c: Color32) {
        p.rect_filled(Rect::from_min_size(Pos2::new(o.x + x * s, o.y + y * s), Vec2::new(w * s, h * s)), 0.0, c);
    }
    fn text(&self, p: &egui::Painter, o: Pos2, s: f32, x: f32, y: f32, t: &str, c: Color32) -> f32 {
        match &self.skin.font {
            Some(f) => f.draw(p, o, s, x, y, t, c),
            None => 0.0,
        }
    }
    fn tw(&self, t: &str) -> f32 {
        self.skin.font.as_ref().map(|f| f.width(t)).unwrap_or(0.0)
    }
    fn fit(&self, t: &str, w: f32) -> String {
        self.skin.font.as_ref().map(|f| f.fit(t, w)).unwrap_or_default()
    }
    fn clip(&self, p: &egui::Painter, o: Pos2, s: f32, r: [f32; 4]) -> egui::Painter {
        p.with_clip_rect(Rect::from_min_size(Pos2::new(o.x + r[0] * s, o.y + r[1] * s), Vec2::new(r[2] * s, r[3] * s)))
    }

    /// Button with `<name>`, `<name>_down`, and optional state variant. Returns true on click.
    fn button(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, oy: f32, id: &'static str, variant: Option<&str>) -> bool {
        let (x, y) = self.skin.pos(id);
        let y = y + oy;
        let (w, h) = self.skin.size(id);
        let over = inp.over(x, y, w, h);
        if over {
            self.hover_now = Some(id.to_string());
        }
        if inp.pressed && over && self.pressed.is_none() && self.drag == Drag::None {
            self.pressed = Some(id);
        }
        let held = self.pressed == Some(id) && over && inp.down;
        let down_name = format!("{}_down", id);
        let var_down = variant.map(|v| format!("{}_down", v)).filter(|v| self.skin.sprite(v).is_some());
        let name = if held && var_down.is_some() {
            var_down.unwrap()
        } else if held && self.skin.sprite(&down_name).is_some() {
            down_name
        } else {
            variant.filter(|v| self.skin.sprite(v).is_some()).map(|v| v.to_string()).unwrap_or_else(|| id.to_string())
        };
        self.spr(p, o, s, &name, x, y);
        inp.released && over && self.pressed == Some(id)
    }

    fn marquee(&self, p: &egui::Painter, o: Pos2, s: f32, r: [f32; 4], t: &str, col: Color32) -> bool {
        let cp = self.clip(p, o, s, r);
        let w = self.tw(t);
        let ty = r[1] + ((r[3] - 7.0) / 2.0).floor();
        if w <= r[2] {
            self.text(&cp, o, s, r[0], ty, t, col);
            return false;
        }
        let gap = 24.0;
        let period = w + gap;
        let el = self.t0.elapsed().as_secs_f32();
        // pause 2s at the start of each loop, then scroll at 14 px/s
        let cycle = 2.0 + period / 14.0;
        let ph = el % cycle;
        let off = if ph < 2.0 { 0.0 } else { ((ph - 2.0) * 14.0).floor() };
        self.text(&cp, o, s, r[0] - off, ty, t, col);
        self.text(&cp, o, s, r[0] - off + period, ty, t, col);
        true
    }

    // ---------------- main window ----------------
    fn draw_main(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp) -> bool {
        let mut animating = false;
        self.spr(p, o, s, "main_bg", 0.0, 0.0);
        let now = now_ms();

        // title bar buttons
        let show_pfp = self.signed_in && self.cfg.lock().unwrap().show_profile;
        let menu_clicked = if show_pfp {
            // your picture is the menu button (with an outline, so a black or missing picture still shows)
            let (x, y) = self.skin.pos("btn_menu");
            let (w, h) = self.skin.size("btn_menu");
            let over = inp.over(x - 1.0, y - 1.0, w + 2.0, h + 2.0);
            if over {
                self.hover_now = Some("btn_menu".into());
            }
            // same press/release bookkeeping as a normal button, so the menu doesn't close again at once
            if inp.pressed && over && self.pressed.is_none() && self.drag == Drag::None {
                self.pressed = Some("btn_menu");
            }
            let pressed_now = over && inp.down && self.pressed == Some("btn_menu");
            self.draw_pfp(p, o, s, x, y + if pressed_now { 1.0 } else { 0.0 }, w, true);
            over && inp.released && self.pressed == Some("btn_menu")
        } else {
            self.button(p, o, s, inp, 0.0, "btn_menu", None)
        };
        if menu_clicked {
            self.menu = !self.menu;
        }
        let on_top = self.cfg.lock().unwrap().always_on_top;
        if self.button(p, o, s, inp, 0.0, "btn_pin", on_top.then_some("btn_pin_down")) {
            let v = !on_top;
            self.cfg.lock().unwrap().always_on_top = v;
            self.ctx.send_viewport_cmd(egui::ViewportCommand::WindowLevel(if v {
                egui::WindowLevel::AlwaysOnTop
            } else {
                egui::WindowLevel::Normal
            }));
            self.osd(if v { "ALWAYS ON TOP" } else { "NORMAL WINDOW" });
        }
        if self.button(p, o, s, inp, 0.0, "btn_min", None) {
            let tray = self.cfg.lock().unwrap().tray && self._tray.is_some();
            self.start_collapse(tray);
        }
        if self.button(p, o, s, inp, 0.0, "btn_close", None) {
            self.ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }

        // album art (click toggles pixel / sharp)
        let ar = self.skin.rect("art");
        if let Some(t) = &self.art_tex {
            p.image(
                t.id(),
                Rect::from_min_size(Pos2::new(o.x + ar[0] * s, o.y + ar[1] * s), Vec2::new(ar[2] * s, ar[3] * s)),
                Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)),
                Color32::WHITE,
            );
        } else {
            self.spr(p, o, s, "art_empty", ar[0], ar[1]);
        }
        if inp.released && inp.over(ar[0], ar[1], ar[2], ar[3]) && self.pressed.is_none() && self.drag == Drag::None {
            let mut c = self.cfg.lock().unwrap();
            c.art_mode = if c.art_mode == "sharp" { "pixel".into() } else { "sharp".into() };
            drop(c);
            self.request_art();
        }

        // status icon + timer
        let st: &str = match self.play.status.as_str() {
            "playing" => "status_play",
            "paused" => "status_pause",
            "buffering" => "status_buffer",
            _ => "status_stop",
        };
        // the small play/pause icon is optional (only drawn if skin.json has a "status" position)
        let has_status = self.skin.json["pos"]["status"].is_array();
        let (sx, sy) = if has_status { self.skin.pos("status") } else { self.skin.pos("minus") };
        let dur = self.play.track.as_ref().map(|t| t.duration_ms as f64).unwrap_or(0.0);
        let pos = if self.drag == Drag::Seek { self.seek_preview } else { self.play.position(now) };
        let remaining = self.cfg.lock().unwrap().show_remaining;
        let shown = if remaining { (dur - pos).max(0.0) } else { pos };
        let (tx, ty) = self.skin.pos("timer");
        let blink = self.play.status == "paused" && self.skin.flag("timer_blink_when_paused", true);
        let visible = !blink || (self.t0.elapsed().as_millis() / 500) % 2 == 0;
        if blink {
            animating = true;
        }
        let secs = (shown / 1000.0) as u64;
        let txt = if self.play.track.is_some() { format!("{:02}:{:02}", (secs / 60).min(99), secs % 60) } else { "  :  ".into() };
        let dw = self.skin.num("/digits/width", 12.0) as f32;
        let colon_w = self.skin.num("/digits/colon_width", dw as f64) as f32;
        let ghost = self.skin.num("/digits/ghost", 0.13) as f32;
        let order: Vec<char> = self.skin.json["digits"]["order"].as_str().unwrap_or("0123456789:- ").chars().collect();
        let dcol = self.skin.color("digits");
        let mut timer_w = 0.0;
        if let Some(sp) = self.skin.sprite("digits") {
            // draws columns [c0, c0+w) of glyph `gi` at skin x
            let glyph = |gi: usize, x: f32, y: f32, c0: f32, w: f32, col: Color32| {
                let r = Rect::from_min_size(Pos2::new(o.x + x * s, o.y + y * s), Vec2::new(w * s, sp.h * s));
                let gx = gi as f32 * dw + c0;
                let uv = Rect::from_min_max(Pos2::new(gx / sp.w, 0.0), Pos2::new((gx + w) / sp.w, 1.0));
                p.image(sp.tex.id(), r, uv, col);
            };
            let eight = order.iter().position(|c| *c == '8');
            let mut x = tx;
            for ch in txt.chars() {
                if ch == ':' {
                    let c0 = ((dw - colon_w) / 2.0).floor();
                    if visible {
                        if let Some(gi) = order.iter().position(|c| *c == ':') {
                            glyph(gi, x, ty, c0, colon_w, dcol);
                        }
                    }
                    x += colon_w;
                    continue;
                }
                // unlit LCD segments: your "8", faintly
                if let Some(e) = eight {
                    glyph(e, x, ty, 0.0, dw, dcol.gamma_multiply(ghost));
                }
                if visible && ch != ' ' {
                    if let Some(gi) = order.iter().position(|c| *c == ch) {
                        glyph(gi, x, ty, 0.0, dw, dcol);
                    }
                }
                x += dw;
            }
            timer_w = x - tx;
            // remaining-time minus sign takes the place of the status icon
            if remaining && self.play.track.is_some() {
                if let Some(mi) = order.iter().position(|c| *c == '-') {
                    if visible {
                        let (stw, _) = self.skin.size(st);
                        let mw = stw.max(4.0).min(dw);
                        glyph(mi, sx, ty, ((dw - mw) / 2.0).floor(), mw, dcol);
                    }
                }
            } else if has_status {
                self.spr(p, o, s, st, sx, sy);
            }
        } else if has_status {
            self.spr(p, o, s, st, sx, sy);
        }
        if inp.released && inp.over(tx, ty, timer_w, self.skin.size("digits").1) && self.pressed.is_none() && self.drag == Drag::None {
            let r = {
                let mut c = self.cfg.lock().unwrap();
                c.show_remaining = !c.show_remaining;
                c.show_remaining
            };
            self.osd(if r { "TIMER: TIME LEFT" } else { "TIMER: TIME PLAYED" });
        }
        // scroll over the timer = seek 5 s
        if inp.wheel != 0.0 && inp.over(tx, ty, timer_w, self.skin.size("digits").1) && dur > 0.0 {
            self.seek_by(if inp.wheel > 0.0 { 5000.0 } else { -5000.0 });
        }

        // visualizer: click = freeze / unfreeze, double-click = next style (bars, mirror)
        let vr = self.skin.rect("vis");
        let (frozen, style) = {
            let c = self.cfg.lock().unwrap();
            (!c.vis_on, c.vis_style.clone())
        };
        let playing = self.play.status == "playing";
        if frozen {
            self.vis.sleep();
        } else if std::env::var("SPOX_DEMO").is_err() {
            self.vis.update(playing);
        }
        {
            let bw = self.skin.num("/vis/bar_width", 3.0) as f32;
            let gap = self.skin.num("/vis/gap", 1.0) as f32;
            let n = self.vis.bars.len();
            let cols = self.skin.vis_colors.clone();
            let peak = self.skin.color("vis_peak");
            let h = vr[3];
            let col_at = |frac: f32| cols[((frac * cols.len() as f32) as usize).min(cols.len() - 1)];
            for i in 0..n {
                let bx = vr[0] + i as f32 * (bw + gap);
                if bx + bw > vr[0] + vr[2] + 0.5 {
                    break;
                }
                // never an empty box: bars at rest keep a 1 px floor
                let v = self.vis.bars[i];
                if style == "mirror" {
                    let half = (h / 2.0).floor();
                    let bh = (v * half).round().max(1.0);
                    for yy in 0..bh as i32 {
                        let c = col_at(1.0 - yy as f32 / half);
                        self.fill(p, o, s, bx, vr[1] + half - 1.0 - yy as f32, bw, 1.0, c);
                        self.fill(p, o, s, bx, vr[1] + half + yy as f32, bw, 1.0, c.gamma_multiply(0.55));
                    }
                } else {
                    let bh = (v * h).round().max(1.0);
                    for yy in 0..bh as i32 {
                        let py = vr[1] + h - 1.0 - yy as f32;
                        self.fill(p, o, s, bx, py, bw, 1.0, col_at((py - vr[1]) / h));
                    }
                    let pk = (self.vis.peaks[i] * h).round();
                    if pk >= 2.0 {
                        self.fill(p, o, s, bx, vr[1] + h - pk, bw, 1.0, peak);
                    }
                }
            }
            if !frozen && (playing || self.vis.active()) {
                animating = true;
            }
        }
        if inp.over(vr[0], vr[1], vr[2], vr[3]) && self.pressed.is_none() && self.drag == Drag::None {
            if inp.dbl {
                // the first click of the double-click froze it: run again in the new style
                let mut c = self.cfg.lock().unwrap();
                c.vis_style = if c.vis_style == "mirror" { "bars".into() } else { "mirror".into() };
                c.vis_on = true;
            } else if inp.released {
                let mut c = self.cfg.lock().unwrap();
                c.vis_on = !c.vis_on;
            }
        }

        // title + info lines
        let tr = self.skin.rect("title");
        let ir = self.skin.rect("info");
        let up = self.skin.flag("uppercase_display", false) || self.cfg.lock().unwrap().uppercase;
        let osd = self.osd.as_ref().filter(|(_, t)| t.elapsed() < Duration::from_millis(1500)).map(|(m, _)| m.clone());
        let pair_msg = format!("OPEN SPOTIFY > DEVICES > {}", self.device_name().to_uppercase());
        let (title, info) = if let Some(m) = osd {
            (m, String::new())
        } else if !self.status.is_empty() {
            (self.status.clone(), String::new())
        } else if self.connected && !self.play.logged_in {
            ("PAIR ONCE: ".to_string() + &pair_msg, "SpoX connects by itself after that".into())
        } else if let Some(t) = &self.play.track {
            let mut info = t.artists.clone();
            if !t.album.is_empty() {
                info = format!("{}  \u{b7}  {}", info, t.album);
            }
            (t.name.clone(), info)
        } else if self.connected {
            ("READY".into(), if self.play.is_active { String::new() } else { "Pick a playlist below or play from your phone".into() })
        } else {
            ("CONNECTING...".into(), String::new())
        };
        let (title, info) = if up { (title.to_uppercase(), info.to_uppercase()) } else { (title, info) };
        if self.osd.is_some() {
            animating = true;
        }
        animating |= self.marquee(p, o, s, tr, &title, self.skin.color("title"));
        // three-line layout (title / artist / album) when the skin defines "artist" and "album"
        let three = self.skin.json["rect"]["artist"].is_array() && self.skin.json["rect"]["album"].is_array();
        let normal_track = self.osd.as_ref().map(|(_, t)| t.elapsed() >= Duration::from_millis(1500)).unwrap_or(true)
            && self.status.is_empty()
            && self.play.logged_in;
        if three && normal_track && self.play.track.is_some() {
            let t = self.play.track.clone().unwrap();
            let (a, al) = if up { (t.artists.to_uppercase(), t.album.to_uppercase()) } else { (t.artists.clone(), t.album.clone()) };
            animating |= self.marquee(p, o, s, self.skin.rect("artist"), &a, self.skin.color("info"));
            let alc = self.skin.json["colors"]["album"].as_str().and_then(crate::skin::parse_color).unwrap_or(self.skin.color("list_dim"));
            animating |= self.marquee(p, o, s, self.skin.rect("album"), &al, alc);
            // middle-click the text: copy "Artist - Title"
            let r = self.skin.rect("title");
            if inp.middle && inp.over(r[0], r[1], r[2], 32.0) {
                self.ctx.copy_text(format!("{} - {}", t.artists, t.name));
                self.osd("COPIED ARTIST - TITLE");
            }
        } else if three {
            animating |= self.marquee(p, o, s, self.skin.rect("artist"), &info, self.skin.color("info"));
        } else {
            animating |= self.marquee(p, o, s, ir, &info, self.skin.color("info"));
        }

        // volume (skin rect "vol" = any width; the PNGs stretch with crisp ends)
        let (vx, vy, vw, vh) = if self.skin.json["rect"]["vol"].is_array() {
            let r = self.skin.rect("vol");
            (r[0], r[1], r[2], r[3])
        } else {
            let (x, y) = self.skin.pos("vol");
            let (w, h) = self.skin.size("vol_track");
            (x, y, w, h)
        };
        let (tw_, _) = self.skin.size("vol_thumb");
        if inp.over(vx, vy - 4.0, vw, vh + 8.0) {
            self.hover_now = Some("vol".into());
        }
        if inp.pressed && inp.over(vx, vy - 4.0, vw, vh + 8.0) && self.pressed.is_none() {
            self.drag = Drag::Vol;
        }
        if inp.middle && inp.over(vx, vy - 4.0, vw, vh + 8.0) {
            self.toggle_mute();
        }
        if self.drag == Drag::Vol {
            if let Some(pp) = inp.pos {
                self.vol_preview = ((pp.x - vx - tw_ / 2.0) / (vw - tw_)).clamp(0.0, 1.0) * 100.0;
                let v = self.vol_preview.round() as i32;
                if v != self.play.volume as i32 && self.vol_sent.elapsed() > Duration::from_millis(80) {
                    self.vol_sent = Instant::now();
                    self.set_volume(v);
                }
            }
            if inp.released {
                let v = self.vol_preview.round() as i32;
                self.set_volume(v);
            }
        }
        if inp.wheel != 0.0 && inp.over(vx, vy - 4.0, vw, vh + 8.0) {
            let v = self.play.volume as i32 + if inp.wheel > 0.0 { 5 } else { -5 };
            self.set_volume(v);
        }
        let vf = if self.drag == Drag::Vol { self.vol_preview } else { self.play.volume as f32 } / 100.0;
        self.nine(p, o, s, "vol_track", vx, vy, vw, vh, 2.0);
        let fw = (vw * vf).round();
        if fw > 0.0 {
            let cp = p.with_clip_rect(Rect::from_min_size(Pos2::new(o.x + vx * s, o.y + vy * s), Vec2::new(fw * s, vh * s)));
            self.nine(&cp, o, s, "vol_fill", vx, vy, vw, vh, 2.0);
        }
        self.spr(p, o, s, "vol_thumb", vx + ((vw - tw_) * vf).round(), vy);

        // shuffle / repeat
        let shuf_var = if self.smart_on {
            Some(if self.skin.sprite("btn_shuffle_smart").is_some() { "btn_shuffle_smart" } else { "btn_shuffle_down" })
        } else if self.play.shuffle {
            Some("btn_shuffle_down")
        } else {
            None
        };
        if self.button(p, o, s, inp, 0.0, "btn_shuffle", shuf_var) {
            self.cycle_shuffle();
        }
        let rep = (self.play.repeat != "off").then_some("btn_repeat_down");
        if self.button(p, o, s, inp, 0.0, "btn_repeat", rep) {
            self.cycle_repeat();
        }
        if self.skin.json["pos"]["btn_smart"].is_array() {
            let on = self.smart_on;
            if self.button(p, o, s, inp, 0.0, "btn_smart", on.then_some("btn_smart_down")) {
                self.toggle_smart();
            }
        }
        if self.skin.json["pos"]["btn_mini"].is_array() && self.button(p, o, s, inp, 0.0, "btn_mini", None) {
            // tuck SpoX into the taskbar player (with the chosen animation)
            let was_off = !self.cfg.lock().unwrap().tb_on;
            self.cfg.lock().unwrap().tb_on = true;
            if was_off {
                self.osd("TASKBAR PLAYER ON");
            }
            let tray = self._tray.is_some();
            self.start_collapse(tray);
        }

        // seek
        let (kx, ky, kw, kh) = self.seek_rect();
        let (thw, _) = self.skin.size("seek_thumb");
        if inp.pressed && inp.over(kx, ky - 2.0, kw, kh + 4.0) && self.pressed.is_none() && dur > 0.0 {
            self.drag = Drag::Seek;
        }
        if inp.wheel != 0.0 && inp.over(kx, ky - 2.0, kw, kh + 4.0) && dur > 0.0 {
            self.seek_by(if inp.wheel > 0.0 { 5000.0 } else { -5000.0 });
        }
        if let Some(pp) = inp.pos.filter(|_| inp.over(kx, ky - 2.0, kw, kh + 4.0) && dur > 0.0) {
            let f = ((pp.x - kx - thw / 2.0) / (kw - thw)).clamp(0.0, 1.0) as f64;
            self.seek_hover = Some(f * dur);
        }
        if self.drag == Drag::Seek {
            if let Some(pp) = inp.pos {
                let f = ((pp.x - kx - thw / 2.0) / (kw - thw)).clamp(0.0, 1.0) as f64;
                self.seek_preview = f * dur;
                self.osd(format!("SEEK {} / {}", fmt_time(self.seek_preview), fmt_time(dur)));
            }
            if inp.released {
                self.send(command_with("seek", "position_ms", json!(self.seek_preview as u64)));
                self.play.pos_ms = self.seek_preview;
                self.play.pos_ts = now_ms();
            }
        }
        let sf = if dur > 0.0 { (pos / dur) as f32 } else { 0.0 };
        self.nine(p, o, s, "seek_track", kx, ky, kw, kh, 2.0);
        let fw = (kw * sf).round();
        if fw > 0.0 {
            let cp = p.with_clip_rect(Rect::from_min_size(Pos2::new(o.x + kx * s, o.y + ky * s), Vec2::new(fw * s, kh * s)));
            self.nine(&cp, o, s, "seek_fill", kx, ky, kw, kh, 2.0);
        }
        if dur > 0.0 {
            self.spr(p, o, s, "seek_thumb", kx + ((kw - thw) * sf).round(), ky);
        }

        // transport
        if self.button(p, o, s, inp, 0.0, "btn_prev", None) {
            self.send(command("skip_prev"));
        }
        let playing_now = self.play.status == "playing" || self.play.status == "buffering";
        let paused_now = self.play.status == "paused";
        if self.button(p, o, s, inp, 0.0, "btn_play", playing_now.then_some("btn_play_down")) {
            self.resume();
        }
        if self.button(p, o, s, inp, 0.0, "btn_pause", paused_now.then_some("btn_pause_down")) {
            self.send(command("pause"));
        }
        if self.button(p, o, s, inp, 0.0, "btn_next", None) {
            self.send(command("skip_next"));
        }
        let open = self.panel_open();
        if self.button(p, o, s, inp, 0.0, "btn_panel", open.then_some("btn_panel_down")) {
            self.cfg.lock().unwrap().panel_open = !open;
        }
        // like / add to playlist / copy link (only drawn if the skin places them)
        if self.skin.json["pos"]["btn_like"].is_array() {
            let liked = self.liked == Some(true);
            if self.button(p, o, s, inp, 0.0, "btn_like", liked.then_some("btn_like_down")) {
                self.toggle_like();
            }
        }
        // + / -: add the song to the playlist you're playing, or take it out again
        let in_it = self.in_playing_playlist();
        if self.skin.json["pos"]["btn_add"].is_array() && self.button(p, o, s, inp, 0.0, "btn_add", in_it.as_ref().map(|(_, i)| *i).unwrap_or(false).then_some("btn_remove")) {
            match (&self.play.track, in_it) {
                (None, _) => self.osd("NOTHING PLAYING"),
                _ if !self.signed_in => self.osd("SIGN IN FIRST (MENU)"),
                (_, None) => self.osd("PLAY ONE OF YOUR PLAYLISTS"),
                (Some(t), Some((pl, inside))) => {
                    let uri = t.uri.clone();
                    if inside {
                        // the copy in the playlist may be another version of the same song
                        let key = crate::smart::key(&t.name, &t.artists);
                        let real = load_tracks_cache(&self.playlist_uri(&pl))
                            .and_then(|ts| ts.into_iter().find(|x| x.uri == uri || crate::smart::key(&x.name, &x.artists) == key).map(|x| x.uri))
                            .unwrap_or(uri);
                        let _ = self.web.send(Req::RemoveFrom { playlist: pl, uri: real, key });
                    } else {
                        let _ = self.web.send(Req::AddTo { playlist: pl, uri });
                    }
                }
            }
        }
        if self.skin.json["pos"]["btn_link"].is_array() && self.button(p, o, s, inp, 0.0, "btn_link", None) {
            match self.track_link() {
                Some(l) => {
                    self.ctx.copy_text(l);
                    self.osd("SONG LINK COPIED");
                }
                None => self.osd("NOTHING PLAYING"),
            }
        }
        // tooltips for areas that aren't buttons
        for (id, r) in [("art", self.skin.rect("art")), ("vis", self.skin.rect("vis"))] {
            if inp.over(r[0], r[1], r[2], r[3]) {
                self.hover_now = Some(id.into());
            }
        }
        {
            let (tx, ty) = self.skin.pos("timer");
            if inp.over(tx, ty, 54.0, 16.0) {
                self.hover_now = Some("timer".into());
            }
            let (kx, ky, kw, kh) = self.seek_rect();
            if inp.over(kx, ky, kw, kh) {
                self.hover_now = Some("seek".into());
            }
            // small info box: position in the playlist
            if self.skin.json["rect"]["infobox"].is_array() {
                let r = self.skin.rect("infobox");
                let cur = self.play.track.as_ref().map(|t| t.uri.clone()).unwrap_or_default();
                let idx = self.tracks.iter().position(|t| t.uri == cur);
                let txt = match idx {
                    Some(i) => format!("{}/{}", i + 1, self.tracks.len()),
                    None if !self.play.upcoming.is_empty() => format!("+{}", self.play.upcoming.len()),
                    None => "--".into(),
                };
                let w = self.tw(&txt);
                self.text(p, o, s, r[0] + ((r[2] - w) / 2.0).floor(), r[1] + ((r[3] - 7.0) / 2.0).floor(), &txt, self.skin.color("info"));
                if inp.over(r[0], r[1], r[2], r[3]) {
                    self.hover_now = Some("infobox".into());
                    if inp.released && self.pressed.is_none() && self.drag == Drag::None {
                        // jump the playlist list to the song that's playing
                        if let Some(i) = idx {
                            self.set_tab(Tab::Playlist);
                            self.picker = false;
                            self.selected = Some(i);
                            self.scroll[SCROLL_PL] = (i as f32 - 3.0).max(0.0);
                            self.cfg.lock().unwrap().panel_open = true;
                        }
                    }
                }
            }
        }
        // optional logo: only drawn if skin.json has a "logo" position
        if self.skin.json["pos"]["logo"].is_array() {
            let (lx, ly) = self.skin.pos("logo");
            let (lw, lh) = self.spr(p, o, s, "logo", lx, ly);
            if inp.released && inp.over(lx, ly, lw, lh) && self.pressed.is_none() && self.drag == Drag::None {
                self.menu = !self.menu;
            }
        }

        // drag the window by the title bar
        let dr = self.skin.rect("drag");
        if inp.dbl && inp.over(dr[0], dr[1], dr[2], dr[3]) && self.pressed.is_none() {
            let open = self.panel_open();
            self.cfg.lock().unwrap().panel_open = !open;
        } else if inp.pressed && inp.over(dr[0], dr[1], dr[2], dr[3]) && self.pressed.is_none() && self.drag == Drag::None {
            self.ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag);
        }
        animating
    }

    // ---------------- panel ----------------
    /// Accordion layout: (tab, header y, content rect if open). Heights in skin pixels.
    fn sections(&self, oy: f32) -> (Vec<(Tab, f32, Option<[f32; 4]>)>, f32) {
        let hh = self.skin.num("/accordion/header_h", 20.0) as f32;
        let ch = self.skin.num("/accordion/content_h", 96.0) as f32;
        let pw = self.skin.panel_size().0;
        let mut y = oy + 4.0;
        let mut out = Vec::new();
        if self.setup || self.install_page {
            out.push((Tab::Playlist, y, Some([8.0, y + hh, pw - 16.0, 132.0])));
            return (out, 4.0 + hh + 132.0 + 4.0);
        }
        if self.settings {
            out.push((Tab::Playlist, y, Some([8.0, y + hh, pw - 28.0, 160.0])));
            return (out, 4.0 + hh + 160.0 + 4.0);
        }
        for t in [Tab::Playlist, Tab::Queue, Tab::Lyrics, Tab::History] {
            let hy = y;
            y += hh;
            let open = self.focus == Some(t) || self.pinned[t as usize];
            let content = if open {
                let r = [8.0, y, pw - 28.0, ch];
                y += ch + 4.0;
                Some(r)
            } else {
                None
            };
            out.push((t, hy, content));
        }
        (out, y - oy + 4.0)
    }

    fn panel_height(&self) -> f32 {
        self.sections(0.0).1
    }

    /// Draws a PNG stretched to any size, keeping its `b`-pixel border crisp (9-slice).
    fn nine(&self, p: &egui::Painter, o: Pos2, s: f32, name: &str, x: f32, y: f32, w: f32, h: f32, b: f32) {
        let Some(sp) = self.skin.sprite(name) else { return };
        let bx = b.min(sp.w / 2.0).min(w / 2.0);
        let by = b.min(sp.h / 2.0).min(h / 2.0);
        let xs = [(0.0, bx, x, bx), (bx, sp.w - bx, x + bx, w - 2.0 * bx), (sp.w - bx, sp.w, x + w - bx, bx)];
        let ys = [(0.0, by, y, by), (by, sp.h - by, y + by, h - 2.0 * by), (sp.h - by, sp.h, y + h - by, by)];
        for (u0, u1, dx, dw) in xs {
            for (v0, v1, dy, dh) in ys {
                if dw <= 0.0 || dh <= 0.0 || u1 <= u0 || v1 <= v0 {
                    continue;
                }
                let r = Rect::from_min_size(Pos2::new(o.x + dx * s, o.y + dy * s), Vec2::new(dw * s, dh * s));
                let uv = Rect::from_min_max(Pos2::new(u0 / sp.w, v0 / sp.h), Pos2::new(u1 / sp.w, v1 / sp.h));
                p.image(sp.tex.id(), r, uv, Color32::WHITE);
            }
        }
    }

    /// Playlist dropdown under the playlist row: (x, y, w, h) in skin pixels.
    fn drop_rect(&self, oy: f32) -> Option<[f32; 4]> {
        if !self.pl_drop || self.settings || self.setup || self.install_page {
            return None;
        }
        let (secs, _) = self.sections(oy);
        let hy = secs.iter().find(|x| x.0 == Tab::Playlist)?.1;
        let pw = self.skin.panel_size().0;
        let n = self.playlists.len().min(10).max(1) as f32;
        Some([24.0, hy + 16.0, pw - 52.0, n * 10.0 + 4.0])
    }

    fn draw_dropdown(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, r: [f32; 4]) {
        let lh = 10.0;
        let n = self.playlists.len();
        let vis = 10usize;
        if inp.wheel != 0.0 && inp.over(r[0], r[1], r[2], r[3]) {
            let max = n.saturating_sub(vis);
            self.drop_scroll = if inp.wheel > 0.0 { self.drop_scroll.saturating_sub(1) } else { (self.drop_scroll + 1).min(max) };
        }
        self.fill(p, o, s, r[0] - 1.0, r[1] - 1.0, r[2] + 2.0, r[3] + 2.0, Color32::from_rgb(8, 8, 9));
        self.fill(p, o, s, r[0], r[1], r[2], r[3], self.skin.color("menu_bg"));
        let cur = self.sel.as_ref().map(|p| p.id.clone()).unwrap_or_default();
        let mut pick = None;
        for (i, pl) in self.playlists.iter().enumerate().skip(self.drop_scroll).take(vis) {
            let y = r[1] + 2.0 + (i - self.drop_scroll) as f32 * lh;
            let over = inp.over(r[0], y, r[2], lh);
            if over {
                self.fill(p, o, s, r[0], y, r[2], lh, self.skin.color("menu_hover"));
                if inp.released {
                    pick = Some(pl.clone());
                }
            }
            let col = if pl.id == cur { self.skin.color("list_current") } else { self.skin.color("menu_text") };
            let cnt = pl.total.to_string();
            let cw = self.tw(&cnt);
            let name = self.fit(&pl.name, r[2] - cw - 14.0);
            self.text(p, o, s, r[0] + 4.0, y + 1.0, &name, col);
            self.text(p, o, s, r[0] + r[2] - cw - 4.0, y + 1.0, &cnt, self.skin.color("list_dim"));
        }
        if n > vis {
            // little scroll position mark on the right edge
            let k = self.drop_scroll as f32 / (n - vis) as f32;
            self.fill(p, o, s, r[0] + r[2] - 2.0, r[1] + 2.0 + (r[3] - 8.0) * k, 1.0, 4.0, self.skin.color("list_dim"));
        }
        if let Some(pl) = pick {
            self.pl_drop = false;
            self.picker = false;
            self.select_playlist(pl);
            self.set_tab(Tab::Playlist);
        } else if inp.released && !inp.over(r[0], r[1], r[2], r[3]) && !self.drop_fresh {
            self.pl_drop = false;
        }
        self.drop_fresh = false;
    }

    fn draw_panel(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp_full: &Inp, oy: f32) {
        // the dropdown sits on top: nothing under it reacts to the mouse
        let drop = self.drop_rect(oy);
        let masked;
        let inp = match drop {
            Some(r) if inp_full.over(r[0], r[1], r[2], r[3]) => {
                masked = Inp { pos: None, wheel: 0.0, ..inp_full.clone() };
                &masked
            }
            _ => inp_full,
        };
        self.draw_panel_inner(p, o, s, inp, oy);
        if let Some(r) = self.drop_rect(oy) {
            self.draw_dropdown(p, o, s, inp_full, r);
        }
    }

    fn draw_panel_inner(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, oy: f32) {
        let (secs, h) = self.sections(oy);
        let (pw, _) = self.skin.panel_size();
        self.nine(p, o, s, "panel_bg", 0.0, oy, pw, h, 8.0);
        let lh = self.skin.font.as_ref().map(|f| f.line_h).unwrap_or(10.0);
        let hcol = self.skin.color("header");
        let dim = self.skin.color("list_dim");
        let hh = self.skin.num("/accordion/header_h", 20.0) as f32;

        if self.settings && !self.setup {
            let (_, hy, c) = secs[0];
            self.text(p, o, s, 12.0, hy + 5.0, "Settings", hcol);
            let dw = self.text(p, o, s, pw - 36.0, hy + 5.0, "done", dim);
            if inp.released && inp.over(pw - 40.0, hy, dw + 8.0, 16.0) {
                self.settings = false;
            }
            let c = c.unwrap();
            self.nine(p, o, s, "list_bg", c[0], c[1], c[2], c[3], 4.0);
            let sr = [c[0] + c[2] + 4.0, c[1], 8.0, c[3]];
            self.nine(p, o, s, "scroll_bg", sr[0], sr[1], sr[2], sr[3], 4.0);
            self.draw_settings(p, o, s, inp, [c[0] + 3.0, c[1] + 3.0, c[2] - 6.0, c[3] - 6.0], sr);
            return;
        }
        if self.install_page {
            let (_, hy, c) = secs[0];
            self.text(p, o, s, 12.0, hy + 5.0, "Install SpoX", hcol);
            let c = c.unwrap();
            self.nine(p, o, s, "list_bg", c[0], c[1], c[2], c[3], 4.0);
            self.draw_install(p, o, s, inp, [c[0] + 3.0, c[1] + 3.0, c[2] - 6.0, c[3] - 6.0], lh);
            return;
        }
        if self.setup {
            let (_, hy, c) = secs[0];
            self.text(p, o, s, 12.0, hy + 5.0, "Setup", hcol);
            let c = c.unwrap();
            self.nine(p, o, s, "list_bg", c[0], c[1], c[2], c[3], 4.0);
            self.draw_setup(p, o, s, inp, [c[0] + 3.0, c[1] + 3.0, c[2] - 6.0, c[3] - 6.0], lh);
            return;
        }

        for (t, hy, content) in secs {
            // header row
            let (icon, label, tip) = match t {
                Tab::Playlist => (
                    "tab_playlist",
                    match &self.sel {
                        Some(pl) => {
                            let n = if self.tracks_listable && !self.tracks.is_empty() { self.tracks.len() as u32 } else { pl.total };
                            let ms: u64 = self.tracks.iter().map(|t| t.duration_ms).sum();
                            let mins = ms / 60_000;
                            let dur = if ms == 0 { String::new() } else if mins >= 60 { format!("  \u{b7}  {}h {}m", mins / 60, mins % 60) } else { format!("  \u{b7}  {}m", mins) };
                            let smart = if self.smart_on && self.smart_shuffled { "  \u{b7}  SpoX shuffle" } else { "" };
                            let sort = match self.pl_sort.as_str() {
                                "added" => "  \u{b7}  newest",
                                "title" => "  \u{b7}  A-Z",
                                "artist" => "  \u{b7}  by artist",
                                "album" => "  \u{b7}  by album",
                                "length" => "  \u{b7}  by length",
                                _ => "",
                            };
                            match &self.pl_find {
                                Some(f) => format!("Find: {}_  \u{b7}  {} of {}", f, self.tracks.len(), self.tracks_all.len()),
                                None => format!("{}  \u{b7}  {} songs{}{}{}", pl.name, n, dur, sort, smart),
                            }
                        }
                        None => "Playlists".into(),
                    },
                    "tab_playlist",
                ),
                Tab::Queue => {
                    let ms: u64 = self.play.upcoming.iter().map(|t| t.duration_ms).sum();
                    let l = if self.play.upcoming.is_empty() { "Up next".to_string() } else { format!("Up next  \u{b7}  {} songs  \u{b7}  {}m", self.play.upcoming.len(), ms / 60_000) };
                    ("tab_queue", l, "tab_queue")
                }
                Tab::Lyrics => (
                    "tab_lyrics",
                    match &self.lyrics {
                        Some(l) if l.synced && self.lyric_offset != 0 => format!("Lyrics  \u{b7}  synced {:+.2} s", self.lyric_offset as f64 / 1000.0),
                        Some(l) if l.synced => "Lyrics  \u{b7} synced".into(),
                        _ => "Lyrics".into(),
                    },
                    "tab_lyrics",
                ),
                Tab::History => ("tab_history", format!("Recently played  \u{b7}  {}", self.history.len()), "tab_history"),
            };
            let open = content.is_some();
            let row = [8.0, hy, pw - 16.0, hh - 4.0];
            let over = inp.over(row[0], row[1], row[2], row[3]);
            if over {
                self.hover_now = Some(tip.to_string());
            }
            if over && self.skin.sprite("tab_row_hover").is_some() {
                self.nine(p, o, s, "tab_row_hover", row[0], row[1], row[2], row[3], 4.0);
            } else if open && self.skin.sprite("tab_row_open").is_some() {
                self.nine(p, o, s, "tab_row_open", row[0], row[1], row[2], row[3], 4.0);
            }
            let on_name = format!("{}_on", icon);
            self.spr(p, o, s, if open && self.skin.sprite(&on_name).is_some() { &on_name } else { icon }, 8.0, hy);
            let _ = hh;
            let maxw = if t == Tab::Playlist { pw - 82.0 } else { pw - 58.0 };
            let lbl = self.fit(&label, maxw);
            self.text(p, o, s, 28.0, hy + 5.0, &lbl, if open { hcol } else { dim });
            // pin button (same sprites as the main window's pin)
            let pin = [pw - 20.0, hy + 4.0, 8.0, 8.0];
            let on_pin = inp.over(pin[0] - 2.0, hy, pin[2] + 4.0, hh - 4.0);
            let pinned = self.pinned[t as usize];
            let pin_name = if pinned || (on_pin && inp.down) { "btn_pin_down" } else { "btn_pin" };
            self.spr(p, o, s, pin_name, pin[0], pin[1]);
            if on_pin {
                self.hover_now = Some(if pinned { "unpin".into() } else { "pin".into() });
            }
            // playlist chooser toggle on the right of the playlist row
            let chooser = [pw - 40.0, hy, 16.0, hh - 4.0];
            let on_chooser = t == Tab::Playlist && inp.over(chooser[0], chooser[1], chooser[2], chooser[3]);
            if t == Tab::Playlist {
                // a real dropdown box: [ v ]
                let bx = [pw - 40.0, hy + 3.0, 14.0, 10.0];
                let edge = if on_chooser || self.pl_drop { hcol } else { dim };
                self.fill(p, o, s, bx[0], bx[1], bx[2], bx[3], edge);
                self.fill(p, o, s, bx[0] + 1.0, bx[1] + 1.0, bx[2] - 2.0, bx[3] - 2.0, self.skin.color("input_bg"));
                // pixel arrow
                let (ax, ay) = (bx[0] + 4.0, bx[1] + 3.0 + if self.pl_drop { 1.0 } else { 0.0 });
                for (i, w) in [6.0, 4.0, 2.0].iter().enumerate() {
                    let yy = if self.pl_drop { ay + 2.0 - i as f32 } else { ay + i as f32 };
                    self.fill(p, o, s, ax + (6.0 - w) / 2.0, yy, *w, 1.0, edge);
                }
                if on_chooser {
                    self.hover_now = Some("chooser".into());
                }
            }
            let free = self.pressed.is_none() && self.drag == Drag::None;
            if over && inp.released && free {
                if on_pin {
                    self.pinned[t as usize] = !pinned;
                    if !pinned {
                        self.focus = Some(t);
                    }
                    {
                        let mut c = self.cfg.lock().unwrap();
                        c.pinned = [self.pinned[0], self.pinned[1], self.pinned[2]];
                        c.pinned_history = self.pinned[3];
                    }
                    self.osd(if !pinned { "PINNED OPEN" } else { "UNPINNED" });
                } else if on_chooser || (t == Tab::Playlist && open && inp.pos.map(|q| q.x < 28.0 + self.tw(&lbl) + 4.0 && q.x >= 28.0).unwrap_or(false)) {
                    // playlist name or the box: open the dropdown
                    self.pl_drop = !self.pl_drop;
                    self.drop_fresh = true;
                    self.drop_scroll = self.sel.as_ref().and_then(|c| self.playlists.iter().position(|p| p.id == c.id)).unwrap_or(0).saturating_sub(4);
                    self.set_tab(t);
                } else if self.focus == Some(t) && !self.pinned[t as usize] {
                    self.focus = None; // click the open one again to fold it
                } else {
                    self.set_tab(t);
                }
            }
            if let Some(c) = content {
                self.nine(p, o, s, "list_bg", c[0], c[1], c[2], c[3], 4.0);
                let sr = [c[0] + c[2] + 4.0, c[1], 8.0, c[3]];
                self.nine(p, o, s, "scroll_bg", sr[0], sr[1], sr[2], sr[3], 4.0);
                self.draw_section(p, o, s, inp, t, [c[0] + 3.0, c[1] + 3.0, c[2] - 6.0, c[3] - 6.0], sr);
            }
        }
    }

    fn draw_section(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, tab: Tab, lr: [f32; 4], sr: [f32; 4]) {
        let lh = self.skin.font.as_ref().map(|f| f.line_h).unwrap_or(10.0);
        let rows_vis = (lr[3] / lh).floor().max(1.0) as usize;
        let dim = self.skin.color("list_dim");
        // rows
        struct Row {
            left: String,
            right: String,
            color: Color32,
            center: bool,
        }
        let cur_uri = self.play.track.as_ref().map(|t| t.uri.clone()).unwrap_or_default();
        let list_col = self.skin.color("list");
        let cur_col = self.skin.color("list_current");
        let mut rows: Vec<Row> = Vec::new();
        let mut empty_msg: Vec<String> = Vec::new();
        let scroll_idx;
        let mut current_row: Option<usize> = None;
        let mut q_map: Vec<Option<usize>> = Vec::new();
        // rows picked with Ctrl+click (Up next)
        let mut picked_rows: Vec<usize> = Vec::new();
        match tab {
            Tab::Playlist if self.picker || self.sel.is_none() => {
                scroll_idx = SCROLL_PICK;
                for pl in &self.playlists {
                    let is_sel = self.sel.as_ref().map(|x| x.uri == pl.uri).unwrap_or(false);
                    rows.push(Row {
                        left: pl.name.clone(),
                        right: if pl.id == "liked" { String::new() } else { pl.total.to_string() },
                        color: if is_sel { cur_col } else { list_col },
                        center: false,
                    });
                }
                if self.playlists.is_empty() {
                    let has_client = !self.cfg.lock().unwrap().client_id.is_empty();
                    empty_msg = if !has_client {
                        vec!["Add a Client ID in Setup".into(), "to see your playlists.".into(), String::new(), "Click here to open Setup".into()]
                    } else if !self.signed_in {
                        vec!["Click here to sign in to Spotify".into(), "(your browser opens once)".into()]
                    } else {
                        vec!["Loading playlists...".into()]
                    };
                }
            }
            Tab::Playlist => {
                scroll_idx = SCROLL_PL;
                for (i, t) in self.tracks.iter().enumerate() {
                    let is_cur = t.uri == cur_uri;
                    let left = if t.artists.is_empty() { format!("{}. {}", i + 1, t.name) } else { format!("{}. {} - {}", i + 1, t.artists, t.name) };
                    rows.push(Row { left, right: fmt_time(t.duration_ms as f64), color: if is_cur { cur_col } else { list_col }, center: false });
                }
                if rows.is_empty() {
                    empty_msg = if !self.tracks_listable {
                        vec![
                            "Spotify only lists songs of".into(),
                            "playlists you own or collaborate on.".into(),
                            String::new(),
                            "Double-click here to play it,".into(),
                            "then see the Up next tab.".into(),
                        ]
                    } else if self.tracks_loading {
                        vec!["Loading songs...".into()]
                    } else {
                        vec!["This playlist is empty.".into()]
                    };
                }
            }
            Tab::Queue => {
                scroll_idx = SCROLL_Q;
                // grouped like Spotify's queue: Next in queue / Next from <playlist> / Autoplay
                let mut last_group = String::new();
                // songs smart shuffle brought in: a soft cyan, so they stand out from your own songs
                let new_col = self.skin.json["colors"]["list_new"].as_str().and_then(crate::skin::parse_color).unwrap_or(Color32::from_rgb(126, 214, 230));
                // SpoX shuffle: its own order (the whole rest of the list); else Soloist's queue
                self.q_list = if self.smart_on {
                    self.smart_upcoming
                        .iter()
                        .map(|(t, k)| {
                            let mut t = t.clone();
                            t.provider = if k == "user" { "queue".into() } else { "spox".into() };
                            t
                        })
                        .collect()
                } else {
                    self.play.upcoming.iter().filter(|t| !self.q_removed.contains(&t.uri)).cloned().collect()
                };
                self.q_picked.retain(|u| self.q_list.iter().any(|t| &t.uri == u));
                let list = self.q_list.clone();
                for (i, t) in list.iter().enumerate() {
                    let p = &t.provider;
                    let group = if p.contains("spox") {
                        let n = if self.smart_list.is_empty() { "your list".to_string() } else { self.smart_list.clone() };
                        if self.smart_shuffled { format!("SpoX shuffle \u{b7} {}", n) } else { format!("Next from {}", n) }
                    } else if p.contains("queue") {
                        "Next in queue".to_string()
                    } else if p.contains("autoplay") || p.contains("recommend") {
                        "Autoplay (Spotify's picks)".to_string()
                    } else if p.contains("context") {
                        let n = if self.play.context_name.is_empty() { "this playlist".to_string() } else { self.play.context_name.clone() };
                        format!("Next from {}", n)
                    } else {
                        String::new()
                    };
                    if !group.is_empty() && group != last_group {
                        rows.push(Row { left: group.clone(), right: String::new(), color: dim, center: false });
                        q_map.push(None);
                        last_group = group;
                    }
                    // highlighted: songs you've never listened to, and songs that aren't from your
                    // playlist (smart shuffle / Spotify's autoplay picks)
                    let never = crate::smart::knows(&t.name, &t.artists) == Some(false);
                    let outside = self.smart_new.contains(&t.uri) || p.contains("autoplay") || p.contains("recommend");
                    let is_new = never || outside;
                    if self.q_picked.contains(&t.uri) {
                        picked_rows.push(rows.len());
                    }
                    rows.push(Row {
                        left: format!("{} - {}", t.artists, t.name),
                        right: if never { "NEW".into() } else if outside { "pick".into() } else if t.duration_ms > 0 { fmt_time(t.duration_ms as f64) } else { String::new() },
                        color: if is_new { new_col } else { list_col },
                        center: false,
                    });
                    q_map.push(Some(i));
                }
                if rows.is_empty() {
                    empty_msg = vec![if !self.smart_busy.is_empty() { self.smart_busy.clone() } else if self.connected { "Nothing queued.".into() } else { "Waiting for Soloist...".into() }];
                }
            }
            Tab::History => {
                scroll_idx = SCROLL_HIST;
                let now = config::now_secs();
                for (t, at) in &self.history {
                    let ago = (now - at).max(0);
                    let r = if ago < 60 { "now".to_string() } else if ago < 3600 { format!("{}m", ago / 60) } else if ago < 86400 { format!("{}h", ago / 3600) } else { format!("{}d", ago / 86400) };
                    let is_cur = t.uri == cur_uri;
                    rows.push(Row { left: format!("{} - {}", t.artists, t.name), right: r, color: if is_cur { cur_col } else { list_col }, center: false });
                }
                if rows.is_empty() {
                    empty_msg = vec!["Songs you play show up here,".into(), "even ones started from your phone.".into()];
                }
            }
            Tab::Lyrics => {
                scroll_idx = SCROLL_LY;
                let key = format!("{}|{}|{}", self.lyrics_uri, lr[2], self.lyrics.as_ref().map(|l| l.lines.len()).unwrap_or(0));
                if key != self.lyric_rows_key {
                    self.lyric_rows_key = key;
                    self.lyric_rows.clear();
                    if let (Some(l), Some(f)) = (&self.lyrics, &self.skin.font) {
                        for (i, (_, line)) in l.lines.iter().enumerate() {
                            let txt = if line.is_empty() { "\u{b7}".to_string() } else { line.clone() };
                            for part in f.wrap(&txt, lr[2] - 4.0) {
                                self.lyric_rows.push((i, part));
                            }
                        }
                    }
                }
                // a little early (people read ahead) + your per-song nudge
                let pos = self.play.position(now_ms()) + 250.0 + self.lyric_offset as f64;
                let cur_line = self
                    .lyrics
                    .as_ref()
                    .filter(|l| l.synced)
                    .and_then(|l| l.lines.iter().rposition(|(t, _)| (*t as f64) <= pos));
                let past = self.skin.color("lyric");
                let lcc = self.skin.color("lyric_current");
                let next = self.skin.json["colors"]["lyric_next"].as_str().and_then(crate::skin::parse_color).unwrap_or(Color32::from_rgb(90, 93, 98));
                let synced = self.lyrics.as_ref().map(|l| l.synced).unwrap_or(false);
                for (i, (li, t)) in self.lyric_rows.iter().enumerate() {
                    let is_cur = Some(*li) == cur_line;
                    if is_cur && current_row.is_none() {
                        current_row = Some(i);
                    }
                    let col = if is_cur {
                        lcc
                    } else if !synced || cur_line.map(|c| *li < c).unwrap_or(false) {
                        past
                    } else {
                        next
                    };
                    rows.push(Row { left: t.clone(), right: String::new(), color: col, center: true });
                }
                // auto-scroll unless you scrolled by hand in the last 5 s
                let manual = self.manual_lyrics_until.map(|t| Instant::now() < t).unwrap_or(false);
                if !manual {
                    if let Some(cr) = current_row {
                        let target = cr as f32 - (rows_vis as f32 / 2.0 - 1.0).floor();
                        self.scroll[SCROLL_LY] = target.max(0.0);
                    }
                }
                if rows.is_empty() {
                    empty_msg = vec![if self.play.track.is_none() {
                        "Play something to see lyrics.".into()
                    } else if self.lyrics_loading && self.lyrics_since.elapsed() < Duration::from_millis(900) {
                        // usually found in a moment (looked up ahead of time): no message flashing
                        self.ctx.request_repaint_after(Duration::from_millis(150));
                        String::new()
                    } else if self.lyrics_loading {
                        "Looking for lyrics...".into()
                    } else {
                        "No lyrics found on LRCLIB.".into()
                    }];
                }
            }
        }

        // scrolling
        let max_scroll = rows.len().saturating_sub(rows_vis) as f32;
        if inp.wheel != 0.0 && inp.ctrl && scroll_idx == SCROLL_LY && inp.over(lr[0], lr[1], lr[2], lr[3]) {
            // Ctrl + scroll on the lyrics: nudge them earlier / later for this song (remembered)
            self.lyric_offset += if inp.wheel > 0.0 { 250 } else { -250 };
            save_lyric_offset(&self.lyrics_uri, self.lyric_offset);
            self.osd(format!("LYRICS {:+.2} S", self.lyric_offset as f64 / 1000.0));
        } else if inp.wheel != 0.0 && (inp.over(lr[0], lr[1], lr[2], lr[3]) || inp.over(sr[0], sr[1], sr[2], sr[3])) {
            self.scroll[scroll_idx] -= inp.wheel / (lh * s) * 1.5;
            if scroll_idx == SCROLL_LY {
                self.manual_lyrics_until = Some(Instant::now() + Duration::from_secs(5));
            }
        }
        let (thw, thh) = self.skin.size("scroll_thumb");
        if inp.pressed && inp.over(sr[0], sr[1], sr[2], sr[3]) && self.pressed.is_none() {
            self.drag = Drag::Scroll;
            self.drag_scroll = scroll_idx;
        }
        if self.drag == Drag::Scroll && self.drag_scroll == scroll_idx {
            if let Some(pp) = inp.pos {
                let f = ((pp.y - sr[1] - thh / 2.0) / (sr[3] - thh)).clamp(0.0, 1.0);
                self.scroll[scroll_idx] = (f * max_scroll).round();
                if scroll_idx == SCROLL_LY {
                    self.manual_lyrics_until = Some(Instant::now() + Duration::from_secs(5));
                }
            }
        }
        self.scroll[scroll_idx] = self.scroll[scroll_idx].clamp(0.0, max_scroll);
        let first = self.scroll[scroll_idx].floor() as usize;
        if max_scroll > 0.0 {
            let f = first as f32 / max_scroll;
            self.spr(p, o, s, "scroll_thumb", sr[0] + ((sr[2] - thw) / 2.0).floor(), sr[1] + ((sr[3] - thh) * f).round());
        }

        // draw rows
        let cp = self.clip(p, o, s, lr);
        let sel_bg = self.skin.color("list_selected_bg");
        if rows.is_empty() {
            for (i, m) in empty_msg.iter().enumerate() {
                let w = self.tw(m);
                self.text(&cp, o, s, lr[0] + ((lr[2] - w) / 2.0).floor(), lr[1] + 2.0 + i as f32 * lh, m, dim);
            }
        }
        let hovered_row = inp
            .pos
            .filter(|_| inp.over(lr[0], lr[1], lr[2], lr[3]))
            .map(|pp| first + ((pp.y - lr[1]) / lh).floor() as usize);
        if let Some(r) = hovered_row.filter(|r| *r < rows.len()) {
            let row = &rows[r];
            // only when the text is cut off: show it in full
            if self.tw(&row.left) > lr[2] - self.tw(&row.right) - 8.0 {
                self.hover_now = Some(format!("row:{}", row.left));
            }
        }
        let sel_row = if scroll_idx == SCROLL_PL || scroll_idx == SCROLL_PICK {
            self.selected
        } else if scroll_idx == SCROLL_HIST {
            self.hist_scroll_sel
        } else if scroll_idx == SCROLL_Q {
            self.q_selected.and_then(|qi| q_map.iter().position(|m| *m == Some(qi)))
        } else {
            None
        };
        for (i, r) in rows.iter().enumerate().skip(first).take(rows_vis) {
            let y = lr[1] + (i - first) as f32 * lh;
            if sel_row == Some(i) || picked_rows.contains(&i) {
                self.fill(&cp, o, s, lr[0], y, lr[2], lh, sel_bg);
            }
            let ty = y + 1.0;
            if r.center {
                let w = self.tw(&r.left);
                self.text(&cp, o, s, lr[0] + ((lr[2] - w) / 2.0).floor(), ty, &r.left, r.color);
            } else {
                let rw = self.tw(&r.right);
                let left = self.fit(&r.left, lr[2] - rw - 8.0);
                self.text(&cp, o, s, lr[0] + 1.0, ty, &left, r.color);
                if !r.right.is_empty() {
                    self.text(&cp, o, s, lr[0] + lr[2] - rw - 1.0, ty, &r.right, if r.color == cur_col { cur_col } else { dim });
                }
            }
        }

        // clicks on rows
        let in_list = inp.over(lr[0], lr[1], lr[2], lr[3]) && self.pressed.is_none() && self.drag == Drag::None;
        // right-click a song: small menu (play, queue, like, add to playlist, copy link)
        if in_list && inp.right {
            let clicked = hovered_row.filter(|r| *r < rows.len());
            let mut items: Vec<(String, CtxAct)> = Vec::new();
            match tab {
                Tab::Playlist if !(self.picker || self.sel.is_none()) => {
                    if let Some(r) = clicked {
                        let u = self.tracks[r].uri.clone();
                        self.selected = Some(r);
                        items = vec![
                            ("Play".into(), CtxAct::PlayTrack(r)),
                            ("Add to queue".into(), CtxAct::QueueTrack(u.clone())),
                            ("Save to Liked Songs".into(), CtxAct::Like(u.clone())),
                            ("Add to playlist...".into(), CtxAct::AddTo(u.clone())),
                            ("Copy song link".into(), CtxAct::CopyLink(u)),
                            (format!("Sort: {}  (O)", sort_name(&self.pl_sort)), CtxAct::Sort),
                            ("Find in playlist  (Ctrl+F)".into(), CtxAct::Find),
                        ];
                    }
                }
                Tab::History => {
                    if let Some(r) = clicked {
                        let u = self.history[r].0.uri.clone();
                        self.hist_scroll_sel = Some(r);
                        items = vec![
                            ("Play".into(), CtxAct::PlayUri(u.clone())),
                            ("Add to queue".into(), CtxAct::QueueTrack(u.clone())),
                            ("Save to Liked Songs".into(), CtxAct::Like(u.clone())),
                            ("Add to playlist...".into(), CtxAct::AddTo(u.clone())),
                            ("Copy song link".into(), CtxAct::CopyLink(u)),
                        ];
                    }
                }
                Tab::Queue => {
                    if let Some(Some(qi)) = clicked.and_then(|r| q_map.get(r).cloned()) {
                        let u = self.q_list[qi].uri.clone();
                        self.q_selected = Some(qi);
                        // the picked songs (Ctrl+click), or just this one
                        let picks: Vec<String> = if self.q_picked.contains(&u) { self.q_list.iter().filter(|t| self.q_picked.contains(&t.uri)).map(|t| t.uri.clone()).collect() } else { vec![u.clone()] };
                        let n = picks.len();
                        items = vec![
                            ("Skip to this song".into(), CtxAct::SkipTo(qi)),
                            (if n > 1 { format!("Play these {} next", n) } else { "Play next".into() }, CtxAct::PlayNextQ(picks.clone())),
                            (if n > 1 { format!("Remove {} from Up next", n) } else { "Remove from Up next".into() }, CtxAct::RemoveQ(picks)),
                            ("Save to Liked Songs".into(), CtxAct::Like(u.clone())),
                            ("Add to playlist...".into(), CtxAct::AddTo(u.clone())),
                            ("Copy song link".into(), CtxAct::CopyLink(u)),
                        ];
                    }
                }
                _ => {}
            }
            if !items.is_empty() {
                let p = inp.pos.unwrap_or(Pos2::ZERO);
                self.ctx_menu = Some(CtxMenu { x: p.x, y: p.y, items });
                self.right_used = true;
            }
        }
        if in_list && (inp.released || inp.dbl) {
            let clicked = hovered_row.filter(|r| *r < rows.len());
            match tab {
                Tab::Playlist if self.picker || self.sel.is_none() => {
                    if rows.is_empty() && inp.released {
                        let has_client = !self.cfg.lock().unwrap().client_id.is_empty();
                        if !has_client {
                            self.setup = true;
                            self.setup_focus = Some(1);
                        } else if !self.signed_in {
                            let _ = self.web.send(Req::SignIn);
                            self.osd("OPENING BROWSER...");
                        }
                    } else if let Some(r) = clicked {
                        let pl = self.playlists[r].clone();
                        if inp.dbl {
                            self.play_playlist(&pl);
                        } else if inp.released {
                            self.selected = Some(r);
                            self.select_playlist(pl);
                            self.picker = false;
                        }
                    }
                }
                Tab::Playlist => {
                    if rows.is_empty() && inp.dbl && !self.tracks_listable {
                        if let Some(pl) = self.sel.clone() {
                            self.play_playlist(&pl);
                            self.set_tab(Tab::Queue);
                        }
                    } else if let Some(r) = clicked {
                        if inp.dbl {
                            self.play_track(r);
                        } else {
                            self.selected = Some(r);
                        }
                    }
                }
                Tab::Lyrics => {
                    // click a synced line to jump there
                    if inp.dbl {
                        if let (Some(r), Some(l)) = (clicked, &self.lyrics) {
                            if l.synced {
                                let li = self.lyric_rows[r].0;
                                let t = l.lines[li].0;
                                self.send(command_with("seek", "position_ms", json!(t)));
                                self.manual_lyrics_until = None;
                            }
                        }
                    }
                }
                Tab::History => {
                    if let Some(r) = clicked {
                        if inp.dbl {
                            let u = self.history[r].0.uri.clone();
                            self.run_ctx(CtxAct::PlayUri(u));
                        } else {
                            self.hist_scroll_sel = Some(r);
                        }
                    }
                }
                Tab::Queue => {
                    if let Some(Some(qi)) = clicked.and_then(|r| q_map.get(r).cloned()) {
                        if inp.dbl {
                            self.run_ctx(CtxAct::SkipTo(qi));
                        } else if inp.ctrl {
                            // Ctrl+click: pick several (then right-click or Delete)
                            let u = self.q_list[qi].uri.clone();
                            if !self.q_picked.remove(&u) {
                                self.q_picked.insert(u);
                            }
                        } else {
                            self.q_picked.clear();
                            self.q_selected = Some(qi);
                        }
                    }
                }
            }
        }
    }

    fn run_ctx(&mut self, a: CtxAct) {
        match a {
            CtxAct::PlayTrack(i) => self.play_track(i),
            CtxAct::PlayUri(u) => {
                if self.smart_on {
                    self.stop_smart();
                }
                if !self.play.is_active {
                    self.send(command("activate"));
                }
                self.send(command_with("play", "uri", json!(u)));
            }
            CtxAct::QueueTrack(u) => {
                self.send(command_with("add_to_queue", "uri", json!(u)));
                if self.smart_on {
                    let t = self.tracks_all.iter().chain(self.history.iter().map(|h| &h.0)).find(|t| t.uri == u).cloned();
                    if let (Some(t), Some(sm)) = (t, &self.smart) {
                        let _ = sm.send(crate::smart::Cmd::Queued(t));
                    }
                }
                self.osd("ADDED TO QUEUE");
                if self.queue_visible() {
                    self.send(command_with("get_queue", "limit", json!(0)));
                }
            }
            CtxAct::Sort => self.cycle_sort(),
            CtxAct::Find => self.start_find(),
            CtxAct::RemoveQ(uris) => {
                let n = uris.len();
                if !self.smart_on {
                    self.q_removed.extend(uris.iter().cloned());
                }
                if let Some(sm) = &self.smart {
                    let _ = sm.send(crate::smart::Cmd::Remove(uris));
                }
                self.q_picked.clear();
                self.q_selected = None;
                self.osd(if n == 1 { "REMOVED FROM UP NEXT".to_string() } else { format!("REMOVED {} FROM UP NEXT", n) });
            }
            CtxAct::PlayNextQ(uris) => {
                let picks: Vec<Track> = uris.iter().filter_map(|u| self.q_list.iter().find(|t| &t.uri == u).cloned()).collect();
                if self.smart_on {
                    if let Some(sm) = &self.smart {
                        let _ = sm.send(crate::smart::Cmd::PlayNext(picks));
                    }
                } else {
                    // Spotify's queue: queue them again in front (their old spot is skipped)
                    self.q_removed.extend(uris.iter().cloned());
                    if let Some(sm) = &self.smart {
                        let _ = sm.send(crate::smart::Cmd::Remove(uris.clone()));
                    }
                    for u in &uris {
                        self.send(command_with("add_to_queue", "uri", json!(u)));
                    }
                }
                self.q_picked.clear();
                self.osd("PLAYING NEXT");
                self.send(command_with("get_queue", "limit", json!(0)));
            }
            CtxAct::SkipTo(i) => {
                if self.smart_on {
                    if let (Some(t), Some(sm)) = (self.q_list.get(i), &self.smart) {
                        let _ = sm.send(crate::smart::Cmd::JumpTo(t.uri.clone()));
                        self.q_selected = None;
                        return;
                    }
                }
                // position in Soloist's own queue (Up next hides songs you removed)
                let i = self.q_list.get(i).and_then(|t| self.play.upcoming.iter().position(|u| u.uri == t.uri)).unwrap_or(i);
                // a song from the playlist itself: start the playlist right at that song (one jump,
                // nothing skipped through, other devices see it straight away)
                if let Some(t) = self.play.upcoming.get(i).cloned() {
                    let ctx_uri = self.play.context_uri.clone();
                    if self.signed_in && !ctx_uri.is_empty() && t.provider.contains("context") && !ctx_uri.contains(":collection") {
                        let _ = self.web.send(Req::PlayIn { context: ctx_uri, track: t.uri.clone(), device_name: self.device_name(), fallback_uris: Vec::new() });
                        let tx = self.solo.cmd.clone();
                        std::thread::spawn(move || {
                            std::thread::sleep(Duration::from_millis(1500));
                            let _ = tx.send(command_with("get_queue", "limit", json!(50)));
                        });
                        return;
                    }
                }
                // otherwise Soloist has no "jump to": skip forward the right number of times, one at
                // a time (sent all at once, Soloist drops most of them while the next song loads)
                let n = i.min(40) + 1;
                crate::smart::hold_for(Duration::from_millis(450 * n as u64 + 2500));
                let tx = self.solo.cmd.clone();
                std::thread::spawn(move || {
                    for _ in 0..n {
                        let _ = tx.send(command("skip_next"));
                        std::thread::sleep(Duration::from_millis(450));
                    }
                    let _ = tx.send(command_with("get_queue", "limit", json!(50)));
                });
                self.q_selected = None;
                self.osd("SKIPPING...");
            }
            CtxAct::Like(u) => {
                if !self.signed_in {
                    self.osd("SIGN IN FIRST (MENU)");
                } else {
                    let _ = self.web.send(Req::SetLiked(u, true));
                }
            }
            CtxAct::AddTo(u) => {
                self.add_target = Some(u);
                self.add_menu = true;
                self.add_scroll = 0;
            }
            CtxAct::CopyLink(u) => {
                let mut it = u.split(':');
                if let (Some(_), Some(k), Some(id)) = (it.next(), it.next(), it.next()) {
                    self.ctx.copy_text(format!("https://open.spotify.com/{}/{}", k, id));
                    self.osd("SONG LINK COPIED");
                }
            }
        }
    }

    fn draw_ctx_menu(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, win_w: f32) {
        let Some(m) = &self.ctx_menu else { return };
        let lh = 10.0;
        let w = m.items.iter().map(|(t, _)| self.tw(t)).fold(0.0, f32::max) + 12.0;
        let h = m.items.len() as f32 * lh + 4.0;
        let x = m.x.min(win_w - w - 2.0).max(2.0);
        let y = m.y;
        self.fill(p, o, s, x - 1.0, y - 1.0, w + 2.0, h + 2.0, Color32::from_rgb(10, 10, 11));
        self.fill(p, o, s, x, y, w, h, self.skin.color("menu_bg"));
        let mut chosen = None;
        for (i, (t, a)) in m.items.iter().enumerate() {
            let iy = y + 2.0 + i as f32 * lh;
            if inp.over(x, iy, w, lh) {
                self.fill(p, o, s, x, iy, w, lh, self.skin.color("menu_hover"));
                if inp.released {
                    chosen = Some(a.clone());
                }
            }
            self.text(p, o, s, x + 6.0, iy + 1.0, t, self.skin.color("menu_text"));
        }
        if let Some(a) = chosen {
            self.ctx_menu = None;
            self.run_ctx(a);
        } else if (inp.released || inp.right) && !inp.over(x, y, w, h) && !self.right_used {
            self.ctx_menu = None;
        }
    }

    /// (label, value, id). id 0 = section heading.
    fn settings_rows(&self) -> Vec<(String, String, u16)> {
        let c = self.cfg.lock().unwrap().clone();
        let mut out = Vec::new();
        for r in self.settings_rows_base() {
            if r.0 == "@lab" {
                if c.dev_mode {
                    out.extend(Self::lab_rows(&c));
                }
            } else {
                out.push(r);
            }
        }
        out
    }

    fn settings_rows_base(&self) -> Vec<(String, String, u16)> {
        let c = self.cfg.lock().unwrap().clone();
        let onoff = |b: bool| if b { "on" } else { "off" }.to_string();
        let act = |a: &str| match a {
            "toggle_main" => "show/hide SpoX",
            "show_main" => "show SpoX",
            "play_pause" => "play / pause",
            "next" => "next song",
            "prev" => "previous song",
            "like" => "like song",
            "copy_link" => "copy song link",
            "seek_fwd" => "forward 10 s",
            "seek_back" => "back 10 s",
            "mute" => "mute / unmute",
            "shuffle" => "cycle shuffle",
            "volume" => "volume",
            "seek" => "seek",
            "track" => "change song",
            _ => "nothing",
        }
        .to_string();
        vec![
            ("General".into(), String::new(), 0),
            ("When SpoX closes".into(), match c.on_close.as_str() { "keep" => "keep playing", "pause" => "pause music", _ => "stop Soloist" }.into(), 1),
            ("Minimize button".into(), if c.tray { "hide to tray" } else { "minimize" }.into(), 2),
            ("Start with Windows".into(), onoff(c.start_with_windows), 3),
            ("Start hidden in tray".into(), onoff(c.start_in_tray), 4),
            ("Always on top".into(), onoff(c.always_on_top), 5),
            ("Window size".into(), format!("{}x", c.scale), 6),
            ("Look".into(), String::new(), 0),
            ("Visualizer".into(), format!("{}{}", if c.vis_style == "mirror" { "mirror" } else { "bars" }, if c.vis_on { "" } else { " (frozen)" }), 10),
            ("Album art".into(), if c.art_mode == "sharp" { "sharp" } else { "pixel" }.into(), 11),
            ("Pixel art colours".into(), c.art_colors.to_string(), 12),
            ("CAPITAL titles".into(), onoff(c.uppercase), 13),
            ("Tooltips".into(), onoff(c.tooltips), 14),
            ("My name + picture".into(), onoff(c.show_profile), 15),
            ("Style lab (advanced)".into(), onoff(c.dev_mode), 70),
            ("Colour pack".into(), c.color_pack.clone(), 16),
            ("Hide animation".into(), match c.collapse_anim.as_str() { "dissolve" => "pixel dissolve", "shutter" => "shutter", "none" => "none", _ => "TV switch-off" }.into(), 19),
            ("@lab".into(), String::new(), 0),
            ("Smart shuffle".into(), String::new(), 0),
            ("SpoX shuffle: new songs".into(), format!("{}%", c.smart_ratio), 20),
            ("Same artist twice in a row".into(), if c.smart_avoid_artist { "avoid" } else { "allow" }.into(), 21),
            ("Last.fm key (more new songs)".into(), if c.lastfm_key.is_empty() { "add...".into() } else { "set".into() }, 22),
            ("Taskbar player".into(), String::new(), 0),
            ("Show on taskbar".into(), onoff(c.tb_on), 30),
            ("Monitor".into(), match c.tb_monitor.as_str() { "main" => "main".to_string(), "follow" => "follow active window".to_string(), n => format!("monitor {}", n) }, 41),
            ("Position".into(), match c.tb_pos.as_str() { "left" => format!("left side (+{}px)", c.tb_left_offset), "custom" => format!("custom ({}px)", c.tb_offset), _ => "next to tray".to_string() }, 31),
            ("Left side offset (Ctrl+scroll)".into(), format!("{}px", c.tb_left_offset), 42),
            ("Text width".into(), format!("{}px", c.tb_text_w), 32),
            ("When idle (or paused 10 s)".into(), match c.tb_idle.as_str() { "icon" => "just the icon", "hide" => "hide", _ => "dim last song" }.into(), 33),
            ("Shuffle button".into(), onoff(c.tb_shuffle_btn), 49),
            ("Hide tray icon while it's on".into(), onoff(c.hide_tray_with_player), 64),
            ("Text lines".into(), match c.tb_lines.as_str() { "one" => "one (scrolls)", "two" => "two", _ => "auto (fits)" }.into(), 43),
            ("Dim when paused".into(), onoff(c.tb_paused_dim), 44),
            ("Volume bar".into(), onoff(c.tb_volume_bar), 45),
            ("Show".into(), if c.tb_show_when == "hidden" { "only when SpoX is hidden" } else { "always" }.into(), 46),
            ("Over fullscreen apps".into(), match c.tb_fullscreen.as_str() { "hide" => "hide", "normal" => "stay", "info" => "info only", _ => "move to other monitor" }.into(), 34),
            ("Click album art".into(), act(&c.tb_click_art), 35),
            ("Click song text".into(), act(&c.tb_click_text), 36),
            ("Double-click song text".into(), act(&c.tb_dbl_text), 37),
            ("Middle-click".into(), act(&c.tb_middle), 38),
            ("Right-click".into(), act(&c.tb_right), 39),
            ("Scroll wheel".into(), act(&c.tb_wheel), 40),
            ("Discord".into(), String::new(), 0),
            ("Show song on Discord".into(), onoff(c.discord), 47),
            ("Friends with SpoX can join".into(), onoff(c.discord_join), 78),
            ("Status".into(), {
                let st = crate::discord::STATE.lock().unwrap().clone();
                if !c.discord { "off".into() } else if st.is_empty() { "starting...".into() } else { st }
            }, 79),
            ("App".into(), String::new(), 0),
            ("Update automatically".into(), onoff(c.auto_update), 63),
            ("Check for updates".into(), { let u = crate::update::status(); if u.is_empty() { "check now".into() } else { u } }, 96),
            ("Memory (RAM)".into(), self.mem_text.clone(), 61),
            ("Install SpoX for this user".into(), if crate::install::is_installed() { "installed".into() } else { "install...".into() }, 62),
            ("Account".into(), String::new(), 0),
            ("Soloist key / Client ID".into(), "edit...".into(), 50),
            ("Spotify sign-in".into(), if self.signed_in { "sign in again".into() } else { "sign in".into() }, 51),
        ]
    }

    /// Style lab rows (only with Style lab on), inserted after the Look section.
    fn lab_rows(c: &crate::config::Config) -> Vec<(String, String, u16)> {
        let mut v: Vec<(String, String, u16)> = vec![
            ("Style lab".into(), String::new(), 0),
            ("Hue turn".into(), format!("{:+}\u{b0}", c.color_hue), 17),
            ("Colour strength".into(), format!("{}%", c.color_sat), 71),
            ("Grey tint".into(), c.grey_tint.clone(), 72),
            ("Grey brightness".into(), format!("{}%", c.grey_light), 73),
            ("Button edges".into(), c.bevel.clone(), 74),
            ("Text shadow".into(), if c.text_shadow { "on" } else { "off" }.into(), 75),
            ("Text colours".into(), c.text_style.clone(), 76),
        ];
        for (i, (k, name)) in crate::skin::EDITABLE.iter().enumerate() {
            v.push((name.to_string(), c.color_over.get(*k).cloned().unwrap_or_else(|| "from pack".into()), 80 + i as u16));
        }
        v.push(("Save all this as a pack".into(), "save...".into(), 18));
        v.push(("Reset style lab".into(), "reset".into(), 77));
        v
    }

    fn apply_setting(&mut self, id: u16, back: bool) {
        let cycle = |cur: &str, opts: &[&str]| -> String {
            let n = opts.len();
            let i = opts.iter().position(|o| *o == cur).map(|i| if back { (i + n - 1) % n } else { (i + 1) % n }).unwrap_or(0);
            opts[i].to_string()
        };
        let actions = crate::tb::ACTIONS;
        let mut redo_art = false;
        let mut reskin = false;
        let mut saved_pack: Option<String> = None;
        let mut startup: Option<bool> = None;
        {
            let mut c = self.cfg.lock().unwrap();
            match id {
                1 => c.on_close = cycle(&c.on_close, &["quit", "pause", "keep"]),
                2 => c.tray = !c.tray,
                3 => {
                    c.start_with_windows = !c.start_with_windows;
                    startup = Some(c.start_with_windows);
                }
                4 => c.start_in_tray = !c.start_in_tray,
                5 => {
                    c.always_on_top = !c.always_on_top;
                    self.ctx.send_viewport_cmd(egui::ViewportCommand::WindowLevel(if c.always_on_top { egui::WindowLevel::AlwaysOnTop } else { egui::WindowLevel::Normal }));
                }
                6 => c.scale = if back { if c.scale <= 1 { 4 } else { c.scale - 1 } } else if c.scale >= 4 { 1 } else { c.scale + 1 },
                10 => {
                    // bars -> mirror -> frozen
                    match (c.vis_on, c.vis_style.as_str()) {
                        (true, "bars") => c.vis_style = "mirror".into(),
                        (true, _) => c.vis_on = false,
                        (false, _) => {
                            c.vis_on = true;
                            c.vis_style = "bars".into();
                        }
                    }
                }
                11 => {
                    c.art_mode = if c.art_mode == "sharp" { "pixel".into() } else { "sharp".into() };
                    redo_art = true;
                }
                12 => {
                    c.art_colors = cycle(&c.art_colors.to_string(), &["6", "8", "10", "12", "16", "24"]).parse().unwrap_or(10);
                    redo_art = true;
                }
                13 => c.uppercase = !c.uppercase,
                14 => c.tooltips = !c.tooltips,
                15 => c.show_profile = !c.show_profile,
                16 => {
                    let packs = crate::skin::pack_names();
                    let names: Vec<&str> = packs.iter().map(|s| s.as_str()).collect();
                    c.color_pack = cycle(&c.color_pack, &names);
                    // a pack is a fresh start for the style lab
                    c.color_hue = 0;
                    c.color_sat = 100;
                    c.grey_tint = "none".into();
                    c.grey_light = 100;
                    c.color_over = crate::skin::pack_colors(&c.color_pack);
                    reskin = true;
                }
                70 => c.dev_mode = !c.dev_mode,
                71 => {
                    c.color_sat = (c.color_sat + if back { -10 } else { 10 }).clamp(0, 200);
                    reskin = true;
                }
                72 => {
                    let t: Vec<&str> = crate::skin::TINTS.iter().map(|t| t.0).collect();
                    c.grey_tint = cycle(&c.grey_tint, &t);
                    reskin = true;
                }
                73 => {
                    c.grey_light = (c.grey_light + if back { -10 } else { 10 }).clamp(50, 200);
                    reskin = true;
                }
                74 => {
                    c.bevel = cycle(&c.bevel, &["normal", "soft", "strong", "flat"]);
                    reskin = true;
                }
                75 => {
                    c.text_shadow = !c.text_shadow;
                    reskin = true;
                }
                76 => {
                    c.text_style = cycle(&c.text_style, &["normal", "bright", "soft"]);
                    reskin = true;
                }
                77 => {
                    c.color_hue = 0;
                    c.color_sat = 100;
                    c.grey_tint = "none".into();
                    c.grey_light = 100;
                    c.bevel = "normal".into();
                    c.text_shadow = false;
                    c.text_style = "normal".into();
                    c.color_over.clear();
                    reskin = true;
                }
                80..=95 => {
                    if let Some((k, _)) = crate::skin::EDITABLE.get((id - 80) as usize) {
                        let mut opts: Vec<&str> = vec!["from pack"];
                        opts.extend(crate::skin::SWATCHES.iter());
                        let cur = c.color_over.get(*k).cloned().unwrap_or_else(|| "from pack".into());
                        let next = cycle(&cur, &opts);
                        if next == "from pack" {
                            c.color_over.remove(*k);
                        } else {
                            c.color_over.insert(k.to_string(), next);
                        }
                        reskin = true;
                    }
                }
                17 => {
                    c.color_hue = if back { c.color_hue - 15 } else { c.color_hue + 15 };
                    if c.color_hue > 180 {
                        c.color_hue -= 360;
                    }
                    if c.color_hue <= -180 {
                        c.color_hue += 360;
                    }
                    reskin = true;
                }
                18 => {
                    saved_pack = crate::skin::save_pack(&c);
                    if let Some(n) = &saved_pack {
                        c.color_pack = n.clone();
                        c.color_hue = 0;
                        c.color_sat = 100;
                        c.grey_tint = "none".into();
                        c.grey_light = 100;
                        c.bevel = "normal".into();
                    }
                }
                19 => c.collapse_anim = cycle(&c.collapse_anim, &["tv", "dissolve", "shutter", "none"]),
                41 => {
                    let n = self.tb.as_ref().map(|t| t.monitors.load(std::sync::atomic::Ordering::SeqCst)).unwrap_or(1).max(1);
                    let mut opts: Vec<String> = vec!["main".into(), "follow".into()];
                    opts.extend((2..=n).map(|i| i.to_string()));
                    let o: Vec<&str> = opts.iter().map(|x| x.as_str()).collect();
                    c.tb_monitor = cycle(&c.tb_monitor, &o);
                }
                49 => c.tb_shuffle_btn = !c.tb_shuffle_btn,
                42 => c.tb_left_offset = cycle(&c.tb_left_offset.to_string(), &["0", "8", "16", "32", "64", "96", "128", "160", "200", "240", "320"]).parse().unwrap_or(8),
                43 => c.tb_lines = cycle(&c.tb_lines, &["auto", "one", "two"]),
                44 => c.tb_paused_dim = !c.tb_paused_dim,
                45 => c.tb_volume_bar = !c.tb_volume_bar,
                46 => c.tb_show_when = cycle(&c.tb_show_when, &["always", "hidden"]),
                47 => c.discord = !c.discord,
                78 => c.discord_join = !c.discord_join,
                63 => c.auto_update = !c.auto_update,
                96 => crate::update::check_now(self.tx.clone(), self.ctx.clone()),
                64 => c.hide_tray_with_player = !c.hide_tray_with_player,
                20 => {
                    c.smart_ratio = cycle(&c.smart_ratio.to_string(), &["0", "25", "50", "75", "100"]).parse().unwrap_or(50);
                    if let Some(sm) = &self.smart {
                        let _ = sm.send(crate::smart::Cmd::Ratio);
                    }
                }
                21 => c.smart_avoid_artist = !c.smart_avoid_artist,
                30 => c.tb_on = !c.tb_on,
                31 => c.tb_pos = cycle(&c.tb_pos, &["auto", "left", "custom"]),
                32 => c.tb_text_w = cycle(&c.tb_text_w.to_string(), &["64", "96", "128", "160", "192"]).parse().unwrap_or(96),
                33 => c.tb_idle = cycle(&c.tb_idle, &["dim", "icon", "hide"]),
                34 => c.tb_fullscreen = cycle(&c.tb_fullscreen, &["other", "info", "hide", "normal"]),
                35 => c.tb_click_art = cycle(&c.tb_click_art, &actions),
                36 => c.tb_click_text = cycle(&c.tb_click_text, &actions),
                37 => c.tb_dbl_text = cycle(&c.tb_dbl_text, &actions),
                38 => c.tb_middle = cycle(&c.tb_middle, &actions),
                39 => c.tb_right = cycle(&c.tb_right, &actions),
                40 => c.tb_wheel = cycle(&c.tb_wheel, &["volume", "seek", "track", "none"]),
                _ => {}
            }
            c.save();
        }
        if reskin {
            self.reload_skin();
            self.osd = None; // live preview: no "skin reloaded" message
        }
        if let Some(n) = saved_pack {
            self.osd(&format!("SAVED PACK {}", n.to_uppercase()));
        }
        match id {
            62 => {
                if !crate::install::is_installed() {
                    self.settings = false;
                    self.install_page = true;
                }
            }
            22 | 50 => {
                self.settings = false;
                self.setup = true;
                self.setup_focus = Some(if id == 22 { 2 } else { 0 });
            }
            51 => {
                if self.cfg.lock().unwrap().client_id.is_empty() {
                    self.settings = false;
                    self.setup = true;
                    self.setup_focus = Some(1);
                } else {
                    let _ = self.web.send(Req::SignIn);
                    self.osd("OPENING BROWSER...");
                }
            }
            _ => {}
        }
        if redo_art {
            self.art_key.clear();
            self.request_art();
        }
        if let Some(on) = startup {
            set_start_with_windows(on);
        }
    }

    fn draw_settings(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, lr: [f32; 4], sr: [f32; 4]) {
        // memory readout, refreshed every 2 s while Settings is open
        if self.mem_at.map(|t| t.elapsed() > Duration::from_secs(2)).unwrap_or(true) {
            self.mem_at = Some(Instant::now());
            let me = crate::desktop::mem_mb(std::process::id()).map(|m| format!("SpoX {:.0} MB", m)).unwrap_or_default();
            let so = soloist::pid().and_then(crate::desktop::mem_mb).map(|m| format!("  \u{b7}  Soloist {:.0} MB", m)).unwrap_or_default();
            self.mem_text = format!("{}{}", me, so);
        }
        self.ctx.request_repaint_after(Duration::from_secs(2));
        let rows = self.settings_rows();
        let lh = 10.0;
        let vis = (lr[3] / lh).floor() as usize;
        let max = rows.len().saturating_sub(vis) as f32;
        if inp.wheel != 0.0 && inp.over(lr[0], lr[1], lr[2] + 12.0, lr[3]) {
            self.settings_scroll = (self.settings_scroll - inp.wheel / (lh * s) * 1.5).clamp(0.0, max);
        }
        let first = self.settings_scroll.floor() as usize;
        let (thw, thh) = self.skin.size("scroll_thumb");
        if max > 0.0 {
            self.spr(p, o, s, "scroll_thumb", sr[0] + ((sr[2] - thw) / 2.0).floor(), sr[1] + ((sr[3] - thh) * first as f32 / max).round());
        }
        let cp = self.clip(p, o, s, lr);
        let (head, txt, val, hov) = (self.skin.color("header"), self.skin.color("list"), self.skin.color("list_current"), self.skin.color("list_selected_bg"));
        let mut clicked = None;
        let mut back = false;
        for (i, (label, value, id)) in rows.iter().enumerate().skip(first).take(vis) {
            let y = lr[1] + (i - first) as f32 * lh;
            if *id == 0 {
                self.text(&cp, o, s, lr[0] + 1.0, y + 1.0, label, head);
                continue;
            }
            if inp.over(lr[0], y, lr[2], lh) {
                self.fill(&cp, o, s, lr[0], y, lr[2], lh, hov);
                self.hover_now = Some("setting".into());
                if inp.released && self.pressed.is_none() {
                    clicked = Some(*id);
                } else if inp.right {
                    clicked = Some(*id);
                    back = true;
                    self.right_used = true;
                }
            }
            let vw0 = if value.starts_with('#') { 20.0 } else { self.tw(value) };
            let label = self.fit(label, lr[2] - vw0 - 16.0);
            self.text(&cp, o, s, lr[0] + 9.0, y + 1.0, &label, txt);
            // colours: a swatch instead of a hex code
            if let Some(c) = value.starts_with('#').then(|| crate::skin::parse_color(value)).flatten() {
                self.fill(&cp, o, s, lr[0] + lr[2] - 18.0, y + 1.0, 16.0, 7.0, Color32::from_rgb(8, 8, 9));
                self.fill(&cp, o, s, lr[0] + lr[2] - 17.0, y + 2.0, 14.0, 5.0, c);
                continue;
            }
            let vw = self.tw(value);
            self.text(&cp, o, s, lr[0] + lr[2] - vw - 2.0, y + 1.0, value, val);
        }
        if let Some(id) = clicked {
            self.apply_setting(id, back);
        }
    }

    /// Pixel "click a word" button inside a page. Returns true when clicked.
    fn page_button(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, x: f32, y: f32, label: &str, tip: &str, lh: f32) -> (bool, f32) {
        let over = inp.over(x - 1.0, y - 1.0, self.tw(label) + 2.0, lh);
        let col = if over { self.skin.color("lyric_current") } else { self.skin.color("list_current") };
        let w = self.text(p, o, s, x, y, label, col);
        if over {
            self.hover_now = Some(format!("row:{}", tip));
        }
        (over && inp.released, w)
    }

    fn draw_install(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, lr: [f32; 4], lh: f32) {
        let txt = self.skin.color("list");
        let dim = self.skin.color("list_dim");
        let x = lr[0] + 1.0;
        let mut y = lr[1] + 1.0;
        let dir = crate::install::install_dir();
        for (t, c) in [
            ("Install SpoX for your user? No admin needed.", txt),
            ("", txt),
            ("- copies SpoX to your Programs folder", dim),
            ("- Start menu + desktop shortcut", dim),
            ("- shows up in Settings > Apps (uninstall there)", dim),
            ("- your settings and sign-in stay as they are", dim),
        ] {
            self.text(p, o, s, x, y, t, c);
            y += lh;
        }
        let shown = self.fit(&dir.display().to_string(), lr[2] - 4.0);
        self.text(p, o, s, x, y, &shown, dim);
        y += lh + 4.0;
        let (go, w1) = self.page_button(p, o, s, inp, x, y, "[ Install ]", "Install, then restart from the new place", lh);
        let (later, w2) = self.page_button(p, o, s, inp, x + w1 + 8.0, y, "[ Just run it ]", "Run from here without installing (asks only once)", lh);
        let (never, _) = self.page_button(p, o, s, inp, x + w1 + w2 + 16.0, y, "[ Portable ]", "Keep everything next to SpoX.exe (USB stick mode)", lh);
        if go {
            let sww = self.cfg.lock().unwrap().start_with_windows;
            match crate::install::install(true, sww) {
                Ok(exe) => {
                    {
                        let mut c = self.cfg.lock().unwrap();
                        c.installed_asked = true;
                        c.save();
                    }
                    // hand over to the installed copy: stop our Soloist first so the new one starts clean
                    soloist::stop_soloist();
                    let _ = std::process::Command::new(&exe).arg("--after-install").current_dir(exe.parent().unwrap_or(std::path::Path::new("."))).spawn();
                    self.quitting_for_install = true;
                    self.ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                }
                Err(e) => self.osd(format!("INSTALL FAILED: {}", e.to_uppercase())),
            }
        }
        if later || never {
            if never {
                let _ = std::fs::write(crate::config::exe_dir().join("portable.txt"), "SpoX portable mode: settings live in ./data\n");
            }
            let mut c = self.cfg.lock().unwrap();
            c.installed_asked = true;
            c.save();
            drop(c);
            self.install_page = false;
            if never {
                self.osd("PORTABLE: RESTART SPOX");
            }
        }
    }

    fn draw_setup(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp, lr: [f32; 4], lh: f32) {
        let dim = self.skin.color("list_dim");
        let txt = self.skin.color("list");
        let link = self.skin.color("list_current");
        let ib = self.skin.color("input_bg");
        let it = self.skin.color("input_text");
        let x = lr[0] + 1.0;
        let mut y = lr[1] + 1.0;
        // step text, link label, url, field index
        let steps: [(&str, &str, &str, Option<usize>); 3] = [
            ("1. Soloist API key (needs Premium)", "[get key]", "https://developer.spotify.com/documentation/soloist", Some(0)),
            ("2. Client ID (new app, tick Web API)", "[dashboard]", "https://developer.spotify.com/dashboard", Some(1)),
            ("3. Last.fm key (optional)", "[get key]", "https://www.last.fm/api/account/create", Some(2)),
        ];
        for (n, (label, ltxt, url, field)) in steps.iter().enumerate() {
            let lw = self.tw(ltxt);
            // never under the link: cut the label to the space that's left
            let label = self.fit(label, lr[2] - lw - 8.0);
            self.text(p, o, s, x, y, &label, txt);
            let lx = lr[0] + lr[2] - lw - 1.0;
            let over_link = inp.over(lx, y - 1.0, lw, lh);
            self.text(p, o, s, lx, y, ltxt, if over_link { self.skin.color("lyric_current") } else { link });
            if over_link {
                self.hover_now = Some(format!("row:Opens {}", url));
                if inp.released {
                    net::open_url(url);
                }
            }
            y += lh;
            if let Some(i) = *field {
                let focused = self.setup_focus == Some(i);
                self.fill(p, o, s, lr[0], y - 1.0, lr[2], lh, ib);
                if inp.released && inp.over(lr[0], y - 1.0, lr[2], lh) {
                    self.setup_focus = Some(i);
                }
                let v = &self.setup_fields[i];
                let shown = if focused || v.is_empty() {
                    if v.is_empty() && !focused { "click here, then paste (Ctrl+V)".to_string() } else { v.clone() }
                } else {
                    let n = v.chars().count();
                    format!("{}{}", "*".repeat(n.saturating_sub(4).min(24)), v.chars().skip(n.saturating_sub(4)).collect::<String>())
                };
                let col = if v.is_empty() && !focused { dim } else { it };
                let caret = focused && (self.t0.elapsed().as_millis() / 500) % 2 == 0;
                let mut shown_fit = shown.clone();
                while self.tw(&shown_fit) > lr[2] - 8.0 && !shown_fit.is_empty() {
                    shown_fit.remove(0);
                }
                let w = self.text(p, o, s, x + 1.0, y, &shown_fit, col);
                if caret {
                    self.text(p, o, s, x + 2.0 + if v.is_empty() { 0.0 } else { w }, y, "_", it);
                }
                y += lh + 2.0;
            }
            if n == 1 {
                // redirect URI right under the Client ID step
                let uw = self.text(p, o, s, x, y, &format!("Redirect URI: {}", web::REDIRECT_URI), dim);
                let cw = self.text(p, o, s, x + uw + 6.0, y, "[copy]", link);
                if inp.over(x, y - 1.0, uw + 6.0 + cw, lh) {
                    self.hover_now = Some("row:Paste this as the Redirect URI in your Spotify app".into());
                    if inp.released {
                        self.ctx.copy_text(web::REDIRECT_URI.to_string());
                        self.osd("COPIED REDIRECT URI");
                    }
                }
                y += lh + 2.0;
            }
        }
        let b1 = "[ Save ]";
        let w1 = self.text(p, o, s, x, y, b1, link);
        if inp.over(x, y - 1.0, w1, lh) {
            self.hover_now = Some("row:Save and connect (Enter)".into());
            if inp.released {
                self.save_setup();
            }
        }
        let tip = self.fit("4. Then once: Spotify > Devices > SpoX", lr[2] - w1 - 10.0);
        self.text(p, o, s, x + w1 + 8.0, y, &tip, dim);
    }

    /// Profile picture with a 1 px outline. `small` = the 8 px version. No picture: a pixel person.
    fn draw_pfp(&self, p: &egui::Painter, o: Pos2, s: f32, x: f32, y: f32, size: f32, small: bool) {
        let line = self.skin.json["colors"]["pfp_outline"].as_str().and_then(crate::skin::parse_color).unwrap_or(self.skin.color("list_dim"));
        self.fill(p, o, s, x - 1.0, y - 1.0, size + 2.0, size + 2.0, line);
        self.fill(p, o, s, x, y, size, size, self.skin.color("input_bg"));
        let tex = if small { self.pfp_small.as_ref().or(self.pfp.as_ref()) } else { self.pfp.as_ref() };
        match tex {
            Some(t) => {
                p.image(
                    t.id(),
                    Rect::from_min_size(Pos2::new(o.x + x * s, o.y + y * s), Vec2::new(size * s, size * s)),
                    Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)),
                    Color32::WHITE,
                );
            }
            None => {
                // head + shoulders
                let c = self.skin.color("list_dim");
                let k = size / 8.0;
                self.fill(p, o, s, x + 3.0 * k, y + 1.0 * k, 2.0 * k, 3.0 * k, c);
                self.fill(p, o, s, x + 2.0 * k, y + 2.0 * k, 4.0 * k, 1.0 * k, c);
                self.fill(p, o, s, x + 1.0 * k, y + 5.0 * k, 6.0 * k, 3.0 * k, c);
            }
        }
    }

    fn draw_menu(&mut self, p: &egui::Painter, o: Pos2, s: f32, inp: &Inp) {
        let (scale, on_top, _vis, has_client, tray_on, on_close) = {
            let c = self.cfg.lock().unwrap();
            (c.scale, c.always_on_top, c.vis_on, !c.client_id.is_empty(), c.tray, c.on_close.clone())
        };
        let scopes_ok = web::NEW_SCOPES.iter().all(|sc| self.cfg.lock().unwrap().scopes.contains(sc));
        let tb_on = self.cfg.lock().unwrap().tb_on;
        let _ = (tray_on, on_close);
        let mut items: Vec<(String, u8)> = vec![
            (
                if !self.signed_in { format!("SpoX v{}", env!("CARGO_PKG_VERSION")) } else if self.profile_name.is_empty() { "Signed in".into() } else { self.profile_name.clone() },
                255,
            ),
            (format!("Size: {}x", scale), 0),
            (format!("Taskbar player: {}", if tb_on { "on" } else { "off" }), 9),
        ];
        // only shown when something needs doing
        if !self.signed_in || !scopes_ok {
            items.push(((if !has_client { "Set up Spotify keys..." } else if self.signed_in { "Sign in again (new features)" } else { "Sign in to Spotify" }).to_string(), 4));
        }
        if let Some(v) = &self.update_ready {
            items.push((format!("Restart to update ({})", v), 20));
        }
        items.push(("Settings...".into(), 5));
        items.push(("Open skin folder".into(), 7));
        items.push(("Quit + stop Soloist".into(), 8));
        let lh = 10.0;
        let w = items.iter().map(|(t, id)| self.tw(t) + if *id == 255 { 40.0 } else { 0.0 }).fold(0.0, f32::max) + 12.0;
        let (mx, my) = (4.0, 13.0);
        let h = items.len() as f32 * lh + 4.0;
        self.fill(p, o, s, mx - 1.0, my - 1.0, w + 2.0, h + 2.0, Color32::from_rgb(10, 10, 11));
        self.fill(p, o, s, mx, my, w, h, self.skin.color("menu_bg"));
        let mut clicked = None;
        for (i, (t, id)) in items.iter().enumerate() {
            let y = my + 2.0 + i as f32 * lh;
            if *id == 255 {
                // header: picture + name (or the version when not signed in)
                if self.signed_in {
                    self.draw_pfp(p, o, s, mx + 4.0, y, 8.0, true);
                    self.text(p, o, s, mx + 16.0, y + 1.0, t, self.skin.color("menu_text"));
                    let v = format!("v{}", env!("CARGO_PKG_VERSION"));
                    let vw = self.tw(&v);
                    self.text(p, o, s, mx + w - vw - 4.0, y + 1.0, &v, self.skin.color("list_dim"));
                } else {
                    self.text(p, o, s, mx + 6.0, y + 1.0, t, self.skin.color("list_dim"));
                }
                continue;
            }
            if inp.over(mx, y, w, lh) {
                self.fill(p, o, s, mx, y, w, lh, self.skin.color("menu_hover"));
                if inp.released {
                    clicked = Some(*id);
                }
            }
            self.text(p, o, s, mx + 6.0, y + 1.0, t, self.skin.color("menu_text"));
        }
        if let Some(id) = clicked {
            match id {
                20 => {
                    // hand over to the new version; this one closes without stopping the music
                    crate::update::restart();
                    self.quitting_for_install = true;
                    self.ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                }
                0 => {
                    let mut c = self.cfg.lock().unwrap();
                    c.scale = if c.scale >= 4 { 1 } else { c.scale + 1 };
                    return; // keep menu open while cycling sizes
                }
                1 => {
                    let v = !on_top;
                    self.cfg.lock().unwrap().always_on_top = v;
                    self.ctx.send_viewport_cmd(egui::ViewportCommand::WindowLevel(if v {
                        egui::WindowLevel::AlwaysOnTop
                    } else {
                        egui::WindowLevel::Normal
                    }));
                }
                2 => {
                    let mut c = self.cfg.lock().unwrap();
                    c.on_close = match c.on_close.as_str() {
                        "pause" => "keep".into(),
                        "keep" => "quit".into(),
                        _ => "pause".into(),
                    };
                    return; // keep the menu open while cycling
                }
                3 => {
                    let mut c = self.cfg.lock().unwrap();
                    c.vis_on = !c.vis_on;
                }
                9 => {
                    let mut c = self.cfg.lock().unwrap();
                    c.tb_on = !c.tb_on;
                    return;
                }
                4 => {
                    if has_client {
                        let _ = self.web.send(Req::SignIn);
                            self.osd("OPENING BROWSER...");
                    } else {
                        self.setup = true;
                        self.setup_focus = Some(1);
                        self.cfg.lock().unwrap().panel_open = true;
                    }
                }
                5 => {
                    self.settings = true;
                    self.setup = false;
                    self.settings_scroll = 0.0;
                    self.cfg.lock().unwrap().panel_open = true;
                }
                6 => {
                    let mut c = self.cfg.lock().unwrap();
                    c.tray = !c.tray;
                }
                7 => {
                    let d = crate::skin::export_builtin();
                    net::open_folder(&d);
                }
                8 => {
                    soloist::stop_soloist();
                    self.ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                }
                _ => {}
            }
            self.menu = false;
        } else if inp.released && !inp.over(mx, my, w, h) && self.pressed != Some("btn_menu") {
            self.menu = false;
        }
    }

    fn keyboard(&mut self, ctx: &egui::Context) {
        let events = ctx.input(|i| i.events.clone());
        if self.setup && self.setup_focus.is_some() {
            let f = self.setup_focus.unwrap();
            for e in events {
                match e {
                    egui::Event::Text(t) => self.setup_fields[f].push_str(&t.replace(['\r', '\n'], "")),
                    egui::Event::Paste(t) => self.setup_fields[f].push_str(t.trim()),
                    egui::Event::Key { key, pressed: true, modifiers, .. } => match key {
                        egui::Key::Backspace => {
                            if modifiers.ctrl {
                                self.setup_fields[f].clear();
                            } else {
                                self.setup_fields[f].pop();
                            }
                        }
                        egui::Key::Tab => self.setup_focus = Some((f + 1) % 3),
                        egui::Key::Enter => self.save_setup(),
                        egui::Key::Escape => {
                            self.setup = false;
                            self.setup_focus = None;
                        }
                        _ => {}
                    },
                    _ => {}
                }
            }
            return;
        }
        // typing to find in the open playlist
        if let Some(mut f) = self.pl_find.clone() {
            let mut close = false;
            let mut play_first = false;
            for e in &events {
                match e {
                    egui::Event::Text(t) => f.push_str(&t.replace(['\r', '\n'], "")),
                    egui::Event::Paste(t) => f.push_str(t.trim()),
                    egui::Event::Key { key, pressed: true, modifiers, .. } => match key {
                        egui::Key::Backspace if modifiers.ctrl => f.clear(),
                        egui::Key::Backspace => {
                            if f.pop().is_none() {
                                close = true;
                            }
                        }
                        egui::Key::Escape => close = true,
                        egui::Key::Enter => play_first = true,
                        _ => {}
                    },
                    _ => {}
                }
            }
            self.pl_find = if close { None } else { Some(f) };
            self.scroll[SCROLL_PL] = 0.0;
            self.apply_view();
            if play_first && !self.tracks.is_empty() {
                let i = self.selected.unwrap_or(0).min(self.tracks.len() - 1);
                self.play_track(i);
            }
            return;
        }
        for e in events {
            if let egui::Event::Key { key, pressed: true, modifiers, .. } = e {
                match key {
                    egui::Key::F if modifiers.ctrl => self.start_find(),
                    egui::Key::F3 | egui::Key::Slash => self.start_find(),
                    egui::Key::O if self.sel.is_some() && !self.picker => self.cycle_sort(),
                    egui::Key::Delete if self.queue_visible() => {
                        let picks: Vec<String> = if !self.q_picked.is_empty() {
                            self.q_picked.iter().cloned().collect()
                        } else {
                            self.q_selected.and_then(|i| self.q_list.get(i)).map(|t| vec![t.uri.clone()]).unwrap_or_default()
                        };
                        if !picks.is_empty() {
                            self.run_ctx(CtxAct::RemoveQ(picks));
                        }
                    }
                    egui::Key::Space => self.toggle_play(),
                    egui::Key::ArrowLeft if modifiers.ctrl => self.send(command("skip_prev")),
                    egui::Key::ArrowRight if modifiers.ctrl => self.send(command("skip_next")),
                    egui::Key::ArrowLeft => self.seek_by(-5000.0),
                    egui::Key::ArrowRight => self.seek_by(5000.0),
                    egui::Key::ArrowUp => self.set_volume(self.play.volume as i32 + 5),
                    egui::Key::ArrowDown => self.set_volume(self.play.volume as i32 - 5),
                    egui::Key::F5 => self.reload_skin(),
                    egui::Key::P => self.set_tab(Tab::Playlist),
                    egui::Key::Q => self.set_tab(Tab::Queue),
                    egui::Key::L if modifiers.ctrl => self.toggle_like(),
                    egui::Key::L => self.set_tab(Tab::Lyrics),
                    egui::Key::S => self.cycle_shuffle(),
                    egui::Key::C if modifiers.ctrl => {
                        if let Some(l) = self.track_link() {
                            self.ctx.copy_text(l);
                            self.osd("SONG LINK COPIED");
                        }
                    }
                    egui::Key::M => self.toggle_mute(),
                    egui::Key::H => {
                        if self._tray.is_some() {
                            self.start_collapse(true);
                        }
                    }
                    egui::Key::R => self.cycle_repeat(),
                    egui::Key::T => {
                        let v = !self.cfg.lock().unwrap().always_on_top;
                        self.cfg.lock().unwrap().always_on_top = v;
                        self.ctx.send_viewport_cmd(egui::ViewportCommand::WindowLevel(if v {
                            egui::WindowLevel::AlwaysOnTop
                        } else {
                            egui::WindowLevel::Normal
                        }));
                    }
                    egui::Key::Num1 | egui::Key::Num2 | egui::Key::Num3 | egui::Key::Num4 => {
                        let n = match key {
                            egui::Key::Num1 => 1,
                            egui::Key::Num2 => 2,
                            egui::Key::Num3 => 3,
                            _ => 4,
                        };
                        self.cfg.lock().unwrap().scale = n;
                    }
                    egui::Key::Escape => {
                        if self.menu || self.add_menu || self.picker {
                            self.menu = false;
                            self.add_menu = false;
                            self.picker = false;
                        } else if !self.q_picked.is_empty() {
                            self.q_picked.clear();
                        } else if self._tray.is_some() && self.cfg.lock().unwrap().tray {
                            self.start_collapse(true);
                        }
                    }
                    _ => {}
                }
            }
        }
    }
}

impl eframe::App for App {
    fn clear_color(&self, _v: &egui::Visuals) -> [f32; 4] {
        [0.0, 0.0, 0.0, 0.0]
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Up next open: refresh it every 10 s, so it matches your other devices
        if self.connected && self.queue_visible() && self.queue_asked.elapsed() > Duration::from_secs(10) {
            self.queue_asked = Instant::now();
            self.send(command_with("get_queue", "limit", json!(0)));
        }
        // 1 egui point = 1 screen pixel, so integer scales stay crisp on 125%/150% Windows scaling.
        let native = ctx.native_pixels_per_point().unwrap_or(1.0);
        let want_zoom = 1.0 / native;
        if (ctx.zoom_factor() - want_zoom).abs() > 0.001 {
            ctx.set_zoom_factor(want_zoom);
            self.sized = None;
        }

        while let Ok(e) = self.rx.try_recv() {
            self.on_event(e);
        }
        // no tray icon while the taskbar player is there (it does the same job)
        if let Some(tr) = &self._tray {
            let c = self.cfg.lock().unwrap();
            tr.set_visible(!(c.tb_on && c.tb_idle != "hide" && c.hide_tray_with_player));
        }
        if let Some(t) = &self.tb {
            if t.smart_on.swap(self.smart_on, std::sync::atomic::Ordering::SeqCst) != self.smart_on {
                self.ctx.request_repaint_of(crate::tb::id());
            }
        }
        self.note_history();
        // likes change on other devices too: ask again every 30 s while something plays
        if self.signed_in && self.play.status == "playing" && self.liked_checked.elapsed() > Duration::from_secs(30) {
            self.liked_checked = Instant::now();
            if let Some(t) = self.play.track.as_ref().filter(|t| t.uri.starts_with("spotify:track:")) {
                let _ = self.web.send(Req::CheckLiked(t.uri.clone()));
            }
        }
        // "hide" from the tray icon / taskbar player: with the close animation
        let pending: Vec<&str> = std::mem::take(&mut *crate::desktop::PENDING.lock().unwrap());
        for a in pending {
            if a == "hide" && !crate::desktop::HIDDEN.load(std::sync::atomic::Ordering::SeqCst) {
                self.start_collapse(true);
            }
        }
        // actions coming from the taskbar player
        let acts: Vec<String> = self.tb.as_ref().map(|t| std::mem::take(&mut *t.app_actions.lock().unwrap())).unwrap_or_default();
        for a in acts {
            match a.as_str() {
                "mute" => {
                    self.toggle_mute();
                    if let Some(t) = &self.tb {
                        crate::tb::show_volume(t);
                        crate::tb::osd(t, if self.muted_from.is_some() { "Muted" } else { "Unmuted" });
                        self.ctx.request_repaint_of(crate::tb::id());
                    }
                }
                "shuffle" => {
                    self.cycle_shuffle();
                    if let Some(t) = &self.tb {
                        let m = if self.smart_on { "SpoX smart shuffle" } else if self.play.shuffle { "Spotify shuffle" } else { "Shuffle off" };
                        crate::tb::osd(t, m);
                        self.ctx.request_repaint_of(crate::tb::id());
                    }
                }
                _ => {}
            }
        }

        let s = self.scale();
        let (mw, mh) = self.skin.main_size();
        let ph = self.panel_height();
        let open = self.panel_open();
        let mut size = (mw * s, (mh + if open { ph } else { 0.0 }) * s);
        // menus never get cut off: grow the window while one is open
        let need = if self.menu {
            13.0 + 11.0 * 10.0 + 8.0
        } else if self.add_menu {
            18.0 + 10.0 * 10.0 + 8.0
        } else if let Some(m) = &self.ctx_menu {
            m.y + m.items.len() as f32 * 10.0 + 8.0
        } else {
            0.0
        };
        size.1 = size.1.max(need * s);
        if self.sized != Some(size) {
            self.sized = Some(size);
            ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(Vec2::new(size.0, size.1)));
        }
        // window position (read from Windows: moves don't reach egui anymore, see desktop.rs)
        #[cfg(windows)]
        {
            self.native_ppp = native;
        }
        #[cfg(not(windows))]
        if let Some(r) = ctx.input(|i| i.viewport().outer_rect) {
            self.win_pos = Some([r.min.x, r.min.y]);
        }
        if self.hide_on_start {
            self.hide_on_start = false;
            if self._tray.is_some() {
                crate::desktop::hide_window();
            }
        }
        // taskbar mini-player (its own small window, drawn from the live state)
        if let Some(sh) = self.tb.clone() {
            if self.cfg.lock().unwrap().tb_on {
                // starts hidden and off-screen; the watcher sizes + places it, then shows it
                // (constant builder values: egui never re-applies them, so it can't fight the watcher)
                let vb = egui::ViewportBuilder::default()
                    .with_title(crate::tb::TITLE)
                    .with_decorations(false)
                    .with_taskbar(false)
                    .with_resizable(false)
                    .with_transparent(true)
                    .with_always_on_top()
                    .with_visible(false)
                    .with_inner_size([64.0, 16.0])
                    .with_position([-32000.0, -32000.0]);
                // no taskbar to sit on outside Windows: show it as a plain little window (for testing)
                #[cfg(not(windows))]
                let vb = {
                    let (bw, bh) = crate::tb::base_size(&self.cfg, false, false);
                    vb.with_visible(true).with_inner_size([bw * 3.0, bh * 3.0]).with_position([20.0, 780.0])
                };
                ctx.show_viewport_deferred(crate::tb::id(), vb, move |ctx, _class| crate::tb::ui(&sh, ctx));
            }
        }

        self.keyboard(ctx);

        let raw = ctx.input(|i| {
            (
                i.pointer.hover_pos(),
                i.pointer.primary_pressed(),
                i.pointer.primary_released(),
                i.pointer.primary_down(),
                i.pointer.button_double_clicked(egui::PointerButton::Primary),
                i.pointer.button_pressed(egui::PointerButton::Secondary),
                i.raw_scroll_delta.y,
                i.pointer.button_pressed(egui::PointerButton::Middle),
                i.modifiers.ctrl,
            )
        });
        let o = Pos2::ZERO;
        let inp = Inp {
            pos: raw.0.map(|p| Pos2::new((p.x - o.x) / s, (p.y - o.y) / s)),
            pressed: raw.1,
            released: raw.2,
            down: raw.3,
            dbl: raw.4,
            right: raw.5,
            wheel: if raw.8 { 0.0 } else { raw.6 },
            middle: raw.7,
            ctrl: raw.8,
        };
        // Ctrl + scroll anywhere = volume
        if raw.8 && raw.6 != 0.0 {
            let v = self.play.volume as i32 + if raw.6 > 0.0 { 5 } else { -5 };
            self.set_volume(v);
        }
        self.seek_hover = None;
        self.right_used = false;
        self.hover_now = None;
        if let Some(t) = self.refresh_at {
            if Instant::now() >= t {
                self.refresh_at = None;
                // the queue changes when shuffle changes: fetch the new one
                self.send(command_with("get_queue", "limit", json!(0)));
            } else {
                ctx.request_repaint_after(t - Instant::now());
            }
        }

        let mut animating = false;
        egui::CentralPanel::default().frame(egui::Frame::NONE).show(ctx, |ui| {
            let p = ui.painter().clone();
            // menu captures clicks first
            let menu_inp = Inp { pos: inp.pos, pressed: inp.pressed, released: inp.released, down: inp.down, dbl: inp.dbl, right: false, middle: false, wheel: inp.wheel, ctrl: inp.ctrl };
            let overlay = self.menu || self.add_menu || self.ctx_menu.is_some();
            let blocked = Inp {
                pos: if overlay { None } else { inp.pos },
                pressed: inp.pressed,
                released: inp.released,
                down: inp.down,
                dbl: inp.dbl,
                right: inp.right && !overlay,
                middle: inp.middle && !overlay,
                ctrl: inp.ctrl,
                wheel: if overlay { 0.0 } else { inp.wheel },
            };
            animating |= self.draw_main(&p, o, s, &blocked);
            if self.panel_open() {
                self.draw_panel(&p, o, s, &blocked, mh);
            }
            if self.ctx_menu.is_some() {
                let menu_inp2 = Inp { right: inp.right, ..menu_inp };
                self.draw_ctx_menu(&p, o, s, &menu_inp2, mw);
            }
            if self.add_menu {
                self.draw_add_menu(&p, o, s, &menu_inp);
            }
            if self.menu {
                self.draw_menu(&p, o, s, &menu_inp);
            }
            let total_h = mh + if self.panel_open() { self.panel_height() } else { 0.0 };
            if self.cfg.lock().unwrap().tooltips {
                self.draw_tooltip(&p, o, s, &inp, mw, total_h.max(size.1 / s));
            } else {
                self.hover_now = None;
            }
            self.draw_collapse(&p);
        });

        if inp.released || !inp.down {
            self.pressed = None;
            self.drag = Drag::None;
        }

        // Only wake up when something on screen can change. Hidden in the tray or minimized:
        // nothing to draw, so no timers at all (Soloist events still wake us when needed).
        let playing = self.play.status == "playing";
        let unseen = crate::desktop::window_unseen();
        let pace = |ms: u64| crate::desktop::PACE_MS.store(ms, std::sync::atomic::Ordering::SeqCst);
        if unseen && self.collapse.is_none() {
            // hidden: nothing to draw, but stay awake for Soloist, SpoX shuffle and the taskbar
            // player (a slow tick, plus every event wakes it at once)
            self.vis.sleep();
            pace(1000);
            return;
        }
        if animating && self.vis_running() {
            pace(33);
            ctx.request_repaint_after(Duration::from_millis(33));
        } else if animating {
            pace(70);
            ctx.request_repaint_after(Duration::from_millis(70));
        } else if playing {
            // the clock and lyrics: 4 times a second is plenty and keeps lyrics on time
            pace(250);
            ctx.request_repaint_after(Duration::from_millis(250));
        } else if self.setup {
            pace(500);
            ctx.request_repaint_after(Duration::from_millis(500));
        } else {
            pace(0);
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        let on_close = if self.quitting_for_install { "keep".to_string() } else { self.cfg.lock().unwrap().on_close.clone() };
        match on_close.as_str() {
            "keep" => {}
            "quit" => soloist::stop_soloist(),
            _ => {
                if self.play.status == "playing" || self.play.status == "buffering" {
                    self.send(command("pause"));
                    // give the Soloist connection a moment to deliver it before SpoX exits
                    std::thread::sleep(Duration::from_millis(250));
                }
            }
        }
        if let Some(r) = crate::desktop::window_rect() {
            let k = self.native_ppp.max(0.5);
            self.win_pos = Some([r[0] as f32 / k, r[1] as f32 / k]);
        }
        let mut c = self.cfg.lock().unwrap();
        if let Some(p) = self.win_pos {
            c.window_pos = Some(p);
        }
        c.save();
    }
}

fn sort_name(k: &str) -> &'static str {
    match k {
        "added" => "Recently added",
        "title" => "Title",
        "artist" => "Artist",
        "album" => "Album",
        "length" => "Length",
        _ => "Playlist order",
    }
}

fn tracks_cache_path(uri: &str) -> std::path::PathBuf {
    let id: String = uri.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
    config::cache_dir().join("tracks").join(format!("{}.json", id))
}
fn save_tracks_cache(uri: &str, t: &[Track]) {
    let v: Vec<serde_json::Value> = t.iter().map(|t| json!([t.uri, t.name, t.artists, t.album, t.duration_ms, t.added, t.order])).collect();
    let p = tracks_cache_path(uri);
    let _ = std::fs::create_dir_all(p.parent().unwrap());
    let _ = std::fs::write(p, serde_json::Value::Array(v).to_string());
}
fn load_tracks_cache(uri: &str) -> Option<Vec<Track>> {
    let v: serde_json::Value = serde_json::from_slice(&std::fs::read(tracks_cache_path(uri)).ok()?).ok()?;
    Some(
        v.as_array()?
            .iter()
            .filter_map(|e| {
                Some(Track {
                    uri: e.get(0)?.as_str()?.into(),
                    name: e.get(1)?.as_str()?.into(),
                    artists: e.get(2)?.as_str()?.into(),
                    album: e.get(3)?.as_str()?.into(),
                    duration_ms: e.get(4)?.as_u64()?,
                    added: e.get(5).and_then(|x| x.as_i64()).unwrap_or(0),
                    order: e.get(6).and_then(|x| x.as_u64()).unwrap_or(0) as u32,
                    ..Default::default()
                })
            })
            .collect(),
    )
}

/// Adds/removes SpoX from Windows' startup apps (HKCU Run key). Starts hidden in the tray.
fn set_start_with_windows(on: bool) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let exe = std::env::current_exe().map(|p| p.display().to_string()).unwrap_or_default();
        let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
        let mut cmd = std::process::Command::new("reg");
        if on {
            cmd.args(["add", key, "/v", "SpoX", "/t", "REG_SZ", "/d", &format!("\"{}\" --tray", exe), "/f"]);
        } else {
            cmd.args(["delete", key, "/v", "SpoX", "/f"]);
        }
        let _ = cmd.creation_flags(0x0800_0000).status();
    }
    #[cfg(not(windows))]
    {
        let _ = on;
    }
}

fn history_path() -> std::path::PathBuf {
    config::cache_dir().join("history.json")
}
fn load_history() -> Vec<(Track, i64)> {
    let Ok(b) = std::fs::read(history_path()) else { return Vec::new() };
    let Ok(serde_json::Value::Array(a)) = serde_json::from_slice::<serde_json::Value>(&b) else { return Vec::new() };
    a.iter()
        .filter_map(|e| {
            Some((
                Track {
                    uri: e.get(0)?.as_str()?.into(),
                    name: e.get(1)?.as_str()?.into(),
                    artists: e.get(2)?.as_str()?.into(),
                    album: e.get(3)?.as_str()?.into(),
                    duration_ms: e.get(4)?.as_u64()?,
                    cover_small: e.get(6).and_then(|x| x.as_str()).map(|s| s.to_string()),
                    cover_large: None,
                    provider: String::new(),
                    ..Default::default()
                },
                e.get(5)?.as_i64()?,
            ))
        })
        .collect()
}
fn save_history(h: &[(Track, i64)]) {
    let v: Vec<serde_json::Value> = h.iter().map(|(t, at)| json!([t.uri, t.name, t.artists, t.album, t.duration_ms, at, t.cover_small])).collect();
    let _ = std::fs::create_dir_all(config::cache_dir());
    let _ = std::fs::write(history_path(), serde_json::Value::Array(v).to_string());
}

fn lyric_offsets_path() -> std::path::PathBuf {
    config::cache_dir().join("lyric_offsets.json")
}

/// Your Ctrl+scroll nudge for a song's lyrics, in ms (0 if none).
fn load_lyric_offset(uri: &str) -> i64 {
    std::fs::read(lyric_offsets_path())
        .ok()
        .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
        .and_then(|v| v[uri].as_i64())
        .unwrap_or(0)
}

fn save_lyric_offset(uri: &str, ms: i64) {
    let p = lyric_offsets_path();
    let mut v = std::fs::read(&p).ok().and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok()).unwrap_or_else(|| json!({}));
    if ms == 0 {
        if let Some(m) = v.as_object_mut() {
            m.remove(uri);
        }
    } else {
        v[uri] = json!(ms);
    }
    let _ = std::fs::create_dir_all(config::cache_dir());
    let _ = std::fs::write(p, serde_json::to_vec(&v).unwrap_or_default());
}

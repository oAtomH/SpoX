//! Taskbar mini-player: a small always-on-top window that sits on the Windows taskbar,
//! next to the tray icons. It draws itself from the shared live state, so it keeps working
//! while the main window is hidden in the tray.

use crate::config::{now_ms, Shared};
use crate::skin::Skin;
use crate::soloist::{command, command_with};
use crate::state::Play;
use eframe::egui::{self, Color32, Pos2, Rect, Vec2};
use serde_json::json;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const TITLE: &str = "SpoX taskbar player";

pub fn id() -> egui::ViewportId {
    egui::ViewportId::from_hash_of("spox_taskbar_player")
}

/// Everything the taskbar window needs, shared with the rest of the app.
pub struct Shared_ {
    pub skin: Mutex<Arc<Skin>>,
    pub live: Arc<Mutex<Play>>,
    pub solo: Mutex<Sender<String>>,
    pub web: Mutex<Sender<crate::web::Req>>,
    pub cfg: Shared,
    pub liked: Mutex<(String, bool)>,
    art: Mutex<ArtState>,
    ui: Mutex<UiState>,
    pub idle_icon: AtomicBool,
    /// A fullscreen app is on our monitor and the "info only" mode is active: no buttons, click-through.
    pub fs_info: AtomicBool,
    /// Actions the main window has to do (shuffle cycling etc.).
    pub app_actions: Mutex<Vec<String>>,
    /// SpoX smart shuffle on (set by the main window), for the shuffle button.
    pub smart_on: AtomicBool,
    /// Number of taskbars (= monitors with a taskbar), counted by the watcher.
    pub monitors: std::sync::atomic::AtomicUsize,
    paused_since: Mutex<Option<Instant>>,
    /// When the watcher last moved / resized / showed the window: clicks right after are ignored
    /// (the buttons may have moved under the mouse).
    moved_at: Mutex<Option<Instant>>,
}

#[derive(Default)]
struct ArtState {
    url: String,
    pending: Option<egui::ColorImage>,
    tex: Option<egui::TextureHandle>,
}

struct UiState {
    pressed: Option<&'static str>,
    pending_text_click: Option<Instant>,
    osd: Option<(String, Instant)>,
    vol_shown: Option<Instant>,
    t0: Instant,
}

pub type TbShared = Arc<Shared_>;

pub fn new_shared(skin: Arc<Skin>, live: Arc<Mutex<Play>>, solo: Sender<String>, web: Sender<crate::web::Req>, cfg: Shared) -> TbShared {
    Arc::new(Shared_ {
        skin: Mutex::new(skin),
        live,
        solo: Mutex::new(solo),
        web: Mutex::new(web),
        cfg,
        liked: Mutex::new((String::new(), false)),
        art: Mutex::new(ArtState::default()),
        ui: Mutex::new(UiState { pressed: None, pending_text_click: None, osd: None, vol_shown: None, t0: Instant::now() }),
        idle_icon: AtomicBool::new(false),
        fs_info: AtomicBool::new(false),
        app_actions: Mutex::new(Vec::new()),
        smart_on: AtomicBool::new(false),
        monitors: std::sync::atomic::AtomicUsize::new(1),
        paused_since: Mutex::new(None),
        moved_at: Mutex::new(None),
    })
}

/// Size of the player at 1x (16 px tall). Width depends on the text width setting.
/// Layout (left to right): art 16, gap 2, text, gap 2, three 16 px buttons.
pub fn base_size(cfg: &Shared, idle_icon: bool, info_only: bool) -> (f32, f32) {
    if idle_icon {
        return (16.0, 16.0);
    }
    let c = cfg.lock().unwrap();
    let tw = c.tb_text_w.clamp(32, 256) as f32;
    let buttons = if info_only { 0.0 } else if c.tb_shuffle_btn { 64.0 } else { 48.0 };
    (16.0 + 2.0 + tw + 2.0 + buttons, 16.0)
}

/// "Nothing plays": no song, stopped, or paused for more than 10 seconds.
pub fn nothing_playing(sh: &TbShared, p: &Play) -> bool {
    if is_idle(p) {
        return true;
    }
    let mut ps = sh.paused_since.lock().unwrap();
    if p.status == "paused" {
        let t = *ps.get_or_insert_with(Instant::now);
        t.elapsed() > Duration::from_secs(10)
    } else {
        *ps = None;
        false
    }
}

/// Is there "nothing playing" right now?
pub fn is_idle(p: &Play) -> bool {
    p.track.is_none() || !p.is_active || !(p.status == "playing" || p.status == "paused" || p.status == "buffering")
}

pub const ACTIONS: [&str; 12] = ["toggle_main", "show_main", "play_pause", "next", "prev", "like", "copy_link", "seek_fwd", "seek_back", "mute", "shuffle", "none"];

fn act(sh: &TbShared, ctx: &egui::Context, action: &str, p: &Play) {
    let solo = sh.solo.lock().unwrap();
    match action {
        "toggle_main" => {
            if crate::desktop::HIDDEN.load(Ordering::SeqCst) {
                crate::desktop::show_window(ctx);
            } else {
                crate::desktop::hide_window();
            }
        }
        "show_main" => crate::desktop::show_window(ctx),
        "play_pause" => {
            let _ = solo.send("__toggle__".into());
        }
        "next" => {
            let _ = solo.send(command("skip_next"));
        }
        "prev" => {
            let _ = solo.send(command("skip_prev"));
        }
        "seek_fwd" | "seek_back" => {
            let d = if action == "seek_fwd" { 10_000.0 } else { -10_000.0 };
            let p2 = (p.position(now_ms()) + d).max(0.0);
            let _ = solo.send(command_with("seek", "position_ms", json!(p2 as u64)));
            drop(solo);
            osd(sh, if d > 0.0 { "+10 s" } else { "-10 s" });
        }
        "mute" | "shuffle" => {
            drop(solo);
            sh.app_actions.lock().unwrap().push(action.to_string());
            crate::desktop::wake(ctx);
        }
        "like" => {
            drop(solo);
            if let Some(t) = &p.track {
                let mut l = sh.liked.lock().unwrap();
                let on = !(l.0 == t.uri && l.1);
                *l = (t.uri.clone(), on);
                let _ = sh.web.lock().unwrap().send(crate::web::Req::SetLiked(t.uri.clone(), on));
                drop(l);
                osd(sh, if on { "Liked" } else { "Unliked" });
            }
        }
        "copy_link" => {
            drop(solo);
            if let Some(t) = &p.track {
                let mut it = t.uri.split(':');
                if let (Some(_), Some(kind), Some(id)) = (it.next(), it.next(), it.next()) {
                    ctx.copy_text(format!("https://open.spotify.com/{}/{}", kind, id));
                    osd(sh, "Link copied");
                }
            }
        }
        _ => {}
    }
}

pub fn osd(sh: &TbShared, m: &str) {
    sh.ui.lock().unwrap().osd = Some((m.to_string(), Instant::now()));
}

/// Flash the volume bar for a moment (scroll / keys).
pub fn show_volume(sh: &TbShared) {
    sh.ui.lock().unwrap().vol_shown = Some(Instant::now());
}

/// Draw + handle input. Called by egui for the taskbar viewport.
pub fn ui(sh: &TbShared, ctx: &egui::Context) {
    let native = ctx.native_pixels_per_point().unwrap_or(1.0);
    if (ctx.zoom_factor() - 1.0 / native).abs() > 0.001 {
        ctx.set_zoom_factor(1.0 / native);
    }
    let skin = sh.skin.lock().unwrap().clone();
    let play = sh.live.lock().unwrap().clone();
    let cfg = sh.cfg.lock().unwrap().clone();
    let idle = nothing_playing(sh, &play);
    let icon_mode = idle && cfg.tb_idle == "icon";
    sh.idle_icon.store(icon_mode, Ordering::SeqCst);
    let info_only = sh.fs_info.load(Ordering::SeqCst);

    // fullscreen "info only": no buttons and clicks do nothing. (No Windows click-through: switching
    // that on and off could leave the window invisible after leaving a fullscreen app.)
    let screen = ctx.screen_rect();
    // integer scale from the window height (the watcher sizes the window to 16 * scale)
    let s = (screen.height() / 16.0).floor().max(1.0);
    let hpx = 16.0 * s;
    // always anchored top-left: no re-centering, so nothing jitters while the window resizes
    let o = Pos2::ZERO;

    // album art (pixelated, same palette trick as the main window)
    if let Some(t) = &play.track {
        let url = t.cover_small.clone().or_else(|| t.cover_large.clone()).unwrap_or_default();
        let mut a = sh.art.lock().unwrap();
        if url != a.url {
            a.url = url.clone();
            a.tex = None;
            if !url.is_empty() {
                let sh2 = sh.clone();
                let ctx2 = ctx.clone();
                let n = skin.num("/taskbar/art_pixels", 16.0) as u32;
                let colors = skin.num("/art_colors", 10.0) as usize;
                crate::art::request_with(url, n, colors, move |img| {
                    sh2.art.lock().unwrap().pending = Some(img);
                    ctx2.request_repaint_of(id());
                });
            }
        }
        if let Some(img) = a.pending.take() {
            a.tex = Some(ctx.load_texture("tb_art", img, egui::TextureOptions::NEAREST));
        }
    }

    let inp = ctx.input(|i| {
        (
            i.pointer.hover_pos(),
            i.pointer.primary_pressed(),
            i.pointer.primary_released(),
            i.pointer.primary_down(),
            i.pointer.button_double_clicked(egui::PointerButton::Primary),
            i.pointer.button_pressed(egui::PointerButton::Middle),
            i.pointer.button_pressed(egui::PointerButton::Secondary),
            i.raw_scroll_delta.y,
            i.modifiers.ctrl,
        )
    });
    let settling = sh.moved_at.lock().unwrap().map(|t| t.elapsed() < Duration::from_millis(400)).unwrap_or(false);
    if settling {
        sh.ui.lock().unwrap().pressed = None;
        ctx.request_repaint_after(Duration::from_millis(400));
    }
    let inp = if info_only || settling { (inp.0.filter(|_| !info_only), false, false, false, false, false, false, 0.0, false) } else { inp };
    // pointer in 1x units
    let pos = inp.0.map(|p| Pos2::new((p.x - o.x) / s, (p.y - o.y) / s));
    let over = |x: f32, y: f32, w: f32, h: f32| pos.map(|p| p.x >= x && p.x < x + w && p.y >= y && p.y < y + h).unwrap_or(false);
    let paused = play.status == "paused";
    let dim = (idle && cfg.tb_idle == "dim") || (!idle && paused && cfg.tb_paused_dim);
    let dim_k = skin.num("/taskbar/dim", 0.45) as f32;
    let tint = if dim { Color32::from_gray((255.0 * dim_k.clamp(0.1, 1.0)) as u8) } else { Color32::WHITE };

    let mut animate = false;
    egui::CentralPanel::default().frame(egui::Frame::NONE).show(ctx, |ui| {
        let p = ui.painter().clone();
        let full_uv = Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0));
        let spr = |name: &str, x: f32, y: f32, w: f32, h: f32| {
            if let Some(sp) = skin.sprite(name) {
                p.image(sp.tex.id(), Rect::from_min_size(Pos2::new(o.x + x * s, o.y + y * s), Vec2::new(w * s, h * s)), full_uv, tint);
            }
        };
        // 9-slice: corners stay crisp, edges and middle stretch
        let nine = |name: &str, x: f32, y: f32, w: f32, h: f32| {
            let Some(sp) = skin.sprite(name) else { return };
            let b = (skin.num(&format!("/taskbar/{}_corner", name), 4.0) as f32).min((sp.w / 2.0).floor()).min((sp.h / 2.0).floor());
            let xs = [(0.0, b, 0.0, b), (b, sp.w - b, b, w - 2.0 * b), (sp.w - b, sp.w, w - b, b)];
            let ys = [(0.0, b, 0.0, b), (b, sp.h - b, b, h - 2.0 * b), (sp.h - b, sp.h, h - b, b)];
            for (u0, u1, dx, dw) in xs {
                for (v0, v1, dy, dh) in ys {
                    if dw <= 0.0 || dh <= 0.0 {
                        continue;
                    }
                    p.image(
                        sp.tex.id(),
                        Rect::from_min_size(Pos2::new(o.x + (x + dx) * s, o.y + (y + dy) * s), Vec2::new(dw * s, dh * s)),
                        Rect::from_min_max(Pos2::new(u0 / sp.w, v0 / sp.h), Pos2::new(u1 / sp.w, v1 / sp.h)),
                        tint,
                    );
                }
            }
        };
        if icon_mode {
            spr("icon", 0.0, 0.0, 16.0, 16.0);
            if over(0.0, 0.0, 16.0, 16.0) {
                ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
            }
            if inp.2 && over(0.0, 0.0, 16.0, 16.0) {
                crate::desktop::show_window(ctx);
                let _ = sh.solo.lock().unwrap().send(command("play"));
            }
            return;
        }
        let (bw, bh) = base_size(&sh.cfg, false, info_only);
        nine("tb_bg", 0.0, 0.0, bw, bh);
        // art
        match &sh.art.lock().unwrap().tex {
            Some(t) => {
                p.image(t.id(), Rect::from_min_size(o, Vec2::splat(16.0 * s)), full_uv, tint);
            }
            None => spr("tb_art_empty", 0.0, 0.0, 16.0, 16.0),
        }

        // ---- text block -------------------------------------------------------------------
        // An invisible bezel keeps the text off the frame. The text gets its own integer scale
        // so two lines fit when the taskbar is tall enough; otherwise one scrolling line.
        let tw = cfg.tb_text_w.clamp(32, 256) as f32;
        let tx = 18.0;
        let bezel = skin.num("/taskbar/bezel", 2.0) as f32 * s;
        let area = Rect::from_min_size(Pos2::new(o.x + tx * s, o.y + bezel), Vec2::new(tw * s, hpx - 2.0 * bezel));
        let inner_h = area.height();
        let ts1 = (inner_h / 9.0).floor().clamp(1.0, s);
        let ts2 = (inner_h / 19.0).floor().min(s);
        let two = match cfg.tb_lines.as_str() {
            "one" => false,
            "two" => ts2 >= 1.0,
            _ => ts2 >= 2.0 || (ts2 >= 1.0 && ts1 <= 1.0),
        };
        let ts = if two { ts2 } else { ts1 };
        let clip = p.with_clip_rect(area);
        let playing = play.status == "playing" || play.status == "buffering";

        // what to show: OSD > hover tip > song
        let osd_msg = sh.ui.lock().unwrap().osd.as_ref().filter(|(_, t)| t.elapsed() < Duration::from_millis(1200)).map(|(m, _)| m.clone());
        let (l1, l2) = match (&osd_msg, &play.track) {
            (Some(m), _) => (m.clone(), String::new()),
            (None, Some(t)) => (t.name.clone(), t.artists.clone()),
            (None, None) => ("SpoX".into(), "Nothing playing".into()),
        };
        if let Some(f) = skin.font.as_ref() {
            let c1 = skin.json["colors"]["tb_title"].as_str().and_then(crate::skin::parse_color).unwrap_or(skin.color("title"));
            let c2 = skin.json["colors"]["tb_artist"].as_str().and_then(crate::skin::parse_color).unwrap_or(skin.color("info"));
            let (c1, c2) = if dim { (c1.gamma_multiply(dim_k), c2.gamma_multiply(dim_k)) } else { (c1, c2) };
            // text coordinates are in "ts" units relative to the text area
            let to = Pos2::new(area.min.x, area.min.y);
            let aw = (area.width() / ts).floor();
            let ah = area.height() / ts;
            let block = if two { 19.0 } else { 9.0 };
            let y0 = ((ah - block) / 2.0).floor().max(0.0);
            // no status icon: the play/pause button already says it
            let x1 = 0.0;
            let el = sh.ui.lock().unwrap().t0.elapsed().as_secs_f32();
            let marquee = |text: &str, x: f32, y: f32, col: Color32| -> bool {
                let avail = aw - x;
                let cp = clip.with_clip_rect(Rect::from_min_max(Pos2::new(to.x + x * ts, area.min.y), area.max));
                let w = f.width(text);
                if w <= avail {
                    f.draw(&cp, to, ts, x, y, text, col);
                    return false;
                }
                let period = w + 24.0;
                let speed = 14.0;
                let cycle = 2.5 + period / speed;
                let ph = el % cycle;
                let off = if ph < 2.5 { 0.0 } else { ((ph - 2.5) * speed).floor() };
                f.draw(&cp, to, ts, x - off, y, text, col);
                f.draw(&cp, to, ts, x - off + period, y, text, col);
                true
            };
            if two {
                animate |= marquee(&l1, x1, y0 + 2.0, c1);
                animate |= marquee(&l2, 0.0, y0 + 12.0, c2);
            } else {
                let line = if l2.is_empty() { l1.clone() } else { format!("{}  \u{b7}  {}", l1, l2) };
                animate |= marquee(&line, x1, y0 + 2.0, c1);
            }
        }
        // volume bar along the bottom of the text area
        let vol_flash = sh.ui.lock().unwrap().vol_shown.map(|t| t.elapsed() < Duration::from_millis(1500)).unwrap_or(false);
        if cfg.tb_volume_bar || vol_flash {
            let y = area.max.y - s.max(1.0);
            let w = (area.width() * (play.volume as f32 / 100.0).clamp(0.0, 1.0)).round();
            let back = skin.json["colors"]["tb_vol_back"].as_str().and_then(crate::skin::parse_color).unwrap_or(Color32::from_rgb(28, 53, 39));
            let col = skin.json["colors"]["tb_vol"].as_str().and_then(crate::skin::parse_color).unwrap_or(skin.color("title"));
            p.rect_filled(Rect::from_min_size(Pos2::new(area.min.x, y), Vec2::new(area.width(), s)), 0.0, back.gamma_multiply(if dim { dim_k } else { 1.0 }));
            p.rect_filled(Rect::from_min_size(Pos2::new(area.min.x, y), Vec2::new(w, s)), 0.0, col.gamma_multiply(if dim { dim_k } else { 1.0 }));
            if vol_flash {
                animate = true;
            }
        }

        // ---- buttons ----------------------------------------------------------------------
        let bx = tx + tw + 2.0;
        let mut ui_st = sh.ui.lock().unwrap();
        let mut hover_now: Option<&'static str> = None;
        if !info_only {
            let smart = sh.smart_on.load(Ordering::SeqCst);
            let shuf_name = if smart { "tb_shuffle_smart" } else if play.shuffle { "tb_shuffle_on" } else { "tb_shuffle" };
            let mut btns = vec![("prev", "tb_prev"), ("pp", if playing { "tb_pause" } else { "tb_play" }), ("next", "tb_next")];
            if cfg.tb_shuffle_btn {
                btns.push(("shuffle", shuf_name));
            }
            for (i, (idn, name)) in btns.into_iter().enumerate() {
                let x = bx + i as f32 * 16.0;
                let ov = over(x, 0.0, 16.0, 16.0);
                if ov {
                    hover_now = Some(idn);
                }
                if inp.1 && ov {
                    ui_st.pressed = Some(idn);
                }
                let held = ui_st.pressed == Some(idn) && ov && inp.3;
                let down = if idn == "shuffle" { "tb_shuffle_down".to_string() } else { format!("{}_down", name) };
                let n = if held && skin.sprite(&down).is_some() { down } else { name.to_string() };
                spr(&n, x, 0.0, 16.0, 16.0);
                if inp.2 && ov && ui_st.pressed == Some(idn) {
                    drop(ui_st);
                    act(sh, ctx, match idn { "prev" => "prev", "pp" => "play_pause", "shuffle" => "shuffle", _ => "next" }, &play);
                    ui_st = sh.ui.lock().unwrap();
                }
            }
        }
        if inp.2 || !inp.3 {
            ui_st.pressed = None;
        }
        // frame drawn on top (transparent middle), so the border also runs over art and buttons
        nine("tb_frame", 0.0, 0.0, bw, bh);

        // clicks on art / text (single click waits a moment only if a double-click action is set)
        let on_art = over(0.0, 0.0, 16.0, 16.0);
        let on_text = over(16.0, 0.0, tw + 4.0, 16.0);
        if on_art {
            hover_now = Some("art");
        } else if on_text {
            hover_now = Some("text");
        }
        if hover_now.is_some() {
            ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
        }
        let mut todo: Vec<String> = Vec::new();
        if inp.2 && on_art {
            todo.push(cfg.tb_click_art.clone());
        }
        let has_dbl = cfg.tb_dbl_text != "none";
        if inp.4 && on_text && has_dbl {
            ui_st.pending_text_click = None;
            todo.push(cfg.tb_dbl_text.clone());
        } else if inp.2 && on_text {
            if has_dbl {
                ui_st.pending_text_click = Some(Instant::now());
            } else {
                todo.push(cfg.tb_click_text.clone());
            }
        }
        if let Some(t) = ui_st.pending_text_click {
            if t.elapsed() > Duration::from_millis(700) {
                // stale (the window was hidden in between): drop it, never fire late
                ui_st.pending_text_click = None;
            } else if t.elapsed() > Duration::from_millis(250) {
                ui_st.pending_text_click = None;
                todo.push(cfg.tb_click_text.clone());
            } else {
                ctx.request_repaint_after(Duration::from_millis(260));
            }
        }
        if inp.5 && (on_art || on_text) {
            todo.push(cfg.tb_middle.clone());
        }
        if inp.6 && (on_art || on_text) {
            todo.push(cfg.tb_right.clone());
        }
        drop(ui_st);
        for a in todo {
            act(sh, ctx, &a, &play);
        }
        // scroll
        if inp.7 != 0.0 && over(0.0, 0.0, bw, 16.0) {
            let up = inp.7 > 0.0;
            if inp.8 {
                // Ctrl + scroll: nudge the player along the taskbar
                let mut c = sh.cfg.lock().unwrap();
                let d = if up { 8 } else { -8 };
                if c.tb_pos == "left" {
                    c.tb_left_offset = (c.tb_left_offset + d).clamp(0, 4000);
                } else {
                    if c.tb_pos != "custom" {
                        c.tb_pos = "custom".into();
                    }
                    c.tb_offset = (c.tb_offset - d).clamp(0, 4000);
                }
            } else {
                match cfg.tb_wheel.as_str() {
                    "volume" => {
                        let v = (play.volume as i32 + if up { 5 } else { -5 }).clamp(0, 100);
                        let _ = sh.solo.lock().unwrap().send(command_with("set_volume", "volume", json!(v)));
                        sh.live.lock().unwrap().volume = v as _;
                        show_volume(sh);
                        osd(sh, &format!("Volume {}%", v));
                    }
                    "seek" => act(sh, ctx, if up { "seek_fwd" } else { "seek_back" }, &play),
                    "track" => {
                        let _ = sh.solo.lock().unwrap().send(command(if up { "skip_prev" } else { "skip_next" }));
                    }
                    _ => {}
                }
            }
        }
    });
    let osd_on = sh.ui.lock().unwrap().osd.as_ref().map(|(_, t)| t.elapsed() < Duration::from_millis(1300)).unwrap_or(false);
    if osd_on {
        ctx.request_repaint_after(Duration::from_millis(100));
    }
    let tb_pace = if animate || osd_on { 70 } else if play.status == "playing" { 1000 } else { 0 };
    crate::desktop::TB_PACE_MS.store(tb_pace, Ordering::SeqCst);
    if animate {
        // marquee moves 1 px per step: repaint only as often as needed
        ctx.request_repaint_after(Duration::from_millis(70));
    }
}

/// Windows: keeps the player glued to the taskbar (position, size, on top, hidden in fullscreen).
pub fn start_watcher(sh: TbShared, ctx: egui::Context) {
    #[cfg(windows)]
    std::thread::Builder::new()
        .name("taskbar-watch".into())
        .spawn(move || win::watch(sh, ctx))
        .ok();
    #[cfg(not(windows))]
    {
        let _ = (sh, ctx);
    }
}

#[cfg(windows)]
mod win {
    use super::*;
    use windows_sys::Win32::Foundation::{HWND, RECT};
    use windows_sys::Win32::Graphics::Gdi::{GetMonitorInfoW, MonitorFromWindow, MONITORINFO, MONITOR_DEFAULTTONEAREST};
    use windows_sys::Win32::UI::Shell::SHQueryUserNotificationState;
    use windows_sys::Win32::UI::WindowsAndMessaging::*;

    fn w(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    fn rect(h: HWND) -> Option<RECT> {
        let mut r: RECT = unsafe { std::mem::zeroed() };
        (unsafe { GetWindowRect(h, &mut r) } != 0).then_some(r)
    }

    fn monitor_rect(mon: windows_sys::Win32::Graphics::Gdi::HMONITOR) -> Option<RECT> {
        unsafe {
            let mut mi: MONITORINFO = std::mem::zeroed();
            mi.cbSize = std::mem::size_of::<MONITORINFO>() as u32;
            (GetMonitorInfoW(mon, &mut mi) != 0).then_some(mi.rcMonitor)
        }
    }

    fn class_of(h: HWND) -> String {
        let mut cls = [0u16; 96];
        let n = unsafe { GetClassNameW(h, cls.as_mut_ptr(), 96) };
        String::from_utf16_lossy(&cls[..n.max(0) as usize])
    }

    /// A fullscreen app (game, video, slideshow) covers the given monitor.
    fn fullscreen_on(mon: windows_sys::Win32::Graphics::Gdi::HMONITOR, me: HWND) -> bool {
        unsafe {
            let fg = GetForegroundWindow();
            if fg.is_null() || fg == me {
                return false;
            }
            if MonitorFromWindow(fg, MONITOR_DEFAULTTONEAREST) != mon {
                return false;
            }
            let mut state = 0;
            if SHQueryUserNotificationState(&mut state) == 0 && (state == 3 || state == 4) {
                // QUNS_RUNNING_D3D_FULL_SCREEN, QUNS_PRESENTATION_MODE
                return true;
            }
            let cls = class_of(fg);
            if matches!(cls.as_str(), "Progman" | "WorkerW" | "Shell_TrayWnd" | "Shell_SecondaryTrayWnd") {
                return false;
            }
            let Some(r) = rect(fg) else { return false };
            let Some(m) = monitor_rect(mon) else { return false };
            r.left <= m.left && r.top <= m.top && r.right >= m.right && r.bottom >= m.bottom
        }
    }

    /// Start menu, search, Alt-Tab, tray overflow... only when one actually covers the player.
    fn shell_popup_over(r: [i32; 4]) -> bool {
        unsafe {
            let fg = GetForegroundWindow();
            if fg.is_null() {
                return false;
            }
            let popup = matches!(
                class_of(fg).as_str(),
                "Windows.UI.Core.CoreWindow" | "XamlExplorerHostIslandWindow" | "TopLevelWindowForOverflowXamlIsland" | "MultitaskingViewFrame"
            );
            if !popup {
                return false;
            }
            let Some(p) = rect(fg) else { return false };
            p.left < r[0] + r[2] && p.right > r[0] && p.top < r[1] + r[3] && p.bottom > r[1]
        }
    }

    /// "Automatically hide the taskbar" is on.
    fn taskbar_autohides() -> bool {
        unsafe {
            let mut d: windows_sys::Win32::UI::Shell::APPBARDATA = std::mem::zeroed();
            d.cbSize = std::mem::size_of::<windows_sys::Win32::UI::Shell::APPBARDATA>() as u32;
            // ABM_GETSTATE = 4, ABS_AUTOHIDE = 1
            (windows_sys::Win32::UI::Shell::SHAppBarMessage(4, &mut d) as u32) & 1 != 0
        }
    }

    /// All taskbars: the main one first, then the other monitors' left to right.
    fn taskbars() -> Vec<HWND> {
        unsafe {
            let mut v = Vec::new();
            let main = FindWindowW(w("Shell_TrayWnd").as_ptr(), std::ptr::null());
            if !main.is_null() {
                v.push(main);
            }
            let cls = w("Shell_SecondaryTrayWnd");
            let mut more = Vec::new();
            let mut prev: HWND = std::ptr::null_mut();
            for _ in 0..16 {
                let h = FindWindowExW(std::ptr::null_mut(), prev, cls.as_ptr(), std::ptr::null());
                if h.is_null() {
                    break;
                }
                more.push(h);
                prev = h;
            }
            more.sort_by_key(|h| rect(*h).map(|r| (r.left, r.top)).unwrap_or((0, 0)));
            v.extend(more);
            v
        }
    }

    pub fn watch(sh: TbShared, ctx: egui::Context) {
        let title = w(super::TITLE);
        let notify_cls = w("TrayNotifyWnd");
        let mut last: Option<(i32, i32, i32, i32, bool)> = None;
        let mut last_me: HWND = std::ptr::null_mut();
        let mut follow_bar: usize = 0;
        loop {
            std::thread::sleep(Duration::from_millis(250));
            let cfg = sh.cfg.lock().unwrap().clone();
            unsafe {
                let me = FindWindowW(std::ptr::null(), title.as_ptr());
                if me.is_null() {
                    last = None;
                    continue;
                }
                if me != last_me {
                    // a fresh window (player switched off and on): it starts hidden and gets placed below
                    last_me = me;
                    last = None;
                }
                let bars = taskbars();
                if bars.is_empty() {
                    continue;
                }
                sh.monitors.store(bars.len(), Ordering::SeqCst);
                // which taskbar?
                let mut idx = match cfg.tb_monitor.as_str() {
                    "main" => 0,
                    "follow" => {
                        let fg = GetForegroundWindow();
                        if !fg.is_null() && fg != me {
                            let mon = MonitorFromWindow(fg, MONITOR_DEFAULTTONEAREST);
                            if let Some(i) = bars.iter().position(|b| MonitorFromWindow(*b, MONITOR_DEFAULTTONEAREST) == mon) {
                                follow_bar = i;
                            }
                        }
                        follow_bar.min(bars.len() - 1)
                    }
                    n => n.parse::<usize>().ok().map(|n| n.saturating_sub(1)).filter(|i| *i < bars.len()).unwrap_or(0),
                };
                // a fullscreen app on that monitor: hop over to another monitor's taskbar
                let mut mode = cfg.tb_fullscreen.clone();
                if mode == "other" {
                    let mon0 = MonitorFromWindow(bars[idx], MONITOR_DEFAULTTONEAREST);
                    if fullscreen_on(mon0, me) {
                        match (0..bars.len()).find(|j| *j != idx && !fullscreen_on(MonitorFromWindow(bars[*j], MONITOR_DEFAULTTONEAREST), me)) {
                            Some(j) => idx = j,
                            None => mode = "info".into(), // one monitor: info only on top of the app
                        }
                    }
                }
                let bar = bars[idx];
                let Some(br) = rect(bar) else { continue };
                let mon = MonitorFromWindow(bar, MONITOR_DEFAULTTONEAREST);
                let mr = monitor_rect(mon);
                let h = br.bottom - br.top;
                let wide = (br.right - br.left) > h;
                let scale = (h / 16).clamp(0, 4);
                // auto-hidden taskbar slid off the screen
                let slid_away = mr.map(|m| br.bottom > m.bottom + 2 || br.top < m.top - 2 || h < 8).unwrap_or(false);
                let play = sh.live.lock().unwrap().clone();
                let idle = super::nothing_playing(&sh, &play);
                let fs = mode != "normal" && fullscreen_on(mon, me);
                let info = fs && (mode == "info" || mode == "other");
                // the taskbar slides away for a moment while a game / video goes fullscreen or back:
                // stay where we are instead of blinking out (unless the taskbar is set to auto-hide)
                if slid_away && !info && !taskbar_autohides() && last.map(|l| l.4).unwrap_or(false) {
                    continue;
                }
                if sh.fs_info.swap(info, Ordering::SeqCst) != info {
                    ctx.request_repaint_of(super::id());
                }
                let hide = !cfg.tb_on
                    || !wide
                    || scale < 1
                    || (slid_away && !info)
                    || (fs && mode == "hide")
                    || (idle && cfg.tb_idle == "hide")
                    || (cfg.tb_show_when == "hidden" && !crate::desktop::HIDDEN.load(Ordering::SeqCst))
                    || (!info && last.map(|l| l.4 && shell_popup_over([l.0, l.1, l.2, l.3])).unwrap_or(false));
                if hide {
                    {
                        let mut u = sh.ui.lock().unwrap();
                        u.pressed = None;
                        u.pending_text_click = None;
                    }
                    if last.map(|l| l.4).unwrap_or(true) {
                        ShowWindowAsync(me, SW_HIDE);
                    }
                    last = Some((0, 0, 0, 0, false));
                    crate::desktop::TB_PACE_MS.store(0, Ordering::SeqCst);
                    continue;
                }
                let (bw, bh) = super::base_size(&sh.cfg, sh.idle_icon.load(Ordering::SeqCst), info);
                let (ww, wh) = ((bw as i32) * scale, (bh as i32) * scale);
                let (x, y) = if info {
                    // fullscreen: bottom-left corner of that monitor, where the taskbar would be
                    let m = mr.unwrap_or(br);
                    (m.left + cfg.tb_left_offset.max(8), m.bottom - wh - 8 * scale.max(1) / 2)
                } else {
                    let tray_left = {
                        let n = FindWindowExW(bar, std::ptr::null_mut(), notify_cls.as_ptr(), std::ptr::null());
                        (!n.is_null()).then(|| rect(n)).flatten().map(|r| r.left).filter(|l| *l > br.left + 200 && *l < br.right)
                    };
                    // "next to tray": the main taskbar has a tray; other monitors may only have a clock
                    // (or nothing, or a Windhawk layout), so there it keeps a fixed gap from the right edge
                    let x = match cfg.tb_pos.as_str() {
                        "left" => br.left + cfg.tb_left_offset,
                        "custom" => br.right - cfg.tb_offset - ww,
                        _ => tray_left.map(|l| l - ww - 4 * scale).unwrap_or(br.right - cfg.tb_offset.max(0) - ww),
                    };
                    (x.clamp(br.left, (br.right - ww).max(br.left)), br.top + (h - wh) / 2)
                };
                let now = (x, y, ww, wh, true);
                // Windows may resize/move us on its own (DPI change when crossing monitors, Explorer restart):
                // compare with where the window really is, not just with what we asked last time
                let real = rect(me).map(|r| (r.left, r.top, r.right - r.left, r.bottom - r.top));
                let drifted = real.map(|r| r != (x, y, ww, wh)).unwrap_or(true);
                let visible = IsWindowVisible(me) != 0;
                if last != Some(now) || drifted || !visible {
                    *sh.moved_at.lock().unwrap() = Some(Instant::now());
                    // size + place first, while still hidden, then show: no frame is ever seen floating
                    SetWindowPos(me, HWND_TOPMOST, x, y, ww, wh, SWP_NOACTIVATE);
                    ctx.request_repaint_of(super::id());
                    if !visible {
                        std::thread::sleep(Duration::from_millis(40));
                        ShowWindowAsync(me, SW_SHOWNOACTIVATE);
                    }
                    last = Some(now);
                } else {
                    // keep it above the taskbar (the taskbar re-raises itself when clicked)
                    SetWindowPos(me, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOACTIVATE | SWP_NOMOVE | SWP_NOSIZE | SWP_ASYNCWINDOWPOS);
                }
            }
        }
    }
}

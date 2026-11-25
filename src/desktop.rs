//! Windows integration: media keys / headset buttons / Windows media flyout (SMTC via souvlaki),
//! and the system tray icon. Everything here keeps working while the window is hidden in the tray.

use crate::soloist::{command, command_with};
use crate::state::Play;
use eframe::egui;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::Duration;

static HWND: AtomicIsize = AtomicIsize::new(0);
pub static HIDDEN: AtomicBool = AtomicBool::new(false);

pub fn set_hwnd(cc: &eframe::CreationContext) {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    if let Ok(h) = cc.window_handle() {
        if let RawWindowHandle::Win32(w) = h.as_raw() {
            HWND.store(w.hwnd.get(), Ordering::SeqCst);
            #[cfg(windows)]
            unsafe {
                use windows_sys::Win32::UI::Shell::SetWindowSubclass;
                SetWindowSubclass(w.hwnd.get() as _, Some(no_redraw_on_move), 1, 0);
            }
        }
    }
}
pub fn hwnd() -> isize {
    HWND.load(Ordering::SeqCst)
}

/// Message a second SpoX sends to the running one: "show yourself".
#[cfg_attr(not(windows), allow(dead_code))]
pub const WM_SPOX_SHOW: u32 = 0x8000 + 77;

/// Window position before hiding (physical pixels), so it comes back in the same place.
static SAVED_POS: Mutex<Option<[i32; 4]>> = Mutex::new(None);
#[cfg_attr(not(windows), allow(dead_code))]
static SAVED_EX: AtomicIsize = AtomicIsize::new(0);
#[cfg_attr(not(windows), allow(dead_code))]
static SAVED_TOP: AtomicBool = AtomicBool::new(false);
/// "hide" requests from the tray / taskbar player, run by the app (with the pixel animation)
pub static PENDING: Mutex<Vec<&'static str>> = Mutex::new(Vec::new());

/// Hide to the tray. Windows stops sending a truly hidden window any updates, so SpoX would
/// "fall asleep" (shuffle, taskbar buttons... waiting until it's shown). Instead the window stays
/// alive: fully see-through, click-through, off-screen, and out of the taskbar and Alt+Tab.
pub fn hide_window() {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::UI::WindowsAndMessaging::*;
        let h = hwnd();
        if h == 0 || HIDDEN.swap(true, Ordering::SeqCst) {
            return;
        }
        let h = h as windows_sys::Win32::Foundation::HWND;
        let mut r: windows_sys::Win32::Foundation::RECT = std::mem::zeroed();
        if GetWindowRect(h, &mut r) != 0 && IsIconic(h) == 0 {
            *SAVED_POS.lock().unwrap() = Some([r.left, r.top, r.right - r.left, r.bottom - r.top]);
        }
        let ex = GetWindowLongPtrW(h, GWL_EXSTYLE);
        SAVED_EX.store(ex, Ordering::SeqCst);
        SAVED_TOP.store(ex & WS_EX_TOPMOST as isize != 0, Ordering::SeqCst);
        ShowWindow(h, SW_HIDE);
        let soft = (ex | (WS_EX_TOOLWINDOW | WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_NOACTIVATE) as isize) & !(WS_EX_APPWINDOW as isize);
        SetWindowLongPtrW(h, GWL_EXSTYLE, soft);
        SetLayeredWindowAttributes(h, 0, 0, LWA_ALPHA);
        SetWindowPos(h, HWND_NOTOPMOST, -32000, -32000, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
        ShowWindow(h, SW_SHOWNOACTIVATE);
    }
    #[cfg(not(windows))]
    HIDDEN.store(true, Ordering::SeqCst);
}

/// Undo [hide_window] (no-op if it isn't hidden).
#[cfg(windows)]
unsafe fn unhide(h: windows_sys::Win32::Foundation::HWND) {
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    if !HIDDEN.swap(false, Ordering::SeqCst) {
        return;
    }
    ShowWindow(h, SW_HIDE);
    SetWindowLongPtrW(h, GWL_EXSTYLE, SAVED_EX.load(Ordering::SeqCst) & !(WS_EX_TOPMOST as isize));
    let after = if SAVED_TOP.load(Ordering::SeqCst) { HWND_TOPMOST } else { HWND_NOTOPMOST };
    if let Some([x, y, _, _]) = *SAVED_POS.lock().unwrap() {
        SetWindowPos(h, after, x, y, 0, 0, SWP_NOSIZE | SWP_NOACTIVATE);
    } else {
        SetWindowPos(h, after, 0, 0, 0, 0, SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE);
    }
}

pub fn show_window(ctx: &egui::Context) {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::UI::WindowsAndMessaging::{SetForegroundWindow, ShowWindow, SW_RESTORE, SW_SHOW};
        let h = hwnd();
        if h != 0 {
            unhide(h as _);
            ShowWindow(h as _, SW_SHOW);
            ShowWindow(h as _, SW_RESTORE);
            SetForegroundWindow(h as _);
        }
    }
    HIDDEN.store(false, Ordering::SeqCst);
    ctx.request_repaint();
}

/// Ask the app to hide with its close animation (from the tray icon / taskbar player).
pub fn request_hide(ctx: &egui::Context) {
    PENDING.lock().unwrap().push("hide");
    ctx.request_repaint_of(egui::ViewportId::ROOT);
}

fn is_hidden() -> bool {
    HIDDEN.load(Ordering::SeqCst)
}

// ---------------- media keys (SMTC) ----------------

/// Starts the media-controls thread. Returns a "tap": send every Soloist event to it.
pub fn start_media(solo: Sender<String>) -> Option<Sender<Value>> {
    if !cfg!(windows) || hwnd() == 0 {
        return None;
    }
    let (tx, rx) = std::sync::mpsc::channel::<Value>();
    std::thread::Builder::new()
        .name("media-keys".into())
        .spawn(move || {
            let cfg = souvlaki::PlatformConfig {
                display_name: "SpoX",
                dbus_name: "spox",
                hwnd: Some(hwnd() as *mut std::ffi::c_void),
            };
            let Ok(mut mc) = souvlaki::MediaControls::new(cfg) else { return };
            let playing = Arc::new(AtomicBool::new(false));
            let p2 = playing.clone();
            let s = solo.clone();
            let _ = mc.attach(move |e| {
                use souvlaki::MediaControlEvent as M;
                let cmd = match e {
                    M::Play => command("play"),
                    M::Pause | M::Stop => command("pause"),
                    M::Toggle => {
                        if p2.load(Ordering::SeqCst) { command("pause") } else { command("play") }
                    }
                    M::Next => command("skip_next"),
                    M::Previous => command("skip_prev"),
                    M::SetPosition(souvlaki::MediaPosition(d)) => command_with("seek", "position_ms", json!(d.as_millis() as u64)),
                    _ => return,
                };
                let _ = s.send(cmd);
            });
            let mut play = Play::default();
            let mut last_meta = String::new();
            let mut last_state = String::new();
            while let Ok(v) = rx.recv() {
                play.apply(&v);
                playing.store(play.status == "playing", Ordering::SeqCst);
                if let Some(t) = &play.track {
                    let key = format!("{}|{}", t.uri, t.cover_large.as_deref().unwrap_or(""));
                    if key != last_meta {
                        last_meta = key;
                        let _ = mc.set_metadata(souvlaki::MediaMetadata {
                            title: Some(&t.name),
                            artist: Some(&t.artists),
                            album: Some(&t.album),
                            cover_url: t.cover_large.as_deref(),
                            duration: (t.duration_ms > 0).then(|| Duration::from_millis(t.duration_ms)),
                        });
                    }
                }
                let pos = Some(souvlaki::MediaPosition(Duration::from_millis(play.position(crate::config::now_ms()) as u64)));
                let state = format!("{}|{}", play.status, play.pos_ts);
                if state != last_state {
                    last_state = state;
                    let _ = mc.set_playback(match play.status.as_str() {
                        "playing" | "buffering" => souvlaki::MediaPlayback::Playing { progress: pos },
                        "paused" => souvlaki::MediaPlayback::Paused { progress: pos },
                        _ => souvlaki::MediaPlayback::Stopped,
                    });
                }
            }
        })
        .ok()?;
    Some(tx)
}

// ---------------- tray ----------------

pub struct Tray {
    _icon: tray_icon::TrayIcon,
    shown: std::cell::Cell<bool>,
}

impl Tray {
    /// Show / hide the tray icon (only calls Windows when it changes).
    pub fn set_visible(&self, on: bool) {
        if self.shown.get() != on {
            self.shown.set(on);
            let _ = self._icon.set_visible(on);
        }
    }
}

fn tray_image(rgba: &image::RgbaImage) -> Option<tray_icon::Icon> {
    // pixel-art: upscale to 32x32 with no smoothing so it stays sharp at 125-200% scaling
    let s = if rgba.width() < 32 { image::imageops::resize(rgba, 32, 32, image::imageops::FilterType::Nearest) } else { rgba.clone() };
    tray_icon::Icon::from_rgba(s.as_raw().clone(), s.width(), s.height()).ok()
}

pub fn start_tray(icon: &image::RgbaImage, solo: Sender<String>, ctx: egui::Context) -> Option<Tray> {
    if !cfg!(windows) {
        return None;
    }
    use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
    let menu = Menu::new();
    let show = MenuItem::new("Show SpoX", true, None);
    let pp = MenuItem::new("Play / Pause", true, None);
    let next = MenuItem::new("Next", true, None);
    let prev = MenuItem::new("Previous", true, None);
    let quit = MenuItem::new("Quit SpoX", true, None);
    let _ = menu.append_items(&[&show, &PredefinedMenuItem::separator(), &pp, &next, &prev, &PredefinedMenuItem::separator(), &quit]);
    let ids = (show.id().clone(), pp.id().clone(), next.id().clone(), prev.id().clone(), quit.id().clone());
    let tray = tray_icon::TrayIconBuilder::new()
        .with_icon(tray_image(icon)?)
        .with_tooltip("SpoX")
        .with_menu(Box::new(menu))
        .with_menu_on_left_click(false)
        .build()
        .ok()?;
    let solo = Mutex::new(solo);
    let ctx2 = ctx.clone();
    MenuEvent::set_event_handler(Some(move |e: MenuEvent| {
        let s = solo.lock().unwrap();
        if e.id == ids.0 {
            show_window(&ctx2);
        } else if e.id == ids.1 {
            let _ = s.send("__toggle__".into());
        } else if e.id == ids.2 {
            let _ = s.send(command("skip_next"));
        } else if e.id == ids.3 {
            let _ = s.send(command("skip_prev"));
        } else if e.id == ids.4 {
            show_window(&ctx2);
            ctx2.send_viewport_cmd(egui::ViewportCommand::Close);
            ctx2.request_repaint();
        }
    }));
    tray_icon::TrayIconEvent::set_event_handler(Some(move |e: tray_icon::TrayIconEvent| {
        if let tray_icon::TrayIconEvent::Click {
            button: tray_icon::MouseButton::Left,
            button_state: tray_icon::MouseButtonState::Up,
            ..
        } = e
        {
            if is_hidden() {
                show_window(&ctx);
            } else {
                request_hide(&ctx);
            }
        }
    }));
    Some(Tray { _icon: tray, shown: std::cell::Cell::new(true) })
}

/// RAM used by a process in MB, the way Task Manager counts it ("private working set":
/// memory only this process uses, without shared Windows/graphics-driver pages).
pub fn mem_mb(pid: u32) -> Option<f64> {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::Foundation::CloseHandle;
        use windows_sys::Win32::System::ProcessStatus::{K32GetProcessMemoryInfo, PROCESS_MEMORY_COUNTERS};
        use windows_sys::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};
        // PROCESS_MEMORY_COUNTERS_EX2 (Windows 10 1809+), declared here so it doesn't depend on the bindings
        #[repr(C)]
        #[derive(Default)]
        struct Ex2 {
            cb: u32,
            page_fault_count: u32,
            peak_working_set: usize,
            working_set: usize,
            quota_peak_paged: usize,
            quota_paged: usize,
            quota_peak_nonpaged: usize,
            quota_nonpaged: usize,
            pagefile: usize,
            peak_pagefile: usize,
            private_usage: usize,
            private_working_set: usize,
            shared_commit: usize,
        }
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            return None;
        }
        let mut c = Ex2 { cb: std::mem::size_of::<Ex2>() as u32, ..Default::default() };
        let ok = K32GetProcessMemoryInfo(h, &mut c as *mut Ex2 as *mut PROCESS_MEMORY_COUNTERS, c.cb) != 0;
        CloseHandle(h);
        let bytes = if c.private_working_set > 0 { c.private_working_set } else { c.working_set };
        return ok.then(|| bytes as f64 / (1024.0 * 1024.0));
    }
    #[cfg(not(windows))]
    {
        let s = std::fs::read_to_string(format!("/proc/{}/status", pid)).ok()?;
        let kb: f64 = s.lines().find(|l| l.starts_with("VmRSS:"))?.split_whitespace().nth(1)?.parse().ok()?;
        Some(kb / 1024.0)
    }
}

/// Wake the main window so it handles queued work (e.g. actions from the taskbar player).
pub fn wake(ctx: &egui::Context) {
    ctx.request_repaint_of(egui::ViewportId::ROOT);
}

/// Main window rect on screen in physical pixels: [x, y, w, h].
pub fn window_rect() -> Option<[i32; 4]> {
    // hidden off-screen: where it will come back
    if HIDDEN.load(Ordering::SeqCst) {
        if let Some(r) = *SAVED_POS.lock().unwrap() {
            return Some(r);
        }
    }
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::Foundation::RECT;
        use windows_sys::Win32::UI::WindowsAndMessaging::GetWindowRect;
        let h = hwnd();
        let mut r: RECT = std::mem::zeroed();
        if h != 0 && GetWindowRect(h as _, &mut r) != 0 {
            return Some([r.left, r.top, r.right - r.left, r.bottom - r.top]);
        }
    }
    None
}


/// Moving the window doesn't change what's in it, but egui redraws on every "moved" event
/// (hundreds per second while dragging). Pure moves skip egui and go straight to Windows.
#[cfg(windows)]
unsafe extern "system" fn no_redraw_on_move(
    hwnd: windows_sys::Win32::Foundation::HWND,
    msg: u32,
    wparam: windows_sys::Win32::Foundation::WPARAM,
    lparam: windows_sys::Win32::Foundation::LPARAM,
    _id: usize,
    _data: usize,
) -> windows_sys::Win32::Foundation::LRESULT {
    use windows_sys::Win32::UI::Shell::DefSubclassProc;
    if msg == WM_SPOX_SHOW {
        // SpoX was started again while already running: bring this one forward instead
        use windows_sys::Win32::UI::WindowsAndMessaging::{SetForegroundWindow, ShowWindow, SW_RESTORE, SW_SHOW};
        unhide(hwnd);
        ShowWindow(hwnd, SW_SHOW);
        ShowWindow(hwnd, SW_RESTORE);
        SetForegroundWindow(hwnd);
        return 0;
    }
    use windows_sys::Win32::UI::WindowsAndMessaging::{DefWindowProcW, SWP_HIDEWINDOW, SWP_NOSIZE, SWP_SHOWWINDOW, WINDOWPOS, WM_WINDOWPOSCHANGED};
    if msg == WM_WINDOWPOSCHANGED && lparam != 0 {
        let f = (*(lparam as *const WINDOWPOS)).flags;
        if f & SWP_NOSIZE != 0 && f & (SWP_SHOWWINDOW | SWP_HIDEWINDOW) == 0 {
            return DefWindowProcW(hwnd, msg, wparam, lparam);
        }
    }
    DefSubclassProc(hwnd, msg, wparam, lparam)
}

/// How often the main window must redraw right now (ms, 0 = only on events). A small thread
/// enforces it, so the visualizer, clock and lyrics never wait for a mouse move to wake egui up.
pub static PACE_MS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Same for the taskbar player.
pub static TB_PACE_MS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

pub fn start_pacer(ctx: egui::Context, pace: &'static std::sync::atomic::AtomicU64, vp: egui::ViewportId) {
    std::thread::Builder::new()
        .name("pacer".into())
        .spawn(move || loop {
            let ms = pace.load(Ordering::SeqCst);
            if ms == 0 {
                std::thread::sleep(std::time::Duration::from_millis(100));
                continue;
            }
            std::thread::sleep(std::time::Duration::from_millis(ms));
            ctx.request_repaint_of(vp);
        })
        .ok();
}

/// Window really not visible (hidden to tray or minimized), asked from Windows itself.
pub fn window_unseen() -> bool {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::UI::WindowsAndMessaging::{IsIconic, IsWindowVisible};
        let h = hwnd();
        if h != 0 {
            return HIDDEN.load(Ordering::SeqCst) || IsWindowVisible(h as _) == 0 || IsIconic(h as _) != 0;
        }
        return false;
    }
    #[cfg(not(windows))]
    HIDDEN.load(Ordering::SeqCst)
}


// ---------------- where the sound goes (for the little Bluetooth / car / headphones icon) ----------------

/// Default sound output: (kind, name). kind: "bt", "car", "wired" or "speaker".
pub static OUTPUT: Mutex<(String, String)> = Mutex::new((String::new(), String::new()));

/// Looks at Windows' default sound output every few seconds (cheap, on its own thread).
pub fn start_output_watch(ctx: egui::Context) {
    std::thread::Builder::new()
        .name("output-watch".into())
        .spawn(move || loop {
            use cpal::traits::{DeviceTrait, HostTrait};
            let name = cpal::default_host().default_output_device().and_then(|d| d.name().ok()).unwrap_or_default();
            let kind = output_kind(&name).to_string();
            let changed = {
                let mut o = OUTPUT.lock().unwrap();
                let c = o.1 != name;
                *o = (kind, name);
                c
            };
            if changed {
                ctx.request_repaint();
            }
            std::thread::sleep(std::time::Duration::from_secs(5));
        })
        .ok();
}

/// Guesses the kind of output from its Windows name, e.g. "Headphones (WH-1000XM4 Stereo)".
pub fn output_kind(name: &str) -> &'static str {
    let n = name.to_lowercase();
    let inner = n.split_once('(').map(|x| x.1).unwrap_or("");
    // whole words only ("audi" is in "audio")
    let words: Vec<&str> = n.split(|c: char| !c.is_alphanumeric()).filter(|w| !w.is_empty()).collect();
    let car_words = ["car", "auto", "carplay", "sync", "uconnect", "mylink", "bmw", "audi", "vw", "toyota", "mercedes", "ford", "kia", "hyundai", "skoda", "tesla"];
    let builtin = ["realtek", "high definition", "nvidia", "amd", "intel", "usb", "digital", "hdmi", "displayport", "virtual", "voicemeeter", "steam"];
    if words.iter().any(|w| car_words.contains(w)) {
        "car"
    } else if n.contains("hands-free") || n.contains("bluetooth") || n.contains("airpods") || n.contains("buds") {
        "bt"
    } else if (n.starts_with("headphones") || n.starts_with("headset")) && !builtin.iter().any(|k| inner.contains(k)) {
        // a named pair of headphones that isn't the PC's own sound chip: almost always Bluetooth
        "bt"
    } else if n.starts_with("headphones") || n.starts_with("headset") {
        "wired"
    } else {
        "speaker"
    }
}

#[cfg(test)]
mod output_tests {
    #[test]
    fn kinds() {
        assert_eq!(super::output_kind("Headphones (WH-1000XM4 Stereo)"), "bt");
        assert_eq!(super::output_kind("Headphones (Realtek(R) Audio)"), "wired");
        assert_eq!(super::output_kind("Speakers (Realtek(R) Audio)"), "speaker");
        assert_eq!(super::output_kind("Headset (Galaxy Buds2 Pro Hands-Free AG Audio)"), "bt");
    }
}

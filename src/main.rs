#![cfg_attr(windows, windows_subsystem = "windows")]

mod app;
mod art;
mod config;
mod desktop;
mod discord;
mod install;
mod lyrics;
mod net;
mod skin;
mod soloist;
mod smart;
mod state;
mod tb;
mod update;
mod vis;
mod web;

use eframe::egui;

fn main() -> eframe::Result<()> {
    // one SpoX at a time (Discord may start SpoX again when a friend clicks Join)
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS};
        use windows_sys::Win32::System::Threading::CreateMutexW;
        let name: Vec<u16> = "Local\\SpoX-one-instance".encode_utf16().chain(std::iter::once(0)).collect();
        // right after installing, the old copy is still closing: wait for it a little
        let tries = if std::env::args().any(|a| a == "--after-install") { 60 } else { 1 };
        let mut ok = false;
        for _ in 0..tries {
            let h = CreateMutexW(std::ptr::null(), 0, name.as_ptr());
            if h.is_null() || GetLastError() != ERROR_ALREADY_EXISTS {
                ok = true; // keep the handle open for as long as SpoX runs
                break;
            }
            CloseHandle(h);
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        if !ok && !std::env::args().any(|a| a == "--uninstall") {
            // already running: show that one (it may be hidden with no tray icon) and stop here
            use windows_sys::Win32::UI::WindowsAndMessaging::{FindWindowW, PostMessageW};
            let title: Vec<u16> = concat!("SpoX ", env!("CARGO_PKG_VERSION")).encode_utf16().chain(std::iter::once(0)).collect();
            let h = FindWindowW(std::ptr::null(), title.as_ptr());
            if !h.is_null() {
                PostMessageW(h, desktop::WM_SPOX_SHOW, 0, 0);
            }
            return Ok(());
        }
    }
    if std::env::args().any(|a| a == "--uninstall") {
        install::uninstall();
        return Ok(());
    }
    let cfg = config::Config::load();
    let scale = cfg.scale.clamp(1, 6) as f32;
    let skin_json: serde_json::Value = std::fs::read(skin::skin_dir().join("skin.json"))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or(serde_json::Value::Null);
    let dim = |k: &str, i: usize, d: f64| skin_json[k][i].as_f64().unwrap_or(d) as f32;
    let (mw, mh, ph) = (dim("main_size", 0, 292.0), dim("main_size", 1, 112.0), dim("panel_size", 1, 128.0));
    let mut vp = egui::ViewportBuilder::default()
        .with_title(concat!("SpoX ", env!("CARGO_PKG_VERSION")))
        .with_decorations(false)
        .with_resizable(false)
        .with_inner_size([mw * scale, (mh + if cfg.panel_open { ph } else { 0.0 }) * scale]);
    if let Some([x, y]) = cfg.window_pos {
        vp = vp.with_position([x, y]);
    }
    if let Some(img) = skin::read_image("icon.png") {
        // pixel-art icon: scale up with no smoothing (16x16 -> 64x64)
        let n = (64 / img.width().max(1)).max(1);
        let img = image::imageops::resize(&img, img.width() * n, img.height() * n, image::imageops::FilterType::Nearest);
        let (w, h) = img.dimensions();
        vp = vp.with_icon(egui::IconData { rgba: img.into_raw(), width: w, height: h });
    }
    let options = eframe::NativeOptions {
        viewport: vp,
        vsync: true,
        multisampling: 0,
        depth_buffer: 0,
        stencil_buffer: 0,
        centered: cfg.window_pos.is_none(),
        ..Default::default()
    };
    eframe::run_native("SpoX", options, Box::new(|cc| Ok(Box::new(app::App::new(cc)))))
}

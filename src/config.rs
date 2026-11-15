use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

/// Folder next to SpoX.exe.
pub fn exe_dir() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
}

/// Portable mode: a file called `portable.txt` next to the exe keeps everything in `./data`.
pub fn portable() -> bool {
    exe_dir().join("portable.txt").exists()
}

fn base(kind: fn() -> Option<PathBuf>) -> PathBuf {
    if portable() {
        return exe_dir().join("data");
    }
    kind().unwrap_or_else(|| exe_dir().join("data")).join("SpoX")
}

pub fn config_dir() -> PathBuf {
    base(dirs::config_dir)
}
pub fn cache_dir() -> PathBuf {
    let p = base(dirs::cache_dir);
    if portable() { p.join("cache") } else { p }
}
pub fn soloist_dir() -> PathBuf {
    base(dirs::data_local_dir).join("soloist")
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(default)]
pub struct Config {
    pub soloist_key: String,
    pub client_id: String,
    pub device_name: String,
    pub access_token: String,
    pub refresh_token: String,
    pub token_expires: i64,
    pub user_id: String,
    pub scale: u32,
    pub always_on_top: bool,
    pub panel_open: bool,
    pub tab: String,
    pub art_mode: String,
    pub vis_on: bool,
    pub show_remaining: bool,
    pub last_playlist: String,
    pub window_pos: Option<[f32; 2]>,
    pub soloist_downloaded: i64,
    pub tray: bool,
    pub pinned: [bool; 3],
    pub scopes: String,
    pub last_add_playlist: String,
    pub on_close: String,
    pub tb_on: bool,
    pub tb_text_w: u32,
    pub tb_idle: String,
    pub tb_pos: String,
    pub tb_offset: i32,
    pub tb_hide_fullscreen: bool,
    pub tb_click_art: String,
    pub tb_click_text: String,
    pub tb_dbl_text: String,
    pub tb_middle: String,
    pub tb_right: String,
    pub tb_wheel: String,
    pub start_with_windows: bool,
    pub start_in_tray: bool,
    pub tooltips: bool,
    pub uppercase: bool,
    pub show_profile: bool,
    pub art_colors: u32,
    pub smart_ratio: u32,
    pub smart_avoid_artist: bool,
    pub lastfm_key: String,
    pub pinned_history: bool,
    pub tb_paused_dim: bool,
    pub tb_monitor: String,
    pub tb_fullscreen: String,
    pub tb_volume_bar: bool,
    pub tb_show_when: String,
    pub tb_left_offset: i32,
    pub collapse_anim: String,
    pub color_pack: String,
    pub discord: bool,
    pub tb_lines: String,
    pub tb_shuffle_btn: bool,
    pub vis_style: String,
    pub dev_mode: bool,
    pub discord_join: bool,
    pub auto_update: bool,
    pub hide_tray_with_player: bool,
    pub color_sat: i32,
    pub grey_tint: String,
    pub grey_light: i32,
    pub bevel: String,
    pub text_shadow: bool,
    pub text_style: String,
    pub color_over: std::collections::BTreeMap<String, String>,
    #[serde(default = "zero")]
    pub cfg_version: u32,
    pub color_hue: i32,
    pub installed_asked: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            soloist_key: String::new(),
            client_id: String::new(),
            device_name: "SpoX".into(),
            access_token: String::new(),
            refresh_token: String::new(),
            token_expires: 0,
            user_id: String::new(),
            scale: 2,
            always_on_top: false,
            panel_open: true,
            tab: "playlist".into(),
            art_mode: "pixel".into(),
            vis_on: true,
            show_remaining: false,
            last_playlist: String::new(),
            window_pos: None,
            soloist_downloaded: 0,
            tray: true,
            pinned: [false; 3],
            scopes: String::new(),
            last_add_playlist: String::new(),
            on_close: "quit".into(),
            tb_on: true,
            tb_text_w: 96,
            tb_idle: "dim".into(),
            tb_pos: "left".into(),
            tb_offset: 300,
            tb_hide_fullscreen: true,
            tb_click_art: "toggle_main".into(),
            tb_click_text: "toggle_main".into(),
            tb_dbl_text: "play_pause".into(),
            tb_middle: "copy_link".into(),
            tb_right: "like".into(),
            tb_wheel: "volume".into(),
            start_with_windows: false,
            start_in_tray: false,
            tooltips: true,
            uppercase: false,
            show_profile: true,
            art_colors: 10,
            smart_ratio: 50,
            smart_avoid_artist: true,
            lastfm_key: String::new(),
            pinned_history: false,
            tb_paused_dim: true,
            tb_monitor: "main".into(),
            tb_fullscreen: "other".into(),
            tb_volume_bar: false,
            tb_show_when: "always".into(),
            tb_left_offset: 8,
            collapse_anim: "tv".into(),
            color_pack: "Default".into(),
            discord: false,
            tb_lines: "auto".into(),
            tb_shuffle_btn: true,
            vis_style: "bars".into(),
            dev_mode: false,
            discord_join: true,
            auto_update: true,
            hide_tray_with_player: true,
            color_sat: 100,
            grey_tint: "none".into(),
            grey_light: 100,
            bevel: "normal".into(),
            text_shadow: false,
            text_style: "normal".into(),
            color_over: Default::default(),
            cfg_version: 6,
            color_hue: 0,
            installed_asked: false,
        }
    }
}

pub type Shared = Arc<Mutex<Config>>;

impl Config {
    fn path() -> PathBuf {
        config_dir().join("config.json")
    }
    pub fn load() -> Self {
        std::fs::read(Self::path())
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .map(|mut c: Config| {
                // v0.5 migration: player moves to the left side, fullscreen shows info only
                if c.cfg_version < 5 {
                    c.cfg_version = 5;
                    if c.tb_pos == "auto" {
                        c.tb_pos = "left".into();
                    }
                    c.tb_fullscreen = if c.tb_hide_fullscreen { "info".into() } else { "normal".into() };
                }
                // v0.5.7: fullscreen app = hop to the other monitor (falls back to info with one monitor)
                if c.cfg_version < 6 {
                    c.cfg_version = 6;
                    if c.tb_fullscreen == "info" {
                        c.tb_fullscreen = "other".into();
                    }
                }
                if c.collapse_anim == "fly" {
                    c.collapse_anim = "tv".into();
                }
                c
            })
            .unwrap_or_default()
    }
    pub fn save(&self) {
        let p = Self::path();
        if let Some(d) = p.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        if let Ok(s) = serde_json::to_vec_pretty(self) {
            let tmp = p.with_extension("json.tmp");
            if std::fs::write(&tmp, s).is_ok() {
                let _ = std::fs::rename(&tmp, &p);
            }
        }
    }
}

pub fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn zero() -> u32 {
    0
}

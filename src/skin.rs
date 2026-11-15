//! Loads the skin: PNGs + skin.json from the `skin` folder next to SpoX.exe,
//! falling back to the built-in copy for anything missing.

use eframe::egui::{self, Color32, Pos2, Rect, TextureHandle, TextureOptions, Vec2};
use include_dir::{include_dir, Dir};
use serde_json::Value;
use std::collections::HashMap;
use std::path::PathBuf;

static BUILTIN: Dir = include_dir!("$CARGO_MANIFEST_DIR/skin");

pub fn skin_dir() -> PathBuf {
    crate::config::exe_dir().join("skin")
}

/// Writes the built-in skin to disk (only files that don't exist yet), so it can be edited.
pub fn export_builtin() -> PathBuf {
    let dir = skin_dir();
    fn walk(d: &Dir, base: &std::path::Path) {
        for f in d.files() {
            let p = base.join(f.path());
            if !p.exists() {
                if let Some(parent) = p.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                let _ = std::fs::write(&p, f.contents());
            }
        }
        for sub in d.dirs() {
            walk(sub, base);
        }
    }
    walk(&BUILTIN, &dir);
    dir
}

fn read(name: &str) -> Option<Vec<u8>> {
    std::fs::read(skin_dir().join(name))
        .ok()
        .or_else(|| BUILTIN.get_file(name).map(|f| f.contents().to_vec()))
}

/// Reads a skin PNG (your skin folder first, then the built-in one).
pub fn read_image(name: &str) -> Option<image::RgbaImage> {
    read(name).and_then(|b| decode(&b))
}

fn decode(bytes: &[u8]) -> Option<image::RgbaImage> {
    image::load_from_memory(bytes).ok().map(|i| i.to_rgba8())
}

pub struct Sprite {
    pub tex: TextureHandle,
    pub w: f32,
    pub h: f32,
}

pub struct Font {
    tex: TextureHandle,
    sheet: Vec2,
    cell_w: f32,
    cell_h: f32,
    first: u32,
    cols: u32,
    top: f32,
    pub space: f32,
    pub spacing: f32,
    pub line_h: f32,
    widths: Vec<u8>,
    /// 1 px drop shadow under every letter (style lab)
    pub shadow: Option<Color32>,
}

pub struct Skin {
    sprites: HashMap<String, Sprite>,
    pub font: Option<Font>,
    pub vis_colors: Vec<Color32>,
    pub json: Value,
}

pub fn parse_color(s: &str) -> Option<Color32> {
    let h = s.trim().trim_start_matches('#');
    let v = u32::from_str_radix(h, 16).ok()?;
    match h.len() {
        6 => Some(Color32::from_rgb((v >> 16) as u8, (v >> 8) as u8, v as u8)),
        8 => Some(Color32::from_rgba_unmultiplied((v >> 24) as u8, (v >> 16) as u8, (v >> 8) as u8, v as u8)),
        _ => None,
    }
}

impl Skin {
    pub fn load(ctx: &egui::Context) -> Self {
        let json: Value = read("skin.json")
            .and_then(|b| serde_json::from_slice(&b).ok())
            .or_else(|| BUILTIN.get_file("skin.json").and_then(|f| serde_json::from_slice(f.contents()).ok()))
            .unwrap_or(Value::Null);
        let mut sprites = HashMap::new();
        let mut names: Vec<String> = BUILTIN
            .files()
            .filter_map(|f| f.path().to_str().map(|s| s.to_string()))
            .collect();
        if let Ok(rd) = std::fs::read_dir(skin_dir()) {
            for e in rd.flatten() {
                if let Some(n) = e.file_name().to_str() {
                    if !names.iter().any(|x| x == n) {
                        names.push(n.to_string());
                    }
                }
            }
        }
        let cfg = crate::config::Config::load();
        let pack = Pack::from_cfg(&cfg);
        let mut json = json;
        if let Some(cols) = json.get_mut("colors").and_then(|c| c.as_object_mut()) {
            for (k, v) in cols.iter_mut() {
                if let Some(c) = v.as_str().and_then(parse_color) {
                    let [r, g, b, _] = pack.apply([c.r(), c.g(), c.b(), 255]);
                    let (r, g, b) = text_style(&cfg.text_style, k, r, g, b);
                    *v = Value::String(format!("#{:02X}{:02X}{:02X}", r, g, b));
                }
                // your own picks from the colour editor win over everything
                if let Some(o) = cfg.color_over.get(k.as_str()) {
                    *v = Value::String(o.clone());
                }
            }
        }
        for n in names {
            let Some(stem) = n.strip_suffix(".png") else { continue };
            if stem == "font" || stem == "vis_colors" {
                continue;
            }
            if let Some(mut img) = read(&n).and_then(|b| decode(&b)) {
                if !pack.is_identity() && stem != "icon" {
                    pack.recolor(&mut img);
                }
                let (w, h) = img.dimensions();
                let ci = egui::ColorImage::from_rgba_unmultiplied([w as usize, h as usize], img.as_raw());
                let tex = ctx.load_texture(stem, ci, TextureOptions::NEAREST);
                sprites.insert(stem.to_string(), Sprite { tex, w: w as f32, h: h as f32 });
            }
        }
        let vis_colors = read("vis_colors.png")
            .and_then(|b| decode(&b))
            .map(|mut i| {
                pack.recolor(&mut i);
                i
            })
            .map(|img| (0..img.height()).map(|y| {
                let p = img.get_pixel(0, y);
                Color32::from_rgba_unmultiplied(p[0], p[1], p[2], p[3])
            }).collect())
            .unwrap_or_else(|| vec![Color32::from_rgb(91, 227, 142)]);
        let font = read("font.png").and_then(|b| decode(&b)).map(|img| {
            let f = &json["font"];
            let num = |k: &str, d: f64| f[k].as_f64().unwrap_or(d);
            let cell_w = f["cell"][0].as_u64().unwrap_or(8) as u32;
            let cell_h = f["cell"][1].as_u64().unwrap_or(16) as u32;
            let cols = num("cols", 16.0) as u32;
            let rows = img.height() / cell_h.max(1);
            let mut widths = Vec::new();
            for gi in 0..cols * rows {
                let (cx, cy) = ((gi % cols) * cell_w, (gi / cols) * cell_h);
                let mut w = 0u8;
                for x in 0..cell_w {
                    for y in 0..cell_h {
                        if cx + x < img.width() && cy + y < img.height() && img.get_pixel(cx + x, cy + y)[3] > 0 {
                            w = w.max(x as u8 + 1);
                        }
                    }
                }
                widths.push(w);
            }
            let (w, h) = img.dimensions();
            let ci = egui::ColorImage::from_rgba_unmultiplied([w as usize, h as usize], img.as_raw());
            Font {
                tex: ctx.load_texture("font", ci, TextureOptions::NEAREST),
                sheet: Vec2::new(w as f32, h as f32),
                cell_w: cell_w as f32,
                cell_h: cell_h as f32,
                first: num("first", 32.0) as u32,
                cols,
                top: num("top_rows", 2.0) as f32,
                space: num("space", 4.0) as f32,
                spacing: num("spacing", 1.0) as f32,
                line_h: num("line_height", 10.0) as f32,
                widths,
                shadow: cfg.text_shadow.then(|| json["colors"]["text_shadow"].as_str().and_then(parse_color).unwrap_or(Color32::from_rgba_unmultiplied(0, 0, 0, 170))),
            }
        });
        Self { sprites, font, vis_colors, json }
    }

    pub fn sprite(&self, name: &str) -> Option<&Sprite> {
        self.sprites.get(name)
    }
    pub fn size(&self, name: &str) -> (f32, f32) {
        self.sprites.get(name).map(|s| (s.w, s.h)).unwrap_or((0.0, 0.0))
    }
    pub fn pos(&self, name: &str) -> (f32, f32) {
        let p = &self.json["pos"][name];
        (p[0].as_f64().unwrap_or(0.0) as f32, p[1].as_f64().unwrap_or(0.0) as f32)
    }
    pub fn rect(&self, name: &str) -> [f32; 4] {
        let p = &self.json["rect"][name];
        [0, 1, 2, 3].map(|i| p[i].as_f64().unwrap_or(0.0) as f32)
    }
    pub fn color(&self, name: &str) -> Color32 {
        self.json["colors"][name].as_str().and_then(parse_color).unwrap_or(Color32::from_rgb(91, 227, 142))
    }
    pub fn num(&self, path: &str, d: f64) -> f64 {
        self.json.pointer(path).and_then(|v| v.as_f64()).unwrap_or(d)
    }
    pub fn flag(&self, key: &str, d: bool) -> bool {
        self.json[key].as_bool().unwrap_or(d)
    }
    pub fn main_size(&self) -> (f32, f32) {
        let m = &self.json["main_size"];
        (m[0].as_f64().unwrap_or(288.0) as f32, m[1].as_f64().unwrap_or(112.0) as f32)
    }
    pub fn panel_size(&self) -> (f32, f32) {
        let m = &self.json["panel_size"];
        (m[0].as_f64().unwrap_or(288.0) as f32, m[1].as_f64().unwrap_or(128.0) as f32)
    }
}

impl Font {
    fn index(&self, c: char) -> Option<usize> {
        let cp = c as u32;
        if cp < self.first {
            return None;
        }
        let i = (cp - self.first) as usize;
        (i < self.widths.len() && self.widths[i] > 0).then_some(i)
    }

    /// Maps a char to the glyphs we can draw (typographic quotes, dashes, ellipsis...).
    fn glyphs(&self, c: char, out: &mut Vec<Option<usize>>) {
        if c == ' ' || c == '\u{a0}' || c == '\t' {
            out.push(None);
            return;
        }
        if let Some(i) = self.index(c) {
            out.push(Some(i));
            return;
        }
        let sub: &str = match c {
            '\u{2018}' | '\u{2019}' | '\u{201a}' | '\u{2032}' => "'",
            '\u{201c}' | '\u{201d}' | '\u{201e}' => "\"",
            '\u{2010}'..='\u{2015}' | '\u{2212}' => "-",
            '\u{2026}' => "...",
            '\u{2022}' | '\u{2027}' => "\u{b7}",
            '\u{25be}' | '\u{25bc}' => "v",
            _ => "?",
        };
        for s in sub.chars() {
            out.push(self.index(s).or(self.index('?')));
        }
    }

    pub fn width(&self, s: &str) -> f32 {
        let mut g = Vec::new();
        for c in s.chars() {
            self.glyphs(c, &mut g);
        }
        let mut w = 0.0;
        for gi in &g {
            w += match gi {
                None => self.space,
                Some(i) => self.widths[*i] as f32 + self.spacing,
            };
        }
        if matches!(g.last(), Some(Some(_))) {
            w -= self.spacing;
        }
        w.max(0.0)
    }

    /// Draws text with its cap-height top at (x, y) in skin pixels. Returns the width in skin pixels.
    pub fn draw(&self, p: &egui::Painter, o: Pos2, s: f32, x: f32, y: f32, text: &str, col: Color32) -> f32 {
        if let Some(sh) = self.shadow {
            self.draw_raw(p, o, s, x + 1.0, y + 1.0, text, sh.gamma_multiply(col.a() as f32 / 255.0));
        }
        self.draw_raw(p, o, s, x, y, text, col)
    }

    fn draw_raw(&self, p: &egui::Painter, o: Pos2, s: f32, x: f32, y: f32, text: &str, col: Color32) -> f32 {
        let mut g = Vec::new();
        for c in text.chars() {
            self.glyphs(c, &mut g);
        }
        let mut cx = x;
        for gi in g {
            match gi {
                None => cx += self.space,
                Some(i) => {
                    let (gx, gy) = ((i as u32 % self.cols) as f32 * self.cell_w, (i as u32 / self.cols) as f32 * self.cell_h);
                    let rect = Rect::from_min_size(
                        Pos2::new(o.x + cx * s, o.y + (y - self.top) * s),
                        Vec2::new(self.cell_w * s, self.cell_h * s),
                    );
                    let uv = Rect::from_min_max(
                        Pos2::new(gx / self.sheet.x, gy / self.sheet.y),
                        Pos2::new((gx + self.cell_w) / self.sheet.x, (gy + self.cell_h) / self.sheet.y),
                    );
                    p.image(self.tex.id(), rect, uv, col);
                    cx += self.widths[i] as f32 + self.spacing;
                }
            }
        }
        cx - x
    }

    /// Cuts text to fit `max` pixels, adding ".." when it was cut.
    pub fn fit(&self, s: &str, max: f32) -> String {
        if self.width(s) <= max {
            return s.to_string();
        }
        let mut out: String = s.to_string();
        while !out.is_empty() && self.width(&format!("{}..", out)) > max {
            out.pop();
        }
        format!("{}..", out.trim_end())
    }

    /// Word-wraps into lines no wider than `max` pixels.
    pub fn wrap(&self, s: &str, max: f32) -> Vec<String> {
        let mut lines = Vec::new();
        let mut cur = String::new();
        for word in s.split(' ') {
            let cand = if cur.is_empty() { word.to_string() } else { format!("{} {}", cur, word) };
            if self.width(&cand) <= max || cur.is_empty() {
                cur = cand;
            } else {
                lines.push(std::mem::take(&mut cur));
                cur = word.to_string();
            }
            while self.width(&cur) > max && cur.chars().count() > 1 {
                // a single word longer than a line: hard-break it
                let mut head = String::new();
                for ch in cur.chars() {
                    if self.width(&format!("{}{}", head, ch)) > max {
                        break;
                    }
                    head.push(ch);
                }
                if head.is_empty() {
                    break;
                }
                let tail = cur[head.len()..].to_string();
                lines.push(head);
                cur = tail;
            }
        }
        lines.push(cur);
        lines
    }
}

// ---- colour packs ------------------------------------------------------------------------
// A pack recolours the whole skin at load time: accent (coloured) pixels get their hue turned
// and saturation scaled, greys stay as drawn. Exact colour swaps ("map") win over the hue turn.
// Packs live in skin/packs/<name>.json: {"hue": -100, "sat": 1.0, "map": {"#5BE38E": "#F2B84B"}}

pub struct Pack {
    hue: f32,
    sat: f32,
    /// greys get tinted toward this colour (strength 0..1)
    tint: [f32; 3],
    tint_k: f32,
    /// grey brightness multiplier
    light: f32,
    map: HashMap<[u8; 3], [u8; 3]>,
}

/// name, hue turn, saturation, grey tint colour, tint strength, grey brightness
const BUILTIN_PACKS: [(&str, f32, f32, [u8; 3], f32, f32); 12] = [
    ("Default", 0.0, 1.0, [0, 0, 0], 0.0, 1.0),
    ("Amber CRT", -100.0, 1.0, [255, 170, 60], 0.12, 0.95),
    ("Ice", 55.0, 0.9, [120, 190, 255], 0.10, 1.0),
    ("Rose", -160.0, 0.9, [255, 120, 160], 0.08, 1.0),
    ("Lime", -55.0, 1.0, [0, 0, 0], 0.0, 1.0),
    ("Mono", 0.0, 0.0, [0, 0, 0], 0.0, 1.0),
    ("Game Boy", -60.0, 0.55, [150, 170, 40], 0.35, 1.05),
    ("Vapor", 160.0, 1.0, [200, 80, 255], 0.14, 1.0),
    ("Blood", -140.0, 1.0, [255, 30, 30], 0.10, 0.85),
    ("Ocean", 70.0, 1.0, [40, 120, 200], 0.16, 0.95),
    ("Sunset", -120.0, 1.1, [255, 110, 60], 0.12, 1.0),
    ("Paper", 0.0, 0.8, [255, 240, 210], 0.10, 1.6),
];

/// Swatches for the colour editor (style lab).
pub const SWATCHES: [&str; 16] = [
    "#5BE38E", "#3E9A65", "#9FD9B4", "#E9F27A", "#F2B84B", "#FF7A59", "#FF5C7A", "#E07BFF", "#7AA2F7", "#5ED6E6", "#FFFFFF", "#C3C6CC", "#8A8D94", "#5A5D62", "#4F7A60", "#1C3527",
];

/// Colours the editor lets you change, with friendly names.
pub const EDITABLE: [(&str, &str); 8] = [
    ("title", "Song title"),
    ("info", "Artist / info"),
    ("digits", "Clock"),
    ("list", "List text"),
    ("list_current", "Playing row"),
    ("list_dim", "Dim text"),
    ("lyric_current", "Lyric line"),
    ("menu_text", "Menu text"),
];

pub const TINTS: [(&str, [u8; 3]); 6] = [("none", [0, 0, 0]), ("warm", [255, 170, 90]), ("cool", [110, 170, 255]), ("green", [90, 230, 140]), ("purple", [190, 110, 255]), ("red", [255, 70, 70])];

/// Bright / soft text, applied to the text colours only.
fn text_style(style: &str, key: &str, r: u8, g: u8, b: u8) -> (u8, u8, u8) {
    let is_text = !(key.ends_with("_bg") || key.contains("hover") || key.contains("selected") || key.starts_with("vis") || key == "anim_bg" || key == "text_shadow");
    if !is_text {
        return (r, g, b);
    }
    let f = |v: u8, k: f32, toward: f32| (v as f32 + (toward - v as f32) * k).round().clamp(0.0, 255.0) as u8;
    match style {
        "bright" => (f(r, 0.25, 255.0), f(g, 0.25, 255.0), f(b, 0.25, 255.0)),
        "soft" => {
            let l = (r as f32 + g as f32 + b as f32) / 3.0;
            (f(r, 0.3, l), f(g, 0.3, l), f(b, 0.3, l))
        }
        _ => (r, g, b),
    }
}

fn packs_dir() -> PathBuf {
    skin_dir().join("packs")
}

pub fn pack_names() -> Vec<String> {
    let mut v: Vec<String> = BUILTIN_PACKS.iter().map(|p| p.0.to_string()).collect();
    if let Ok(rd) = std::fs::read_dir(packs_dir()) {
        let mut extra: Vec<String> = rd.flatten().filter_map(|e| e.file_name().to_str().and_then(|n| n.strip_suffix(".json")).map(|s| s.to_string())).collect();
        extra.sort();
        v.extend(extra);
    }
    v
}


fn hex(c: [u8; 3]) -> String {
    format!("#{:02X}{:02X}{:02X}", c[0], c[1], c[2])
}

/// Saves everything you set in the style lab as a new pack file. Returns its name.
pub fn save_pack(cfg: &crate::config::Config) -> Option<String> {
    let p = Pack::from_cfg(cfg);
    let _ = std::fs::create_dir_all(packs_dir());
    let n = (1..1000).map(|i| format!("Custom {}", i)).find(|n| !packs_dir().join(format!("{}.json", n)).exists())?;
    let map: serde_json::Map<String, Value> = p.map.iter().map(|(k, v)| (hex(*k), Value::String(hex(*v)))).collect();
    let colors: serde_json::Map<String, Value> = cfg.color_over.iter().map(|(k, v)| (k.clone(), Value::String(v.clone()))).collect();
    let j = serde_json::json!({
        "hue": p.hue, "sat": p.sat, "tint": hex([p.tint[0] as u8, p.tint[1] as u8, p.tint[2] as u8]), "tint_strength": p.tint_k,
        "light": p.light, "map": map, "colors": colors,
    });
    std::fs::write(packs_dir().join(format!("{}.json", n)), serde_json::to_vec_pretty(&j).ok()?).ok()?;
    Some(n)
}

/// Colour overrides stored in a pack file (from the colour editor).
pub fn pack_colors(name: &str) -> std::collections::BTreeMap<String, String> {
    std::fs::read(packs_dir().join(format!("{}.json", name)))
        .ok()
        .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
        .and_then(|j| j["colors"].as_object().map(|m| m.iter().filter_map(|(k, v)| Some((k.clone(), v.as_str()?.to_string()))).collect()))
        .unwrap_or_default()
}

impl Pack {
    pub fn from_cfg(cfg: &crate::config::Config) -> Self {
        let mut p = Pack { hue: 0.0, sat: 1.0, tint: [0.0; 3], tint_k: 0.0, light: 1.0, map: HashMap::new() };
        let name = cfg.color_pack.as_str();
        if let Some(b) = BUILTIN_PACKS.iter().find(|b| b.0 == name || (name == "Amber" && b.0 == "Amber CRT")) {
            p.hue = b.1;
            p.sat = b.2;
            p.tint = [b.3[0] as f32, b.3[1] as f32, b.3[2] as f32];
            p.tint_k = b.4;
            p.light = b.5;
        } else if let Some(j) = std::fs::read(packs_dir().join(format!("{}.json", name))).ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok()) {
            p.hue = j["hue"].as_f64().unwrap_or(0.0) as f32;
            p.sat = j["sat"].as_f64().unwrap_or(1.0) as f32;
            if let Some(t) = j["tint"].as_str().and_then(parse_color) {
                p.tint = [t.r() as f32, t.g() as f32, t.b() as f32];
            }
            p.tint_k = j["tint_strength"].as_f64().unwrap_or(0.0) as f32;
            p.light = j["light"].as_f64().unwrap_or(1.0) as f32;
            if let Some(m) = j["map"].as_object() {
                for (k, v) in m {
                    if let (Some(a), Some(b)) = (parse_color(k), v.as_str().and_then(parse_color)) {
                        p.map.insert([a.r(), a.g(), a.b()], [b.r(), b.g(), b.b()]);
                    }
                }
            }
        }
        // style lab on top of the pack
        p.hue += cfg.color_hue as f32;
        p.sat *= cfg.color_sat as f32 / 100.0;
        if let Some((_, t)) = TINTS.iter().find(|t| t.0 == cfg.grey_tint && t.0 != "none") {
            p.tint = [t[0] as f32, t[1] as f32, t[2] as f32];
            p.tint_k = p.tint_k.max(0.14);
        }
        p.light *= cfg.grey_light as f32 / 100.0;
        // button bevels (the two edge colours of the button art)
        let (hi, lo, fill) = ([88u8, 91, 99], [31u8, 33, 35], [52u8, 54, 59]);
        let mix = |a: [u8; 3], b: [u8; 3], k: f32| [0, 1, 2].map(|i| (a[i] as f32 + (b[i] as f32 - a[i] as f32) * k).round() as u8);
        match cfg.bevel.as_str() {
            "flat" => {
                p.map.entry(hi).or_insert(fill);
                p.map.entry(lo).or_insert(fill);
            }
            "soft" => {
                p.map.entry(hi).or_insert(mix(hi, fill, 0.5));
                p.map.entry(lo).or_insert(mix(lo, fill, 0.5));
            }
            "strong" => {
                p.map.entry(hi).or_insert(mix(hi, [255, 255, 255], 0.3));
                p.map.entry(lo).or_insert(mix(lo, [0, 0, 0], 0.5));
            }
            _ => {}
        }
        // mapped colours still go through the tint/brightness below
        p
    }
    pub fn is_identity(&self) -> bool {
        self.hue.rem_euclid(360.0) == 0.0 && self.sat == 1.0 && self.map.is_empty() && self.tint_k == 0.0 && self.light == 1.0
    }
    pub fn apply(&self, px: [u8; 4]) -> [u8; 4] {
        let px = match self.map.get(&[px[0], px[1], px[2]]) {
            Some(m) => [m[0], m[1], m[2], px[3]],
            None => px,
        };
        let (r, g, b) = (px[0] as f32 / 255.0, px[1] as f32 / 255.0, px[2] as f32 / 255.0);
        let mx = r.max(g).max(b);
        let mn = r.min(g).min(b);
        let d = mx - mn;
        let sat = if mx > 0.0 { d / mx } else { 0.0 };
        if sat < 0.22 || d < 0.06 {
            // greys keep their shape; only the style lab's tint / brightness touch them
            if self.tint_k == 0.0 && self.light == 1.0 {
                return px;
            }
            let q = |v: u8, t: f32| {
                let v = v as f32 * self.light;
                (v + (t * (v / 255.0).max(0.25) - v) * self.tint_k).round().clamp(0.0, 255.0) as u8
            };
            return [q(px[0], self.tint[0]), q(px[1], self.tint[1]), q(px[2], self.tint[2]), px[3]];
        }
        let mut h = if mx == r { 60.0 * (((g - b) / d).rem_euclid(6.0)) } else if mx == g { 60.0 * ((b - r) / d + 2.0) } else { 60.0 * ((r - g) / d + 4.0) };
        h = (h + self.hue).rem_euclid(360.0);
        let s2 = (sat * self.sat).clamp(0.0, 1.0);
        let v = mx;
        let c = v * s2;
        let x = c * (1.0 - ((h / 60.0).rem_euclid(2.0) - 1.0).abs());
        let m = v - c;
        let (r1, g1, b1) = match (h / 60.0) as u32 {
            0 => (c, x, 0.0),
            1 => (x, c, 0.0),
            2 => (0.0, c, x),
            3 => (0.0, x, c),
            4 => (x, 0.0, c),
            _ => (c, 0.0, x),
        };
        let q = |f: f32| ((f + m) * 255.0).round().clamp(0.0, 255.0) as u8;
        [q(r1), q(g1), q(b1), px[3]]
    }
    pub fn recolor(&self, img: &mut image::RgbaImage) {
        if self.is_identity() {
            return;
        }
        for p in img.pixels_mut() {
            p.0 = self.apply(p.0);
        }
    }
}

use serde_json::Value;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Track {
    pub uri: String,
    pub name: String,
    pub artists: String,
    pub album: String,
    pub duration_ms: u64,
    pub cover_small: Option<String>,
    pub cover_large: Option<String>,
    /// where a queued item comes from: "queue", "context", "autoplay"... (empty if unknown)
    pub provider: String,
    /// when it was added to the playlist / Liked Songs (unix seconds, 0 = unknown)
    pub added: i64,
    /// its place in the playlist's own order
    pub order: u32,
}

#[derive(Clone, Debug, Default)]
pub struct Playlist {
    pub uri: String,
    pub id: String,
    pub name: String,
    pub total: u32,
    pub owned: bool,
}

#[derive(Clone, Debug, Default)]
pub struct Lyrics {
    pub synced: bool,
    pub lines: Vec<(u64, String)>,
}

/// Everything the background threads send to the UI.
pub enum Ev {
    Status(String),
    SoloistMsg(Value),
    SoloistConnected(bool),
    Playlists(Vec<Playlist>),
    Tracks { playlist: String, tracks: Vec<Track>, listable: bool, done: bool },
    Lyrics { uri: String, lyrics: Option<Lyrics> },
    Art { url: String, img: eframe::egui::ColorImage },
    SignedIn(bool),
    /// short on-screen message (title line), e.g. "ADDED TO GYM MIX"
    Osd(String),
    Profile { name: String, img: Option<eframe::egui::ColorImage>, small: Option<eframe::egui::ColorImage> },
    Liked { uri: String, liked: bool },
    /// a song was added to a playlist (so the add menu can mark it)
    Added { playlist: String, uri: String },
    Removed { playlist: String, uri: String, key: String },
    UpdateReady(String),
    /// SpoX shuffle state: on/off, shuffled or in your order, the list's name, songs it picked as
    /// "new", and what's coming (the whole rest of its order: (song, kind "list"/"new"/"user"))
    Smart { on: bool, shuffled: bool, list: String, new_uris: Vec<String>, upcoming: Vec<(Track, String)>, busy: String },
}

#[derive(Clone, Debug)]
pub struct Play {
    pub logged_in: bool,
    pub is_active: bool,
    pub device_name: String,
    pub status: String,
    pub track: Option<Track>,
    pub context_uri: String,
    pub context_name: String,
    pub pos_ms: f64,
    pub pos_ts: i64,
    pub speed: f64,
    pub volume: u8,
    pub shuffle: bool,
    pub repeat: String,
    pub upcoming: Vec<Track>,
}

impl Default for Play {
    fn default() -> Self {
        Self {
            logged_in: false,
            is_active: false,
            device_name: String::new(),
            status: "idle".into(),
            track: None,
            context_uri: String::new(),
            context_name: String::new(),
            pos_ms: 0.0,
            pos_ts: 0,
            speed: 1.0,
            volume: 50,
            shuffle: false,
            repeat: "off".into(),
            upcoming: Vec::new(),
        }
    }
}

fn name_of(v: &Value) -> String {
    v.pointer("/decorations/identity/name").and_then(|x| x.as_str()).unwrap_or("").to_string()
}

pub fn parse_entity(v: &Value) -> Option<Track> {
    let uri = v.get("uri")?.as_str()?.to_string();
    let d = v.get("decorations");
    let artists = d
        .and_then(|d| d.get("creators"))
        .and_then(|c| c.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|c| c.get("entity").map(name_of))
                .filter(|s| !s.is_empty())
                .collect::<Vec<_>>()
                .join(", ")
        })
        .unwrap_or_default();
    let album = d
        .and_then(|d| d.pointer("/parent/entity"))
        .map(name_of)
        .unwrap_or_default();
    let duration_ms = d
        .and_then(|d| d.pointer("/playback/duration_ms"))
        .and_then(|x| x.as_u64())
        .unwrap_or(0);
    let mut small = None;
    let mut large = None;
    if let Some(covers) = d.and_then(|d| d.pointer("/visual_identity/cover")).and_then(|c| c.as_array()) {
        let pick = |want: &[&str]| -> Option<String> {
            for w in want {
                for c in covers {
                    if c.get("size").and_then(|s| s.as_str()) == Some(w) {
                        if let Some(u) = c.get("url").and_then(|u| u.as_str()) {
                            return Some(u.to_string());
                        }
                    }
                }
            }
            covers.first().and_then(|c| c.get("url")).and_then(|u| u.as_str()).map(|s| s.to_string())
        };
        small = pick(&["small", "default", "large", "xlarge"]);
        large = pick(&["default", "large", "small", "xlarge"]);
    }
    Some(Track { uri, name: name_of(v), artists, album, duration_ms, cover_small: small, cover_large: large, ..Default::default() })
}

impl Play {
    pub fn position(&self, now_ms: i64) -> f64 {
        let mut p = self.pos_ms;
        if self.status == "playing" && self.pos_ts > 0 {
            p += (now_ms - self.pos_ts).max(0) as f64 * self.speed;
        }
        if let Some(t) = &self.track {
            if t.duration_ms > 0 {
                p = p.min(t.duration_ms as f64);
            }
        }
        p.max(0.0)
    }

    fn set_pos(&mut self, v: &Value) {
        if let Some(p) = v.get("position_ms").and_then(|x| x.as_f64()) {
            self.pos_ms = p;
        }
        self.pos_ts = v.get("timestamp_ms").and_then(|x| x.as_i64()).unwrap_or_else(crate::config::now_ms);
        self.speed = v.get("speed").and_then(|x| x.as_f64()).unwrap_or(1.0);
    }

    fn set_options(&mut self, o: &Value) {
        if let Some(s) = o.get("shuffle").and_then(|x| x.as_bool()) {
            self.shuffle = s;
        }
        if let Some(r) = o.get("repeat").and_then(|x| x.as_str()) {
            self.repeat = r.to_string();
        }
    }

    fn set_context(&mut self, c: &Value) {
        self.context_uri = c.get("uri").and_then(|x| x.as_str()).unwrap_or("").to_string();
        self.context_name = name_of(c);
    }

    /// Applies one Soloist WebSocket event. Returns true when the track changed.
    pub fn apply(&mut self, v: &Value) -> bool {
        let ty = v.get("type").and_then(|t| t.as_str()).unwrap_or("");
        let old = self.track.as_ref().map(|t| t.uri.clone());
        match ty {
            "auth_state" => {
                self.logged_in = v.get("logged_in").and_then(|x| x.as_bool()).unwrap_or(false);
                self.is_active = v.get("is_active").and_then(|x| x.as_bool()).unwrap_or(false);
                if let Some(n) = v.get("device_name").and_then(|x| x.as_str()) {
                    self.device_name = n.to_string();
                }
            }
            "playback_state" => {
                self.logged_in = true;
                if let Some(s) = v.get("status").and_then(|x| x.as_str()) {
                    self.status = s.to_string();
                }
                self.track = v.get("item").and_then(parse_entity);
                if let Some(c) = v.get("context") {
                    self.set_context(c);
                }
                if let Some(p) = v.get("position") {
                    self.set_pos(p);
                }
                if let Some(x) = v.get("volume").and_then(|x| x.as_u64()) {
                    self.volume = x.min(100) as u8;
                }
                if let Some(a) = v.get("is_active").and_then(|x| x.as_bool()) {
                    self.is_active = a;
                }
                if let Some(o) = v.get("options") {
                    self.set_options(o);
                }
            }
            "track_changed" => {
                self.track = v.get("item").and_then(parse_entity);
                self.pos_ms = 0.0;
                self.pos_ts = crate::config::now_ms();
            }
            "playback_changed" => {
                let now = crate::config::now_ms();
                let p = self.position(now);
                self.pos_ms = p;
                self.pos_ts = now;
                if let Some(s) = v.get("status").and_then(|x| x.as_str()) {
                    self.status = s.to_string();
                }
            }
            "position_sync" => {
                if let Some(p) = v.get("position") {
                    self.set_pos(p);
                }
            }
            "volume_changed" => {
                if let Some(x) = v.get("volume").and_then(|x| x.as_u64()) {
                    self.volume = x.min(100) as u8;
                }
            }
            "device_changed" => {
                if let Some(a) = v.get("is_active").and_then(|x| x.as_bool()) {
                    self.is_active = a;
                }
                if let Some(n) = v.get("device_name").and_then(|x| x.as_str()) {
                    self.device_name = n.to_string();
                }
            }
            "options_changed" => {
                if let Some(o) = v.get("options") {
                    self.set_options(o);
                }
            }
            "context_changed" => {
                if let Some(c) = v.get("context") {
                    self.set_context(c);
                }
            }
            _ => {}
        }
        // queue data can arrive as a queue_changed event or as the answer to get_queue
        let up = v.get("upcoming").or_else(|| v.pointer("/queue/upcoming")).or_else(|| v.pointer("/result/upcoming")).or_else(|| v.pointer("/data/upcoming"));
        if let Some(up) = up.and_then(|x| x.as_array()) {
            let list: Vec<Track> = up
                .iter()
                .filter_map(|e| {
                    let mut t = e.get("item").and_then(parse_entity).or_else(|| parse_entity(e))?;
                    t.provider = e
                        .get("provider")
                        .or_else(|| e.get("source"))
                        .and_then(|p| p.as_str().map(|s| s.to_string()).or_else(|| p.get("type").and_then(|x| x.as_str()).map(|s| s.to_string())))
                        .unwrap_or_default()
                        .to_lowercase();
                    Some(t)
                })
                .collect();
            // Broadcasts are capped at 10 entries; keep a longer list we fetched if it still lines up.
            let first_same = list.first().map(|t| &t.uri) == self.upcoming.first().map(|t| &t.uri);
            if !(ty == "queue_changed" && list.len() == 10 && self.upcoming.len() > 10 && first_same) {
                self.upcoming = list;
            }
        }
        old != self.track.as_ref().map(|t| t.uri.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn playback_state() {
        let v: Value = serde_json::from_str(r#"{"type":"playback_state","status":"playing","item":{"uri":"spotify:track:1","entity_type":"track","decorations":{"identity":{"name":"My Song"},"visual_identity":{"cover":[{"url":"L","size":"large"},{"url":"S","size":"small"}]},"parent":{"entity":{"uri":"spotify:album:a","decorations":{"identity":{"name":"Album"}}}},"creators":[{"entity":{"decorations":{"identity":{"name":"A1"}}}},{"entity":{"decorations":{"identity":{"name":"A2"}}}}],"playback":{"duration_ms":210000}}},"context":{"uri":"spotify:playlist:x","decorations":{"identity":{"name":"PL"}}},"position":{"position_ms":45000,"timestamp_ms":1000,"speed":1.0},"volume":65,"is_active":true,"options":{"shuffle":true,"repeat":"context"}}"#).unwrap();
        let mut p = Play::default();
        assert!(p.apply(&v));
        let t = p.track.clone().unwrap();
        assert_eq!((t.name.as_str(), t.artists.as_str(), t.album.as_str(), t.duration_ms), ("My Song", "A1, A2", "Album", 210000));
        assert_eq!(t.cover_small.as_deref(), Some("S"));
        assert_eq!((p.volume, p.shuffle, p.repeat.as_str(), p.context_name.as_str()), (65, true, "context", "PL"));
        assert_eq!(p.position(3000), 47000.0);
        p.apply(&serde_json::json!({"type":"playback_changed","status":"paused"}));
        let frozen = p.position(10_000_000);
        assert_eq!(frozen, p.position(20_000_000));
    }
}

# SpoX skin

Every piece of the player is its own PNG in this folder. Edit them in any pixel-art tool (Aseprite, LibreSprite, Piskel, Paint.NET...), save, then press **F5** in SpoX to reload. No restart is needed.

- **Sizes come from the PNG.** The size below is the size of the default art and the room it has in the layout. Drawing bigger is allowed, and the click area grows with it. If you need to move something, change its spot in `skin.json`.
- **Everything is on an 8 px grid** (8, 16, 24, 32, 48, 64...), like Minecraft textures. Keep new art on the same grid.
- **1 PNG pixel = 1 screen pixel at 1x.** SpoX scales by whole numbers (1x to 4x) with no smoothing, so your pixels stay sharp.
- **Transparency works everywhere.** Non-square buttons are fine.
- **`_down` = pressed, and also the active state:** play stays pressed while playing, pause while paused, and shuffle, repeat, the panel button and the pin while they're on. `_on` files are only used by the tabs.
- `_backup/` holds your originals of files I had to resize (8-px digits, 32-px shuffle/repeat).
- `_guides/layout_main.png` and `_guides/layout_panel.png` show every element's box at 4x (magenta outlines). `_guides/font_grid.png` shows the font cells with the baseline in red.
- To go back to the default for one file, delete it. SpoX falls back to its built-in copy.

## Files

| File | Size (px) | What it is |
|---|---|---|
| `art_empty.png` | 48×48 | Shown in the album-art box when a song has no cover |
| `btn_close.png` | 8×8 | Close (Soloist keeps running) |
| `btn_close_down.png` | 8×8 | Close (Soloist keeps running) (pressed) |
| `btn_menu.png` | 8×8 | Menu button (top-left) |
| `btn_menu_down.png` | 8×8 | Menu button (top-left) (pressed) |
| `btn_min.png` | 8×8 | Minimize |
| `btn_min_down.png` | 8×8 | Minimize (pressed) |
| `btn_next.png` | 24×16 | Next button |
| `btn_next_down.png` | 24×16 | Next button (pressed) |
| `btn_panel.png` | 24×16 | Show/hide the bottom panel (music disc) |
| `btn_panel_down.png` | 24×16 | Show/hide the bottom panel (music disc) (pressed) |
| `btn_panel_on.png` | 24×16 | Same, while the panel is open |
| `btn_pause.png` | 24×16 | Pause button |
| `btn_pause_down.png` | 24×16 | Pause button (pressed) |
| `btn_pin.png` | 8×8 | Always-on-top pin |
| `btn_pin_down.png` | 8×8 | Always-on-top pin (pressed) |
| `btn_pin_on.png` | 8×8 | Pin while always-on-top is on |
| `btn_play.png` | 24×16 | Play button |
| `btn_play_down.png` | 24×16 | Play button (pressed) |
| `btn_prev.png` | 24×16 | Prev button |
| `btn_prev_down.png` | 24×16 | Prev button (pressed) |
| `btn_repeat.png` | 24×16 | Repeat off |
| `btn_repeat_down.png` | 24×16 | Repeat off (pressed) |
| `btn_repeat_on.png` | 24×16 | Repeat playlist |
| `btn_repeat_track.png` | 24×16 | Repeat one track |
| `btn_shuffle.png` | 24×16 | Shuffle off |
| `btn_shuffle_down.png` | 24×16 | Shuffle off (pressed) |
| `btn_shuffle_on.png` | 24×16 | Shuffle on |
| `digits.png` | 156×16 | Big LCD timer. 13 glyphs, 12×16 each, in this order: 0123456789:- (blank). Draw in white: it gets tinted by colors.digits. Your 8 is drawn faintly behind every digit as the unlit segments (strength: digits.ghost in skin.json). Only the middle 4 columns of the colon cell are used (digits.colon_width) |
| `font.png` | 128×352 | Pixel font sheet, see "Font" below |
| `icon.png` | 16×16 | Window / taskbar icon |
| `logo.png` | 64×16 | Bottom-right logo (click = menu) |
| `main_bg.png` | 288×112 | Whole top window (frame, title bar, the two dark display boxes) |
| `panel_bg.png` | 288×128 | Whole bottom panel (list box + scrollbar track) |
| `scroll_thumb.png` | 8×16 | List scrollbar knob |
| `seek_fill.png` | 272×8 | Seek fill (cut to the song position) |
| `seek_thumb.png` | 16×8 | Seek knob |
| `seek_track.png` | 272×8 | Seek bar background |
| `status_buffer.png` | 8×8 | ... while loading |
| `status_pause.png` | 8×8 | ... while paused |
| `status_play.png` | 8×8 | Small icon next to the timer while playing |
| `status_stop.png` | 8×8 | ... when stopped / idle |
| `tab_lyrics.png` | 16×16 | Lyrics tab (mic) |
| `tab_lyrics_on.png` | 16×16 | Lyrics tab (mic) (active) |
| `tab_playlist.png` | 16×16 | Playlist tab (disc) |
| `tab_playlist_on.png` | 16×16 | Playlist tab (disc) (active) |
| `tab_queue.png` | 16×16 | Up next tab |
| `tab_queue_on.png` | 16×16 | Up next tab (active) |
| `vis_colors.png` | 8×24 | Vertical strip (only the first column is used): top pixel = colour of the top of a visualizer bar, bottom pixel = bottom |
| `vol_fill.png` | 64×8 | Volume fill (drawn cut to the volume %) |
| `vol_thumb.png` | 8×8 | Volume knob |
| `vol_track.png` | 64×8 | Volume slider background |

## Font (`font.png`)

- A grid of 16 columns of 8×12 cells (8×16), starting at character code 32 (space) and running in Unicode order: ASCII first, then Latin-1 (à é ë ç ñ ...), then Latin Extended-A (č š ž ő ...).
- In each cell, rows 0–1 are for accents on capitals, rows 2–8 are the letter (7 px tall caps), and rows 9–10 are descenders (g, p, y, ç); rows 11–15 are spare.
- Draw glyphs in **white** with a transparent background. SpoX tints them with the colors in `skin.json`.
- **Letter width is detected automatically** from the rightmost painted pixel, so narrow letters like `i` and `l` stay narrow. Leave a cell empty and SpoX draws `?` for that character.
- Spacing between letters, the space width and the line height are in `skin.json` → `font`.

## skin.json

- `pos`: top-left corner of each element, in 1x pixels. The panel's positions are measured from the top of the panel.
- `rect`: areas that aren't a single PNG, given as `[x, y, width, height]`: album art, visualizer, the two text lines, the list, the header, the scrollbar and the window drag area.
- `colors`: every text color, as `#RRGGBB`.
- `art_pixels`: how many pixels the album art is shrunk to in pixel mode. The default is 24, which shows each art pixel as 2×2 in the 48×48 box (16 gives 3×3). Click the album art to switch between pixel and sharp.
- `vis`: number of bars, bar width and gap.
- `uppercase_display`: `true` shows the title and artist in capitals, like Winamp.
- `timer_blink_when_paused`: whether the timer blinks while paused.


## Added in v0.2

| File | Size (px) | What it is |
|---|---|---|
| `btn_like.png` / `btn_like_down.png` | 24×16 | Like button. The `_down` image also shows while the song is in your Liked Songs |
| `btn_add.png` / `btn_add_down.png` | 24×16 | Add the current song to one of your playlists |
| `btn_link.png` / `btn_link_down.png` | 24×16 | Copy the song's open.spotify.com link |
| `panel_bg.png` | any size | Bottom panel frame. It's stretched to the panel's height with its 8px border kept crisp (9-slice) |
| `list_bg.png` | 24×24 | Dark box behind each open section (9-slice, 4px border) |
| `scroll_bg.png` | 8×24 | Scrollbar track (9-slice) |
| `tab_row_hover.png` | 24×16 | Section row while the mouse is over it (9-slice, optional) |
| `tab_row_open.png` | 24×16 | Optional: section row while open (9-slice) |
| `tab_pin.png` | 8×8 | Marker on pinned sections |

**Sections (accordion):** click a row to show only that section; double-click it to pin it open. The `v` on the playlist row opens the playlist chooser. `accordion.content_h` in skin.json sets how tall an open section is.

**Optional pieces:** remove a position from `skin.json` and that element disappears. Examples: `status` (the small play icon, now off by default), `logo`, `btn_like`, `btn_add`, `btn_link`. Remove the `pfp` rect to hide your name and profile picture.

**Tooltips:** add `"tooltips": {"btn_play": "your text"}` in skin.json to change any tooltip.


## Layout in v0.3

- Everything sits on an 8px outer margin with **4px gaps** between neighbours.
- **The right display has three lines** (`rect.title`, `rect.artist`, `rect.album`).
- **`rect.vol` sets the volume bar's size.** `vol_track.png` and `vol_fill.png` stretch to it, keeping 2px crisp ends.
- **`rect.infobox` is the small box bottom-right.** It shows the song's position in the playlist (e.g. 12/48); click it to jump the list to that song.
- **When the visualizer is off,** its space shows the next song.
- `_backup/main_bg_original.png` is your title-bar art from before I moved the display boxes.


## Added in v0.4

- **The window is 292 px wide,** so ten 24-px buttons fit with even 4 px gaps. The panel stretches to match.
- **`btn_smart.png` / `_down` (24×16):** SpoX smart shuffle. The `_down` image shows while it's on.
- **`btn_mini.png` / `_down` (24×16):** hides SpoX into the taskbar player.
- **`rect.seek` sets the seek bar's size.** `seek_track.png` and `seek_fill.png` stretch to it, like the volume bar.
- **Taskbar player** (designed at 16 px tall; SpoX scales it ×1–×4 to fit your taskbar height):

| File | Size | What it is |
|---|---|---|
| `tb_bg.png` | 16×16 | Background. Stretched sideways; its 4 px ends stay crisp |
| `tb_art_empty.png` | 16×16 | Shown when a song has no cover |
| `tb_prev.png`, `tb_play.png`, `tb_pause.png`, `tb_next.png` (+ `_down`) | 16×16 | Buttons. Draw them transparent; `tb_bg` shows through |
| `icon.png` | 16×16 | Also used when "When nothing plays: just the icon" is on |

`skin.json → taskbar`: `art_pixels` (album art size in pixels) and `status_icon` (the small play/pause icon before the title). The `colors` `tb_title` and `tb_artist` set its text colours.


## Added in v0.5

- `tab_history.png` / `tab_history_on.png`: the Recently played section icon.
- `btn_shuffle_smart.png`: the shuffle button while SpoX smart shuffle is on (the `btn_smart*` files are no longer used).
- `btn_like/add/link/mini*.png` and `tb_prev/play/pause/next*.png` were redrawn in your button style by `tools/make_buttons.py`. That script only touches these files, never your own art.
- `tb_frame.png`: the taskbar player's border. It's drawn **on top** of everything, so keep the middle transparent. It's a 9-slice; the corner size is `taskbar.tb_frame_corner` in skin.json.
- `tb_bg.png`: still the background behind the text (9-slice, `taskbar.tb_bg_corner`).
- skin.json `taskbar`: `bezel` (the invisible padding around the text, in 1x px) and `dim` (brightness when paused).
- skin.json colours: `tb_vol` / `tb_vol_back` (the volume bar) and `anim_bg` (the tuck-away animation). `anim_ms` sets the animation length.
- Colour packs: `skin/packs/<name>.json` = `{"hue": -100, "sat": 1.0, "map": {"#5BE38E": "#F2B84B"}}`. `hue` turns every coloured pixel, `sat` scales its saturation and `map` swaps exact colours first. Greys stay exactly as you drew them.

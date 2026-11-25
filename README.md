# SpoX

A tiny pixel-art Spotify mini player for Windows. Spotify's own **Soloist** player does the playback; SpoX is the window you look at.

- About 6–7 MB, one `.exe`, no browser engine.
- Soloist stays running in the background after you close SpoX, so SpoX opens and connects instantly. There's no 10-second login wait.
- Parts: album art drawn as pixel art, a spectrum visualizer, an LCD timer that blinks when paused, a playlist tab, an Up next tab and synced lyrics (from LRCLIB).

## Download

Get the newest version from **[Releases](https://github.com/oAtomH/SpoX/releases/latest)**:

- **`SpoX-Setup-x.y.z.exe`**: the classic setup wizard.
- **`SpoX.exe`**: just the app. The first time you run it, it offers to install itself (no admin needed), or you can run it as is.

Then follow step 2 below to get your keys. See also: [Privacy](PRIVACY.md) · [Terms](TERMS.md) · MIT license.

## 1. Build it yourself (optional)

Double-click **`build.bat`**.

- The first time, it installs Rust (the official installer from rustup.rs) and builds SpoX. This takes about 5 minutes.
- When it finishes, `SpoX.exe` is in this folder and there's a **SpoX** shortcut on your desktop.
- Later builds after code changes take under a minute.

## 2. Get your keys (once, about 3 minutes)

You need Spotify Premium for this.

1. Go to **developer.spotify.com → Dashboard** and log in.
2. **Soloist API key** (required): open *Spotify Soloist API Key* and generate a key.
3. **Client ID** (optional, needed for the playlist list):
   1. Click *Create app*. Any name works.
   2. Add the Redirect URI `http://127.0.0.1:43821/callback`.
   3. Tick *Web API* and save.
   4. Copy the Client ID.
4. Open SpoX. The Setup panel appears. Paste both values and click **[ Save ]** (Tab moves between the fields). Your browser opens once so you can approve playlist access.

SpoX downloads `soloist.exe` by itself and keeps it updated. Soloist builds expire after 90 days.

## 3. Pair once

Open Spotify on your phone or PC, open the **Devices** list and pick **SpoX**. Soloist saves the login, and you never have to do this again.

## Controls

| | |
|---|---|
| Click the album art | Switch between pixel and sharp art |
| Click the timer | Switch between elapsed and remaining time |
| Click the visualizer | Turn the visualizer on or off |
| Drag the title bar | Move the window |
| Right-click anywhere / ☰ / logo | Menu: size 1x–4x, always on top, sign in, setup, reload skin, quit |
| Disc tab or header | Pick a playlist. Click once to open it, double-click to play it |
| Double-click a song | Play it inside its playlist |
| Double-click a lyric line | Jump to that line |
| Scroll over the volume bar | Volume ±5 |
| `Space` | Play / pause |
| `←` / `→` | Seek back / forward 5 seconds |
| `Ctrl+←` / `Ctrl+→` | Previous / next track |
| `↑` / `↓` | Volume up / down |
| `S` / `R` | SpoX shuffle / repeat on-off |
| `Ctrl+F` (or `/`) | Find in the open playlist: type, `Enter` plays, `Esc` closes |
| `O` / `Shift+O` | Sort the open playlist (newest first, title, artist, album, length, playlist order) / flip the direction |
| `Ctrl` + click in Up next | Pick several songs; right-click or `Delete` removes them or plays them next |
| `P` / `Q` / `L` | Playlist / Up next / Lyrics tab |
| `T` | Always on top |
| `1`–`4` | Window size |
| `F5` | Reload the skin |

**Headset / keyboard media keys** control SpoX, and Windows' own media pop-up (next to the volume) shows the song and cover.

**Minimize (–)** hides SpoX to the tray icon by the clock. Left-click the tray icon to show or hide it; right-click it for play/pause, next, previous and quit. Turn this off in the menu. On Windows 11, if the icon sits in the hidden ^ area, drag it onto the taskbar, or turn SpoX on under Settings → Personalization → Taskbar → Other system tray icons.

**Closing SpoX** (✕) leaves Soloist running so your music keeps playing, and the next start is instant. **Menu → Quit + stop Soloist** stops everything.

## Make it yours

Every button, background, icon and the font is a PNG in `skin/`. See `skin/SKIN_README.md`, and use the layout guides in `skin/_guides/`. Press **F5** to see your changes live.

To preview without Spotify, run `SpoX.exe` with the environment variable `SPOX_DEMO=1`. That shows fake songs and lyrics. Other values are `lyrics`, `queue`, `setup`, `menu`, `picker` and `paused`.

## Where things are stored

| What | Where |
|---|---|
| Settings and keys | `%APPDATA%\SpoX\config.json` |
| Soloist, its login and its log | `%LOCALAPPDATA%\SpoX\soloist\` |
| Cached art, lyrics and playlists | `%LOCALAPPDATA%\SpoX\` |

**Portable mode:** put an empty file called `portable.txt` next to `SpoX.exe`. Everything then lives in a `data` folder beside it.

## Giving it to friends

Two ways, pick one:

- **Simple:** send them just `SpoX.exe`. The first time they open it, SpoX offers to install itself (no admin needed): Start menu + desktop shortcut, and it shows up in *Settings → Apps* so they can uninstall it normally. They can also pick *Just run it* or *Portable*.
- **Classic wizard:** run `build.bat installer`. It installs Inno Setup once, then makes `SpoX-Setup-0.5.0.exe` with the pixel-art wizard. Send them that file.

Each friend needs:

- **Spotify Premium.**
- **Their own Soloist API key.** Spotify doesn't allow sharing keys.
- **For playlists:** either their own Client ID, or yours if you add their Spotify email under *User Management* in your app. Development-mode apps allow up to 5 users.

## Limits (Spotify's rules, not SpoX's)

- **Playlists you only follow:** Spotify lists the songs only for playlists you own or collaborate on. For others, double-click to play the playlist, then use the *Up next* tab.
- **The queue:** you can add to it (coming later) but can't remove or reorder songs. Soloist doesn't offer that.
- **Crossfade and offline downloads:** these aren't possible. Soloist (the official Spotify player that SpoX runs on) has no crossfade or download option. Its only settings are device name, cache folder and size, starting volume and similar basics.
- **Exclusive-fullscreen games:** nothing can draw over a game in *exclusive* fullscreen mode; Windows gives the screen to the game. In *borderless* or *windowed fullscreen*, the taskbar player shows the song (info only, clicks go through to the game). Anti-cheat software (Vanguard and others) treats programs that draw *inside* a game as cheats, so SpoX doesn't try.
- **The visualizer:** it shows what your speakers play, including other apps' sounds.
- **Sign-in:** Spotify sign-ins expire after about 6 months. SpoX then asks you to sign in again from the menu.

## Planned

- OBS overlays: compact, standard, extended, lyrics and minimal layouts. These will be served from SpoX's existing playback state over a local WebSocket and HTTP, with no second Spotify connection.
- Tray mode, global media keys and launch on startup.


## New in v0.9 (Windows + Android)

- **SpoX shuffle rebuilt again, properly this time.** Spotify now gets a real playing list from SpoX (20 songs back, 80 ahead), so next / previous work everywhere (headphones, lock screen, car) and going back goes to the song before, not the start of the session. Up next is always about 100 songs, fully planned with the new songs in place; it only grows at the bottom. When the list has been played through, a new shuffled round follows.
- **Shuffle button:** off → shuffle → SpoX shuffle. Every playlist also has *Play*, *Shuffle* and *SpoX shuffle* at the top (phone).
- **Phone:**
  - Pulling out headphones / Bluetooth disconnecting pauses at once. Calls and voice notes pause the music and it carries on after.
  - The volume number is always shown on the Player tab, and volume changed from Bluetooth headphones or a car shows in SpoX too.
  - The library reopens on the playlist you had open.
  - Settings → *Lock screen player* says why the lock screen player is missing, and fixes it with a tap.

## New in v0.8 (Windows + Android)

- **SpoX shuffle plans ahead.** New songs are placed into the order as soon as they're found, so Up next shows a long, fixed list with the new songs already in it. Going back and forth never swaps them. New songs are picked like a radio: from artists similar to the song next to them. Spotify only gets the next 3 songs at a time; the rest stays SpoX's.
- **Listening stats:** top artists, top songs and time listened (phone: Library → *Your listening stats*; PC: right-click Recently played or press `I`).
- **Last.fm scrobbling** (Settings → *Last.fm scrobbling*; PC: key + secret in Setup).
- **Sorting both ways:** tap the active sort again (phone) or `Shift+O` (PC).
- **PC:** a Bluetooth / car / headphones icon after the song title when the sound goes there (hover it for the device).
- **Phone:**
  - One search box for everything: the open list, your library and Spotify, in sections.
  - Swipe a song in Up next to the left to remove it.
  - The volume buttons show on the big volume bar on the Player tab (the pop-up is for other tabs). Tap the speaker to mute.
  - Remembers the song and spot you were at; pressing play right after opening waits for SpoX Phone instead of failing. SpoX Engine starts faster.
  - Songs you play are kept in a song cache (Settings → *Song cache*), so replays use less mobile data.

## New in v0.7 (Windows + Android)

- **SpoX shuffle, rebuilt.** SpoX decides the order itself instead of Spotify:
  - Every song plays once before any repeats, duplicates are merged, and the same artist is spread out.
  - New songs (not in your Liked Songs, the list or recent plays) are slipped in by the **new songs** share: 0% (just your list), 25, 50, 75 or **100% (only new songs)**. They come from Spotify's own autoplay picks and from artists similar to what's playing right now.
  - The order is **saved**: close SpoX or restart the PC/phone and it carries on where you were.
  - Shuffle is now simply off ↔ SpoX shuffle. Turning it off carries on with the list in its own order.
- **Up next:** new songs are marked `NEW` (never heard) or `pick` (brought in by SpoX/Spotify). Pick songs to **remove** them or **play them next** (phone: long-press; PC: Ctrl+click). Removed songs are skipped if Spotify already had them.
- **Library / playlists:** sort by recently added (the default), title, artist, album, length or the list's own order, remembered per list. Find songs inside a list. With shuffle off, a song plays the list **in the order you see**.
- **Lyrics** for the next song are looked up while this one plays: no "looking for lyrics" flash on skip.
- **PC:** SpoX stays awake while hidden in the tray (taskbar shuffle and buttons react at once). Hiding from the tray icon or taskbar player plays the hide animation too.
- **Phone:**
  - Closing SpoX (swipe it away) quits it, once the music is paused (Settings → *When you close SpoX*).
  - Says *Playing on SpoX Phone*, with a Bluetooth / car / headphones icon and the speaker's name. Device picker, search, keys and menus are SpoX-styled, not Android's.
  - Volume buttons show SpoX's own pixel volume instead of the phone's slider (Settings → *Volume buttons*).
  - The player only follows Spotify (no more YouTube or PDF readers showing up), and the lock screen player is re-posted so it stays.
  - The like button uses Spotify's new library API (it had stopped working), and +/- flips straight away.

## New in v0.4

- **Taskbar player:** a small strip on your taskbar, next to the tray icons.
  - It fits any taskbar height from 16 to 64 px and hides below 16 px or over fullscreen games.
  - Click it to open or hide SpoX, scroll for volume, and middle-click to copy the song link.
  - Every click action is changeable in **Settings → Taskbar player**. Ctrl + scroll nudges it along the taskbar.
  - The 🖵 button in SpoX tucks SpoX into it.
- **Smart shuffle (the ✦ button next to Shuffle):**
  - Plays your playlist in a truly random order, with duplicate songs merged so nothing plays twice.
  - Mixes in songs that are **not** in your playlists or Liked Songs (50% by default; change it in Settings).
  - New songs come from Spotify's own autoplay picks, and from Last.fm if you add a free key in Setup.
  - New songs show as `new` in Up next.
  - It keeps working while SpoX is hidden in the tray.
- **Up next works like Spotify's queue:**
  - It's grouped into Next in queue / Next from <playlist> / Autoplay.
  - Double-click a song to skip to it, or right-click it for more.
- **Right-click any song** for: play, add to queue, save to Liked Songs, add to playlist, copy link.
- **Settings page** (Menu → Settings...) holds every option, including **Start with Windows**.
- Playlist rows show the song count and total length (e.g. `Gym mix · 48 songs · 2h 13m`).


## New in v0.5

- **Sections:** clicking a section opens it right away (no more double-click delay). Use the pin on the right of each row to keep several open.
- **Recently played** section: every song you played, including songs started from your phone. Double-click a song to go back to it.
- **Shuffle cycles** through three modes: off → Spotify shuffle → SpoX smart shuffle (the pressed button with the little spark). Up Next refreshes when you switch.
- **Settings:** left-click goes to the next option and right-click goes back.
- **Taskbar player, rebuilt:**
  - It sits on the left of the taskbar by default; nudge it with Ctrl + scroll or *Left side offset*.
  - It can follow your main monitor, the monitor of the active window, or a fixed monitor 2/3/4.
  - It starts hidden until it's in place, so it never floats on screen. It also hides with an auto-hidden taskbar and under the Start menu, search and Alt-Tab.
  - Text sits inside an invisible bezel: two lines when they fit, otherwise one scrolling line. The status icon has its own spot.
  - It dims when paused. Optional volume bar (it also flashes when you scroll the volume).
  - Over fullscreen apps it shows *info only* (click-through) by default; you can also pick *hide* or *stay*.
  - The border comes from `tb_frame.png`, so you can redraw it. Hovering shows the tooltip in the text spot.
  - More actions: forward/back 10 s, mute, cycle shuffle.
  - It can show only while SpoX is hidden.
- **Tuck-away animations:** the taskbar button in SpoX now plays *shrink & fly*, *TV switch-off* or *pixel dissolve* (Settings → Look).
- **Colour packs:** Default, Amber, Ice, Rose, Lime and Mono, plus a live *Custom hue* slider. *Save current colours as pack* writes `skin/packs/Custom N.json`, which you can hand-edit (`hue`, `sat`, exact colour `map`).
- **Discord:** shows "Listening to" with album art, a time bar and a **Get SpoX** button. Set it up in Settings → Discord.
- **Add to playlist** is fixed. Songs already in a playlist (same title + artist counts) are marked *in it* and can't be added twice.
- **Setup** walks through each key step by step, with a link for each one.
- **Memory readout** in Settings shows SpoX and Soloist separately. Soloist now runs as SpoX's child process, so Task Manager lists it under SpoX.
- Your name and picture moved out of the title bar (it's your art), into the menu header.

## New in v0.5.1

- **+ / − button:** it works with the playlist you're playing. `+` adds the song; if the song is already in there, the button shows pressed with a `−` and clicking removes it. Adding to other playlists stays in Spotify (or right-click a song).
- **SpoX smart shuffle** now uses Spotify's own shuffle of the playing playlist, so Up next shows the whole list. SpoX slips new songs in between (the "new songs" %), inspired by that playlist.
- **Taskbar player:**
  - New shuffle button (off → Spotify → SpoX smart). Turn it off in Settings if you don't want it.
  - No tooltips or play/pause icons in front of the song.
  - *When idle* now also counts "paused for 10 s", so *just the icon* and *hide* actually kick in.
  - It no longer disappears when some shell window is in the foreground, only when one really covers it. It fixes itself if Windows moves or resizes it (DPI change on another monitor). The monitor list matches how many monitors you have.
- **Visualizer:** never "off". Click pauses it (frozen bars), double-click switches style (bars / mirror).
- **Your picture is the menu button** (outlined, so a black or missing picture still shows). The menu header shows your picture and name.
- **Hide animations** (also on the minimize button): TV switch-off (default), pixel dissolve, shutter. They step at about 12 frames a second for that old-hardware look.
- **Colour packs:** Default, Amber CRT, Ice, Rose, Lime, Mono, Game Boy, Vapor, Blood, Ocean, Sunset, Paper.
- **Style lab** (Settings → Look → Style lab, off by default so normal settings stay simple):
  - Hue turn, colour strength, grey tint and grey brightness.
  - Button edges: normal, soft, strong or flat.
  - Text shadow and text colours (normal, bright, soft).
  - Pick your own colour for each kind of text.
  - *Save all this as a pack*.
- **Discord** is built in: users only get an on/off switch. Your SpoX icon and the song title are clickable (the icon goes to GitHub, the title to the song). There's also a **Get SpoX** button.
- **Memory** in Settings now counts like Task Manager does (private memory).
- **Lighter on CPU:**
  - Dragging the window no longer redraws it.
  - Hidden or minimized, SpoX doesn't redraw at all and the visualizer stops listening.
  - The clock wakes the app once a second instead of four times.

## Discord (v0.5.3)

Turn on **Settings → Discord → Show song on Discord**. Your profile then shows *Listening to SpoX* with the song, album art and a time bar.

- **Friends with Spotify** click **Listen on Spotify** to open the same song.
- **Friends with SpoX** click **Join** (or *Ask to join*, which SpoX accepts by itself). Their SpoX jumps into the same song at the same moment. It's a one-time sync, not a live session: after that song, each of you carries on separately.
- **Discord's own "Listen Along"** (the one that keeps you in sync) is only for Spotify's own Discord status. SpoX plays through your Spotify account, so if you link Spotify under *Discord → Settings → Connections*, Discord can show that status as well. Spotify users can then Listen Along with you, and you can Listen Along with them: it plays on SpoX if SpoX is your active Spotify device. This is Discord's own feature, so it may only work when your Spotify desktop or phone app is the device playing.

## Updates (v0.5.4)

From v0.5.4 on, SpoX updates itself. It checks GitHub when it starts (and every 6 hours), downloads the new version in the background and swaps it in. The menu then shows **Restart to update**; otherwise the new version starts the next time you open SpoX. Your settings, sign-in and skin stay as they are. Turn it off in Settings → App → Update automatically.

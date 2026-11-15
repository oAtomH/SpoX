//! Built-in installer for friends: SpoX.exe copies itself to the user's Programs folder,
//! adds Start menu + desktop shortcuts and a normal "Apps & features" uninstall entry.
//! No admin rights needed (everything is per-user). `SpoX.exe --uninstall` removes it again.

use std::path::{Path, PathBuf};

#[cfg_attr(not(windows), allow(dead_code))]
const UNINSTALL_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\SpoX";

/// %LOCALAPPDATA%\Programs\SpoX
pub fn install_dir() -> PathBuf {
    dirs::data_local_dir().unwrap_or_else(|| PathBuf::from(".")).join("Programs").join("SpoX")
}

fn same(a: &Path, b: &Path) -> bool {
    let n = |p: &Path| p.canonicalize().unwrap_or_else(|_| p.to_path_buf()).to_string_lossy().to_lowercase();
    n(a) == n(b)
}

/// Running from the install folder (or installed by the Inno Setup wizard, which writes the same key).
pub fn is_installed() -> bool {
    if same(&crate::config::exe_dir(), &install_dir()) {
        return true;
    }
    // the Inno Setup wizard leaves its uninstaller next to the exe
    crate::config::exe_dir().join("unins000.exe").exists()
}

/// Worth offering the installer? (not installed, not portable, not run from a build folder)
pub fn should_offer() -> bool {
    if is_installed() || crate::config::portable() {
        return false;
    }
    // your own build folder (Cargo.toml next to the exe): never nag the developer
    if crate::config::exe_dir().join("Cargo.toml").exists() {
        return false;
    }
    let d = crate::config::exe_dir().to_string_lossy().to_lowercase();
    !(d.contains("\\target\\") || d.ends_with("\\target\\release") || d.ends_with("\\target\\debug"))
}

#[cfg(windows)]
fn hidden(cmd: &mut std::process::Command) -> &mut std::process::Command {
    use std::os::windows::process::CommandExt;
    cmd.creation_flags(0x0800_0000)
}

#[cfg(windows)]
fn shortcut(lnk: &Path, target: &Path, dir: &Path) {
    let ps = format!(
        "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{}');$s.TargetPath='{}';$s.WorkingDirectory='{}';$s.IconLocation='{},0';$s.Description='SpoX - pixel Spotify mini-player';$s.Save()",
        lnk.display().to_string().replace('\'', "''"),
        target.display().to_string().replace('\'', "''"),
        dir.display().to_string().replace('\'', "''"),
        target.display().to_string().replace('\'', "''"),
    );
    let _ = hidden(std::process::Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", &ps])).status();
}

#[cfg_attr(not(windows), allow(dead_code))]
fn start_menu_lnk() -> Option<PathBuf> {
    dirs::config_dir().map(|d| d.join(r"Microsoft\Windows\Start Menu\Programs\SpoX.lnk"))
}
#[cfg_attr(not(windows), allow(dead_code))]
fn desktop_lnk() -> Option<PathBuf> {
    dirs::desktop_dir().map(|d| d.join("SpoX.lnk"))
}

fn copy_dir(from: &Path, to: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(to)?;
    for e in std::fs::read_dir(from)?.flatten() {
        let p = e.path();
        let t = to.join(e.file_name());
        if p.is_dir() {
            copy_dir(&p, &t)?;
        } else {
            std::fs::copy(&p, &t)?;
        }
    }
    Ok(())
}

/// Copies SpoX (and a custom skin folder next to it, if any) and registers it. Returns the new exe.
pub fn install(desktop_icon: bool, start_with_windows: bool) -> Result<PathBuf, String> {
    let dir = install_dir();
    std::fs::create_dir_all(&dir).map_err(|e| format!("can't create folder: {}", e))?;
    let me = std::env::current_exe().map_err(|e| e.to_string())?;
    let exe = dir.join("SpoX.exe");
    std::fs::copy(&me, &exe).map_err(|e| format!("can't copy SpoX.exe (is it running from there?): {}", e))?;
    let skin = crate::config::exe_dir().join("skin");
    if skin.is_dir() {
        let _ = copy_dir(&skin, &dir.join("skin"));
    }
    #[cfg(windows)]
    {
        if let Some(l) = start_menu_lnk() {
            shortcut(&l, &exe, &dir);
        }
        if desktop_icon {
            if let Some(l) = desktop_lnk() {
                shortcut(&l, &exe, &dir);
            }
        }
        let size_kb = std::fs::metadata(&exe).map(|m| m.len() / 1024).unwrap_or(0).to_string();
        let exe_s = exe.display().to_string();
        let vals: [(&str, &str, String); 9] = [
            ("DisplayName", "REG_SZ", "SpoX".into()),
            ("DisplayVersion", "REG_SZ", env!("CARGO_PKG_VERSION").into()),
            ("Publisher", "REG_SZ", "SpoX".into()),
            ("DisplayIcon", "REG_SZ", format!("{},0", exe_s)),
            ("InstallLocation", "REG_SZ", dir.display().to_string()),
            ("UninstallString", "REG_SZ", format!("\"{}\" --uninstall", exe_s)),
            ("NoModify", "REG_DWORD", "1".into()),
            ("NoRepair", "REG_DWORD", "1".into()),
            ("EstimatedSize", "REG_DWORD", size_kb),
        ];
        for (k, t, v) in vals {
            let _ = hidden(std::process::Command::new("reg").args(["add", UNINSTALL_KEY, "/v", k, "/t", t, "/d", &v, "/f"])).status();
        }
        if start_with_windows {
            let _ = hidden(std::process::Command::new("reg").args([
                "add",
                r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run",
                "/v",
                "SpoX",
                "/t",
                "REG_SZ",
                "/d",
                &format!("\"{}\" --tray", exe_s),
                "/f",
            ]))
            .status();
        }
    }
    let _ = (desktop_icon, start_with_windows);
    Ok(exe)
}

#[cfg(windows)]
fn ask(text: &str, title: &str) -> bool {
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, IDYES, MB_ICONQUESTION, MB_YESNO};
    let w = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
    unsafe { MessageBoxW(std::ptr::null_mut(), w(text).as_ptr(), w(title).as_ptr(), MB_YESNO | MB_ICONQUESTION) == IDYES }
}

/// `SpoX.exe --uninstall`: asks, then removes shortcuts, registry entries and the program folder.
pub fn uninstall() {
    #[cfg(windows)]
    {
        if !ask("Remove SpoX from this PC?", "Uninstall SpoX") {
            return;
        }
        let wipe_data = ask(
            "Also delete your SpoX settings, Spotify sign-in and the downloaded Soloist?\n\n(No = keep them, in case you reinstall later)",
            "Uninstall SpoX",
        );
        crate::soloist::stop_soloist();
        for l in [start_menu_lnk(), desktop_lnk()].into_iter().flatten() {
            let _ = std::fs::remove_file(l);
        }
        let _ = hidden(std::process::Command::new("reg").args(["delete", UNINSTALL_KEY, "/f"])).status();
        let _ = hidden(std::process::Command::new("reg").args(["delete", r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run", "/v", "SpoX", "/f"])).status();
        if wipe_data {
            let _ = std::fs::remove_dir_all(crate::config::config_dir());
            let _ = std::fs::remove_dir_all(crate::config::cache_dir());
            if let Some(d) = crate::config::soloist_dir().parent() {
                let _ = std::fs::remove_dir_all(d);
            }
        }
        // the exe can't delete itself while running: a hidden cmd waits a moment, then removes the folder
        let dir = crate::config::exe_dir();
        if same(&dir, &install_dir()) {
            let _ = hidden(std::process::Command::new("cmd").args([
                "/c",
                &format!("ping -n 3 127.0.0.1 >nul & rmdir /s /q \"{}\"", dir.display()),
            ]))
            .spawn();
        }
    }
}

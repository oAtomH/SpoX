// Embeds skin/icon.png as the Windows .exe icon (taskbar, Start menu, desktop shortcut).
// The pixel art is scaled up with no smoothing so it stays crisp at every size.
fn main() {
    println!("cargo:rerun-if-changed=skin/icon.png");
    println!("cargo:rerun-if-changed=build.rs");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let out = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("spox.ico");
    match make_ico(&out) {
        Ok(()) => {
            let mut res = winresource::WindowsResource::new();
            res.set_icon(out.to_str().unwrap());
            res.set("FileDescription", "SpoX");
            res.set("ProductName", "SpoX");
            if let Err(e) = res.compile() {
                println!("cargo:warning=SpoX icon not embedded ({e}); the app still works.");
            }
        }
        Err(e) => println!("cargo:warning=could not build icon: {e}"),
    }
}

fn make_ico(out: &std::path::Path) -> Result<(), String> {
    use image::codecs::ico::{IcoEncoder, IcoFrame};
    let img = image::open("skin/icon.png").map_err(|e| e.to_string())?.to_rgba8();
    let mut frames = Vec::new();
    for size in [16u32, 24, 32, 48, 64, 128, 256] {
        let r = image::imageops::resize(&img, size, size, image::imageops::FilterType::Nearest);
        frames.push(IcoFrame::as_png(r.as_raw(), size, size, image::ExtendedColorType::Rgba8).map_err(|e| e.to_string())?);
    }
    let f = std::fs::File::create(out).map_err(|e| e.to_string())?;
    IcoEncoder::new(f).encode_images(&frames).map_err(|e| e.to_string())
}

use std::sync::OnceLock;
use std::time::Duration;

static AGENT: OnceLock<ureq::Agent> = OnceLock::new();

/// Shared HTTPS client (Windows uses the built-in SChannel TLS, so no OpenSSL is shipped).
pub fn agent() -> ureq::Agent {
    AGENT
        .get_or_init(|| {
            let b = ureq::AgentBuilder::new()
                .timeout_connect(Duration::from_secs(8))
                .timeout_read(Duration::from_secs(30))
                .user_agent("SpoX/0.1 (personal Spotify mini player)");
            #[cfg(windows)]
            let b = match native_tls::TlsConnector::new() {
                Ok(c) => b.tls_connector(std::sync::Arc::new(c)),
                Err(_) => b,
            };
            b.build()
        })
        .clone()
}

/// Percent-encode a query value.
pub fn enc(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

pub fn open_url(url: &str) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let _ = std::process::Command::new("rundll32")
            .args(["url.dll,FileProtocolHandler", url])
            .creation_flags(0x0800_0000)
            .spawn();
    }
    #[cfg(not(windows))]
    {
        let _ = std::process::Command::new("xdg-open").arg(url).spawn();
    }
}

pub fn open_folder(p: &std::path::Path) {
    #[cfg(windows)]
    {
        let _ = std::process::Command::new("explorer").arg(p).spawn();
    }
    #[cfg(not(windows))]
    {
        let _ = std::process::Command::new("xdg-open").arg(p).spawn();
    }
}

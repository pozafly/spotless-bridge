//! Dependency-free transport client. Java owns settings, target selection, encoding and writes.
use std::{
    collections::HashMap,
    env, fs,
    io::{self, Read, Write},
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const MAX: usize = 16 * 1024 * 1024;
const MAGIC: u32 = 0x534c4432;
type Result<T> = std::result::Result<T, String>;

fn main() {
    if let Err(error) = run(env::args_os().skip(1).collect()) {
        eprintln!("Spotless Bridge: {error}");
        std::process::exit(1);
    }
}

fn run(args: Vec<std::ffi::OsString>) -> Result<()> {
    if args.len() == 1 && args[0] == "--version" {
        println!("Spotless Bridge {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    if args.is_empty() || (args.len() == 1 && args[0] == "--help") {
        println!("Usage: spotless-bridge [--root <gradle-root>] apply [--files-from <list>] [--] [files...]");
        return Ok(());
    }
    let mut index = 0;
    let root = if args[0] == "--root" {
        index = 2;
        PathBuf::from(args.get(1).ok_or("--root requires a directory")?)
    } else {
        env::current_dir().map_err(|e| e.to_string())?
    };
    let root = fs::canonicalize(root).map_err(|e| format!("cannot resolve project root: {e}"))?;
    if args.get(index).is_none_or(|arg| arg != "apply") {
        return Err("expected apply; use --help for usage".into());
    }
    index += 1;
    let mut files = Vec::new();
    let mut literal = false;
    while index < args.len() {
        let arg = &args[index];
        if !literal && arg == "--" {
            literal = true;
        } else if !literal && arg == "--files-from" {
            index += 1;
            let list = args.get(index).ok_or("--files-from requires a path")?;
            let text = fs::read_to_string(root.join(list))
                .map_err(|e| format!("cannot read file list: {e}"))?;
            for line in text.lines().filter(|line| !line.trim().is_empty()) {
                files.push(root.join(line));
            }
        } else {
            if !literal && arg.to_string_lossy().starts_with('-') {
                return Err("unknown option; use -- before file names starting with '-'".into());
            }
            files.push(root.join(arg));
        }
        index += 1;
    }
    if files.is_empty() {
        return Ok(());
    }
    // Validate the whole batch before starting Gradle or sending a potentially mutating request.
    let gradle_home = env::var("GRADLE_USER_HOME").unwrap_or_default();
    if gradle_home.contains(['\n', '\r']) {
        return Err("newlines in GRADLE_USER_HOME are not supported".into());
    }
    let mut payload = wire_path(&root)? + "\n" + &gradle_home + "\n";
    for file in files {
        let resolved = fs::canonicalize(&file).map_err(|e| {
            if e.kind() == io::ErrorKind::NotFound {
                format!("file not found: {}", file.display())
            } else {
                format!("cannot read {}: {e}", file.display())
            }
        })?;
        if !resolved.is_file() {
            return Err(format!("not a file: {}", file.display()));
        }
        payload.push_str(&wire_path(&resolved)?);
        payload.push('\n');
    }
    if payload.len() > MAX {
        return Err("file list exceeds 16 MiB".into());
    }
    let state = root.join(".gradle/spotless-bridge");
    let mut refreshed = false;
    loop {
        let attempt = if runtime_available(&state) {
            connect(&state)?
        } else {
            None
        };
        if let Some((mut socket, endpoint)) = attempt {
            // Once any bytes are sent, a failure is ambiguous: NEVER rerun the formatting automatically.
            let (status, message) = request(&mut socket, &endpoint, &payload, refreshed)?;
            match status {
                0 => return Ok(()),
                3 if !refreshed => {} // Server guarantees refresh responses happen before any writes.
                _ => return Err(message),
            }
        } else if refreshed {
            return Err("daemon unavailable after Gradle preparation".into());
        }
        bootstrap(&root, &state)?;
        refreshed = true;
    }
}

fn wire_path(path: &Path) -> Result<String> {
    let text = path.to_str().ok_or("file paths must be valid Unicode")?;
    // canonicalize uses extended-length Windows paths; Java expects the ordinary drive/UNC form.
    let text = if let Some(unc) = text.strip_prefix("\\\\?\\UNC\\") {
        format!("\\\\{unc}")
    } else {
        text.strip_prefix("\\\\?\\").unwrap_or(text).to_owned()
    };
    if text.contains(['\n', '\r']) {
        return Err("newlines in file names are not supported".into());
    }
    Ok(text)
}

fn runtime_available(state: &Path) -> bool {
    fs::read_to_string(state.join("client-runtime.txt"))
        .ok()
        .and_then(|value| value.lines().next().map(|line| Path::new(line).is_file()))
        .unwrap_or(false)
}

// Only these ASCII fields are read from Java Properties. Unicode project/file paths travel in payloads.
fn endpoint(text: &str) -> Option<HashMap<String, String>> {
    let mut fields = HashMap::new();
    for line in text.lines() {
        if let Some((key, value)) = line.split_once('=') {
            if [
                "protocol",
                "nativeApply",
                "nativeFamily",
                "port",
                "token",
                "key",
            ]
            .contains(&key)
            {
                fields.insert(key.to_owned(), value.to_owned());
            }
        }
    }
    if fields.get("protocol")? != "2" || fields.get("nativeApply")? != "2" {
        return None;
    }
    for key in ["token", "key"] {
        let value = fields.get(key)?;
        if value.is_empty()
            || !value
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-')
        {
            return None;
        }
    }
    if !["4", "6"].contains(&fields.get("nativeFamily")?.as_str()) {
        return None;
    }
    fields
        .get("port")?
        .parse::<u16>()
        .ok()
        .filter(|port| *port > 0)?;
    Some(fields)
}

fn connect(state: &Path) -> Result<Option<(TcpStream, HashMap<String, String>)>> {
    let text = match fs::read_to_string(state.join("endpoint.properties")) {
        Ok(text) => text,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(format!("cannot read daemon endpoint: {e}")),
    };
    let Some(fields) = endpoint(&text) else {
        return Ok(None);
    };
    let port = fields["port"].parse().unwrap();
    let ip = if fields["nativeFamily"] == "6" {
        IpAddr::V6(Ipv6Addr::LOCALHOST)
    } else {
        IpAddr::V4(Ipv4Addr::LOCALHOST)
    };
    {
        match TcpStream::connect_timeout(&SocketAddr::new(ip, port), Duration::from_secs(2)) {
            Ok(socket) => {
                socket
                    .set_read_timeout(Some(Duration::from_secs(30)))
                    .map_err(|e| e.to_string())?;
                socket
                    .set_write_timeout(Some(Duration::from_secs(5)))
                    .map_err(|e| e.to_string())?;
                socket.set_nodelay(true).map_err(|e| e.to_string())?;
                return Ok(Some((socket, fields)));
            }
            Err(e)
                if matches!(
                    e.kind(),
                    io::ErrorKind::ConnectionRefused | io::ErrorKind::AddrNotAvailable
                ) => {}
            Err(e) => return Err(format!("cannot connect to daemon: {e}")),
        }
    }
    Ok(None)
}

fn request(
    socket: &mut TcpStream,
    endpoint: &HashMap<String, String>,
    payload: &str,
    refreshed: bool,
) -> Result<(u32, String)> {
    let mut bytes = MAGIC.to_be_bytes().to_vec();
    for text in [
        endpoint["token"].as_str(),
        if refreshed {
            "apply-refreshed"
        } else {
            "apply"
        },
        endpoint["key"].as_str(),
        "",
    ] {
        // ASCII is identical in UTF-8 and DataOutputStream.writeUTF's modified UTF-8.
        let length = u16::try_from(text.len()).map_err(|_| "invalid endpoint header")?;
        bytes.extend_from_slice(&length.to_be_bytes());
        bytes.extend_from_slice(text.as_bytes());
    }
    bytes.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    bytes.extend_from_slice(payload.as_bytes());
    socket.write_all(&bytes).map_err(uncertain)?;
    read_response(socket).map_err(uncertain)
}

fn uncertain(error: io::Error) -> String {
    format!("daemon communication failed: {error}; formatting may have completed, no automatic retry was attempted")
}

fn read_response(input: &mut impl Read) -> io::Result<(u32, String)> {
    let mut word = [0; 4];
    input.read_exact(&mut word)?;
    let status = u32::from_be_bytes(word);
    input.read_exact(&mut word)?;
    let length = u32::from_be_bytes(word) as usize;
    if length > MAX {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "response exceeds 16 MiB",
        ));
    }
    let mut bytes = vec![0; length];
    input.read_exact(&mut bytes)?;
    let message =
        String::from_utf8(bytes).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    Ok((status, message))
}

fn bootstrap(root: &Path, state: &Path) -> Result<()> {
    fs::create_dir_all(state).map_err(|e| e.to_string())?;
    let runtime = fs::read_to_string(state.join("client-runtime.txt")).unwrap_or_default();
    let saved_home = runtime.lines().nth(1).map(PathBuf::from);
    let executable = if cfg!(windows) { "java.exe" } else { "java" };
    let home = saved_home
        .filter(|home| home.join("bin").join(executable).is_file())
        .or_else(|| env::var_os("JAVA_HOME").map(PathBuf::from));
    let java = home
        .as_ref()
        .map(|home| home.join("bin").join(executable))
        .unwrap_or_else(|| executable.into());
    let wrapper = root.join("gradle/wrapper/gradle-wrapper.jar");
    if !wrapper.is_file() {
        return Err(format!(
            "Gradle wrapper JAR not found: {}",
            wrapper.display()
        ));
    }
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| e.to_string())?
        .as_nanos();
    let log_path = state.join(format!(
        "gradle-start-native-{}-{stamp}.log",
        std::process::id()
    ));
    let log = fs::File::create(&log_path).map_err(|e| e.to_string())?;
    let mut command = Command::new(java);
    command
        .args(["-classpath"])
        .arg("gradle/wrapper/gradle-wrapper.jar")
        .args([
            "org.gradle.wrapper.GradleWrapperMain",
            "--no-configuration-cache",
            "--console=plain",
            ":spotlessBridgeStart",
        ])
        .current_dir(root)
        .stdin(Stdio::null())
        .stderr(log.try_clone().map_err(|e| e.to_string())?)
        .stdout(log);
    // Gradle 8.x cannot update a reused daemon's environment on Windows ARM64.
    // Only preparation uses a fresh Gradle process; warm requests still bypass Gradle.
    if cfg!(all(windows, target_arch = "aarch64")) {
        command.arg("--no-daemon");
    }
    if let Some(home) = home {
        command.env("JAVA_HOME", home);
    }
    let mut child = command
        .spawn()
        .map_err(|e| format!("cannot start Java for Gradle: {e}"))?;
    let start = Instant::now();
    let status = loop {
        match child.try_wait().map_err(|e| e.to_string())? {
            Some(status) => break status,
            None if start.elapsed() < Duration::from_secs(120) => {
                thread::sleep(Duration::from_millis(50))
            }
            None => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(format!(
                    "Gradle preparation timed out; log: {}",
                    log_path.display()
                ));
            }
        }
    };
    let log_text = fs::read_to_string(&log_path).unwrap_or_default();
    for line in log_text.lines() {
        if line.trim().starts_with("Spotless Bridge:") {
            eprintln!("{}", line.trim());
        }
    }
    if !status.success() {
        return Err(format!(
            "could not load Spotless configuration; previous rules were not applied. Log: {}",
            log_path.display()
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn endpoint_capability_is_required() {
        assert!(endpoint("protocol=2\nport=1234\ntoken=abc\nkey=def\n").is_none());
        assert!(endpoint(
            "protocol=2\nnativeApply=2\nnativeFamily=4\nport=1234\ntoken=abc\nkey=def\nroot=ignored\\uAC00\n"
        )
        .is_some());
        assert!(endpoint(
            "protocol=2\nnativeApply=2\nnativeFamily=4\nport=0\ntoken=abc\nkey=def\n"
        )
        .is_none());
    }
    #[test]
    fn response_rejects_truncation_and_large_allocations() {
        assert!(read_response(&mut &b"\0\0\0\0\0\0\0\x02x"[..]).is_err());
        assert!(read_response(&mut &b"\0\0\0\0\xff\xff\xff\xff"[..]).is_err());
        assert_eq!(
            read_response(&mut &b"\0\0\0\x03\0\0\0\x01x"[..]).unwrap(),
            (3, "x".into())
        );
    }
    #[test]
    fn unicode_paths_use_utf8_and_newlines_are_rejected() {
        assert_eq!(
            wire_path(Path::new("한글 😀.java")).unwrap(),
            "한글 😀.java"
        );
        assert!(wire_path(Path::new("a\nb.java")).is_err());
        assert!(wire_path(Path::new("a\rb.java")).is_err());
    }
}

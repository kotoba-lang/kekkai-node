use std::env;
use std::fs::File;
use std::io::{self, Read, Write};
use std::thread;

const MAX_PACKET: usize = 65_575;

fn read_frame<R: Read>(source: &mut R) -> io::Result<Vec<u8>> {
    let mut header = [0u8; 4];
    source.read_exact(&mut header)?;
    let length = u32::from_be_bytes(header) as usize;
    if length == 0 || length > MAX_PACKET {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "packet bridge length is invalid",
        ));
    }
    let mut packet = vec![0u8; length];
    source.read_exact(&mut packet)?;
    Ok(packet)
}

fn write_frame<W: Write>(destination: &mut W, packet: &[u8]) -> io::Result<()> {
    if packet.is_empty() || packet.len() > MAX_PACKET {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "TUN packet length is invalid",
        ));
    }
    destination.write_all(&(packet.len() as u32).to_be_bytes())?;
    destination.write_all(packet)?;
    destination.flush()
}

#[cfg(unix)]
fn run_bridge(mut device: File, socket_path: &str) -> io::Result<()> {
    use std::os::unix::net::UnixStream;
    let mut bridge = UnixStream::connect(socket_path)?;
    let mut bridge_reader = bridge.try_clone()?;
    let mut device_writer = device.try_clone()?;
    let inbound = thread::spawn(move || -> io::Result<()> {
        loop {
            let packet = read_frame(&mut bridge_reader)?;
            device_writer.write_all(&packet)?;
        }
    });
    let mut packet = vec![0u8; MAX_PACKET];
    loop {
        let count = device.read(&mut packet)?;
        if count == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "TUN closed"));
        }
        write_frame(&mut bridge, &packet[..count])?;
        if inbound.is_finished() {
            return inbound
                .join()
                .unwrap_or_else(|_| Err(io::Error::other("inbound pump panicked")));
        }
    }
}

#[cfg(target_os = "linux")]
fn open_tun(name: &str) -> io::Result<File> {
    use std::fs::OpenOptions;
    use std::os::fd::AsRawFd;
    use std::os::raw::{c_int, c_ulong};

    const IFNAMSIZ: usize = 16;
    const IFF_TUN: i16 = 0x0001;
    const IFF_NO_PI: i16 = 0x1000;
    const TUNSETIFF: c_ulong = 0x4004_54ca;

    unsafe extern "C" {
        fn ioctl(fd: c_int, request: c_ulong, ...) -> c_int;
    }

    if name.is_empty() || name.len() >= IFNAMSIZ || !name.is_ascii() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid TUN name"));
    }
    let device = OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/net/tun")?;
    let mut request = [0u8; 40];
    request[..name.len()].copy_from_slice(name.as_bytes());
    request[16..18].copy_from_slice(&(IFF_TUN | IFF_NO_PI).to_ne_bytes());
    let result = unsafe { ioctl(device.as_raw_fd(), TUNSETIFF, request.as_mut_ptr()) };
    if result < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(device)
}

#[cfg(target_os = "macos")]
fn open_tun(_name: &str) -> io::Result<File> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "macOS uses platform/apple NetworkExtension, not an ambient utun open",
    ))
}

#[cfg(windows)]
fn open_tun(name: &str) -> io::Result<File> {
    use std::os::windows::io::FromRawHandle;
    use std::os::raw::c_void;

    type Handle = *mut c_void;
    unsafe extern "system" {
        fn CreateFileW(
            name: *const u16,
            access: u32,
            share: u32,
            security: *mut c_void,
            creation: u32,
            flags: u32,
            template: Handle,
        ) -> Handle;
    }
    const GENERIC_READ: u32 = 0x8000_0000;
    const GENERIC_WRITE: u32 = 0x4000_0000;
    const OPEN_EXISTING: u32 = 3;
    const FILE_ATTRIBUTE_NORMAL: u32 = 0x80;
    let path = format!(r"\\.\Global\{}", name);
    let wide: Vec<u16> = path.encode_utf16().chain(Some(0)).collect();
    let handle = unsafe {
        CreateFileW(
            wide.as_ptr(),
            GENERIC_READ | GENERIC_WRITE,
            0,
            std::ptr::null_mut(),
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL,
            std::ptr::null_mut(),
        )
    };
    if handle as isize == -1 {
        return Err(io::Error::last_os_error());
    }
    Ok(unsafe { File::from_raw_handle(handle) })
}

fn argument(name: &str, default: &str) -> String {
    let args: Vec<String> = env::args().collect();
    args.windows(2)
        .find(|pair| pair[0] == name)
        .map(|pair| pair[1].clone())
        .unwrap_or_else(|| default.to_owned())
}

fn main() -> io::Result<()> {
    let name = argument("--name", "kekkai0");
    #[cfg(unix)]
    let socket = argument("--bridge", "/var/run/kekkai/packet.sock");
    #[cfg(windows)]
    let socket = argument("--bridge", r"\\.\pipe\kekkai-packet");
    let device = open_tun(&name)?;
    run_bridge(device, &socket)
}

#[cfg(windows)]
fn run_bridge(_device: File, _socket_path: &str) -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "Windows named-pipe packet bridge requires the first-party NDIS package",
    ))
}

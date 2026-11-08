//! SSH transport: explicit key input, SHA256 key pin and a constant exec command.
//! No agent, key file, known-host file, environment credential or shell template.

use crate::{protocol, session};
use russh::keys::ssh_key::Fingerprint;
use russh::keys::{self, HashAlg, PrivateKeyWithHashAlg, PublicKeyOrCertificate};
use russh::{client, ChannelMsg};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use std::time::Duration;
use tokio::sync::{mpsc, watch};
use zeroize::Zeroizing;

pub struct ConnectOptions {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub private_key: Zeroizing<String>,
    pub passphrase: Zeroizing<String>,
    pub fingerprint: String,
    pub width: u32,
    pub height: u32,
    pub headless: bool,
    pub output: Option<String>,
}

impl ConnectOptions {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self
            .output
            .as_ref()
            .is_some_and(|name| !crate::desktop::valid_output(name))
            || (self.headless && self.output.is_some())
        {
            return Err("invalid mirror output selection");
        }
        if self.host.is_empty()
            || self.host.len() > 253
            || self
                .host
                .chars()
                .any(|c| c.is_whitespace() || c.is_control())
            || self.host.contains(['/', '\\', '@'])
            || self.port == 0
        {
            return Err("invalid SSH host or port");
        }
        if self.user.is_empty()
            || self.user.len() > 128
            || self.user.chars().any(|c| c.is_control())
        {
            return Err("invalid SSH user");
        }
        if self.private_key.len() > 65536 || self.passphrase.len() > 1024 {
            return Err("invalid explicit private key input");
        }
        if !protocol::valid_dimensions(self.width, self.height) {
            return Err("invalid decoder capabilities");
        }
        if !self.fingerprint.is_empty() {
            pin(&self.fingerprint)?;
        }
        Ok(())
    }
}

fn pin(s: &str) -> Result<Fingerprint, &'static str> {
    let fingerprint = s
        .parse::<Fingerprint>()
        .map_err(|_| "invalid SHA256 host fingerprint")?;
    if fingerprint.algorithm() != HashAlg::Sha256 || fingerprint.to_string() != s {
        return Err("host pin must use canonical SHA256 format");
    }
    Ok(fingerprint)
}

pub(crate) const HOST_KEY_DISCOVERY: &str = "SSH host key trust required";
struct PinnedHost {
    fingerprint: Option<Fingerprint>,
    discovered: Arc<AtomicBool>,
    rejected: Arc<AtomicBool>,
    verified: bool,
    events: Option<mpsc::Sender<Vec<u8>>>,
    approval_sent: Arc<AtomicBool>,
}
fn approval_url(banner: &str) -> Option<&str> {
    if banner.len() > 8192 {
        return None;
    }
    banner.split_whitespace().find_map(|word| {
        let url = word.trim_matches(|c: char| matches!(c, '<' | '>' | '(' | ')' | '\'' | '"'));
        (url.starts_with("https://login.tailscale.com/a/")
            && url.len() <= 2048
            && url.is_ascii()
            && !url.contains(['\\', '<', '>', '"', '\'']))
        .then_some(url)
    })
}
impl PinnedHost {
    fn accepts(&self, key: &PublicKeyOrCertificate) -> bool {
        // Certificate trust needs separate CA/principal/expiry rules, so fail closed.
        matches!(key, PublicKeyOrCertificate::PublicKey { .. })
            && Some(key.public_key().fingerprint(HashAlg::Sha256)) == self.fingerprint
    }
}
impl client::Handler for PinnedHost {
    type Error = russh::Error;
    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        if self.fingerprint.is_none() && matches!(key, PublicKeyOrCertificate::PublicKey { .. }) {
            let observed = key.public_key().fingerprint(HashAlg::Sha256).to_string();
            if let Some(events) = &self.events {
                let _ = events.try_send(session::text_event(8, &observed));
            }
            self.discovered.store(true, Ordering::Relaxed);
            // Discovery rejects KEX before any user authentication or exec.
            return Ok(false);
        }
        let accepted = self.accepts(key);
        self.verified = accepted;
        if !accepted {
            self.rejected.store(true, Ordering::Relaxed);
        }
        Ok(accepted)
    }
    async fn auth_banner(
        &mut self,
        banner: &str,
        _session: &mut client::Session,
    ) -> Result<(), Self::Error> {
        // Tailscale check mode holds `none` authentication and sends its approval
        // link in SSH_MSG_USERAUTH_BANNER. Never open a browser automatically.
        if self.verified && !self.approval_sent.load(Ordering::Relaxed) {
            if let (Some(events), Some(url)) = (&self.events, approval_url(banner)) {
                if events.try_send(session::text_event(7, url)).is_ok() {
                    self.approval_sent.store(true, Ordering::Relaxed);
                }
            }
        }
        Ok(())
    }
}

// russh spawns its KEX loop before returning a Handle. Cancelling the connect
// future alone can detach that loop, so a lifetime guard also fails its IO and
// wakes any pending read/write. This covers cancellation before a Handle exists.
struct ShutdownState {
    closed: bool,
    read: Option<std::task::Waker>,
    write: Option<std::task::Waker>,
}
struct IoLifetime(Arc<std::sync::Mutex<ShutdownState>>);
struct ControlledIo<S> {
    io: S,
    state: Arc<std::sync::Mutex<ShutdownState>>,
}
fn controlled_io<S>(io: S) -> (ControlledIo<S>, IoLifetime) {
    let state = Arc::new(std::sync::Mutex::new(ShutdownState {
        closed: false,
        read: None,
        write: None,
    }));
    (
        ControlledIo {
            io,
            state: state.clone(),
        },
        IoLifetime(state),
    )
}
impl Drop for IoLifetime {
    fn drop(&mut self) {
        let (read, write) = {
            let mut state = self.0.lock().unwrap_or_else(|p| p.into_inner());
            state.closed = true;
            (state.read.take(), state.write.take())
        };
        if let Some(w) = read {
            w.wake();
        }
        if let Some(w) = write {
            w.wake();
        }
    }
}
impl<S> ControlledIo<S> {
    fn closed(&self, cx: &std::task::Context<'_>, write: bool) -> bool {
        let mut state = self.state.lock().unwrap_or_else(|p| p.into_inner());
        if state.closed {
            return true;
        }
        if write {
            state.write = Some(cx.waker().clone());
        } else {
            state.read = Some(cx.waker().clone());
        }
        false
    }
}
impl<S: tokio::io::AsyncRead + Unpin> tokio::io::AsyncRead for ControlledIo<S> {
    fn poll_read(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &mut tokio::io::ReadBuf<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.closed(cx, false) {
            return std::task::Poll::Ready(Err(std::io::ErrorKind::ConnectionAborted.into()));
        }
        std::pin::Pin::new(&mut self.io).poll_read(cx, buf)
    }
}
impl<S: tokio::io::AsyncWrite + Unpin> tokio::io::AsyncWrite for ControlledIo<S> {
    fn poll_write(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
        buf: &[u8],
    ) -> std::task::Poll<std::io::Result<usize>> {
        if self.closed(cx, true) {
            return std::task::Poll::Ready(Err(std::io::ErrorKind::ConnectionAborted.into()));
        }
        std::pin::Pin::new(&mut self.io).poll_write(cx, buf)
    }
    fn poll_flush(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        if self.closed(cx, true) {
            return std::task::Poll::Ready(Err(std::io::ErrorKind::ConnectionAborted.into()));
        }
        std::pin::Pin::new(&mut self.io).poll_flush(cx)
    }
    fn poll_shutdown(
        mut self: std::pin::Pin<&mut Self>,
        cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<std::io::Result<()>> {
        // A cancelled transport closes without trying to write additional bytes.
        if self.closed(cx, true) {
            return std::task::Poll::Ready(Ok(()));
        }
        std::pin::Pin::new(&mut self.io).poll_shutdown(cx)
    }
}

// Also exercised with an in-memory duplex stream, without opening a listener.
async fn establish<S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static>(
    stream: S,
    options: &ConnectOptions,
    key: Option<keys::PrivateKey>,
    rejected: Arc<AtomicBool>,
    events: Option<mpsc::Sender<Vec<u8>>>,
) -> Result<
    (
        client::Handle<PinnedHost>,
        russh::ChannelStream<client::Msg>,
    ),
    &'static str,
> {
    let config = client::Config {
        window_size: 256 * 1024,
        maximum_packet_size: 32768,
        channel_buffer_size: 16,
        keepalive_interval: Some(Duration::from_secs(10)),
        keepalive_max: 3,
        inactivity_timeout: Some(Duration::from_secs(60)),
        nodelay: true,
        ..Default::default()
    };
    let discovered = Arc::new(AtomicBool::new(false));
    let approval_sent = Arc::new(AtomicBool::new(false));
    let handler = PinnedHost {
        fingerprint: if options.fingerprint.is_empty() {
            None
        } else {
            Some(pin(&options.fingerprint)?)
        },
        discovered: discovered.clone(),
        rejected: rejected.clone(),
        verified: false,
        events,
        approval_sent: approval_sent.clone(),
    };
    let mut handle = tokio::time::timeout(
        Duration::from_secs(10),
        client::connect_stream(Arc::new(config), stream, handler),
    )
    .await
    .map_err(|_| "SSH key exchange timed out")?
    .map_err(|_| {
        if discovered.load(Ordering::Relaxed) {
            HOST_KEY_DISCOVERY
        } else if rejected.load(Ordering::Relaxed) {
            "SSH host fingerprint mismatch or unsupported certificate"
        } else {
            "SSH connection or key exchange failed"
        }
    })?;
    if let Some(key) = key {
        let hash = if key.algorithm().is_rsa() {
            Some(
                handle
                    .best_supported_rsa_hash()
                    .await
                    .map_err(|_| "RSA signature negotiation failed")?
                    .flatten()
                    .ok_or("server does not support RSA SHA2 signatures")?,
            )
        } else {
            None
        };
        let auth = handle
            .authenticate_publickey(
                options.user.as_str(),
                PrivateKeyWithHashAlg::new(Arc::new(key), hash),
            )
            .await
            .map_err(|_| "SSH public-key authentication failed")?;
        if !auth.success() {
            return Err("SSH public-key authentication rejected");
        }
    } else {
        // Tailscale SSH authenticates the peer's tailnet identity, using SSH
        // method `none`; this is server authorization, not an accept-any bypass.
        let authentication = handle.authenticate_none(options.user.as_str());
        tokio::pin!(authentication);
        let result = match tokio::time::timeout(Duration::from_secs(12), &mut authentication).await
        {
            Ok(result) => result,
            // Keep the SAME authentication pending while the human approves it.
            // The outer setup deadline and cancellation still bound this wait.
            Err(_) if approval_sent.load(Ordering::Relaxed) => authentication.await,
            Err(_) => return Err("SSH authentication response timed out"),
        }
        .map_err(|_| "Tailscale SSH authentication failed")?;
        if !result.success() {
            return Err(
                "Tailscale SSH rejected keyless authentication; check tailnet policy and user",
            );
        }
    }
    let mut channel = handle
        .channel_open_session()
        .await
        .map_err(|_| "SSH session channel failed")?;
    let command = if options.headless {
        crate::HEADLESS_SERVER_COMMAND.to_string()
    } else if let Some(name) = &options.output {
        // Validated connector identity, never a Kotlin command or shell fragment.
        format!("gliff-server --stdio --output {} --low-bandwidth", name)
    } else {
        crate::SERVER_COMMAND.to_string()
    };
    channel
        .exec(true, command)
        .await
        .map_err(|_| "remote server exec request failed")?;
    // Do not discard an exec failure by blindly converting to an IO stream.
    loop {
        match channel.wait().await {
            Some(ChannelMsg::Success) => break,
            Some(ChannelMsg::ExtendedData { .. }) => {} // drain, do not log stderr
            _ => return Err("remote gliff-server exec was rejected or closed"),
        }
    }
    Ok((handle, channel.into_stream()))
}

async fn setup_or_cancel<T>(
    setup: impl std::future::Future<Output = Result<T, &'static str>>,
    cancel: &mut watch::Receiver<bool>,
    timeout: Duration,
) -> Result<Option<T>, &'static str> {
    tokio::select! {
        biased;
        _ = session::cancelled(cancel) => Ok(None),
        result = tokio::time::timeout(timeout,setup) => result.map_err(|_|"SSH setup timed out")?.map(Some),
    }
}

pub async fn run(
    options: ConnectOptions,
    commands: mpsc::Receiver<session::Control>,
    events: mpsc::Sender<Vec<u8>>,
    cancel: watch::Receiver<bool>,
) -> Result<(), &'static str> {
    let (_tx, rx) = mpsc::channel(crate::desktop::QUEUE);
    run_with_desktop(options, commands, rx, events, cancel).await
}

pub(crate) async fn run_with_desktop(
    options: ConnectOptions,
    commands: mpsc::Receiver<session::Control>,
    actions: mpsc::Receiver<crate::desktop::Action>,
    events: mpsc::Sender<Vec<u8>>,
    mut cancel: watch::Receiver<bool>,
) -> Result<(), &'static str> {
    options.validate()?;
    let rejected = Arc::new(AtomicBool::new(false));
    let mut io_lifetime = None;
    let setup = async {
        // Encrypted-key KDF parameters can demand attacker-sized CPU/memory. Keep
        // this first bridge unencrypted-key-only instead of running an unbounded KDF.
        if !options.passphrase.is_empty() {
            return Err("encrypted private keys are not supported yet");
        }
        let key = if options.private_key.is_empty() {
            None
        } else {
            Some(
                keys::decode_secret_key(options.private_key.as_str(), None)
                    .map_err(|_| "private key format unsupported or key is encrypted")?,
            )
        };
        let mut attempt = 0u32;
        loop {
            attempt += 1;
            // A stale SYN/KEX can outlive host wake-up. Replace the connection
            // instead of making the user cancel and reopen the whole session.
            let result = async {
                let stream = tokio::time::timeout(
                    Duration::from_secs(8),
                    tokio::net::TcpStream::connect((options.host.as_str(), options.port)),
                )
                .await
                .map_err(|_| "SSH TCP connection timed out")?
                .map_err(|_| "SSH TCP connection failed")?;
                stream
                    .set_nodelay(true)
                    .map_err(|_| "SSH TCP setup failed")?;
                let (stream, lifetime) = controlled_io(stream);
                io_lifetime = Some(lifetime);
                establish(
                    stream,
                    &options,
                    key.clone(),
                    rejected.clone(),
                    Some(events.clone()),
                )
                .await
            }
            .await;
            match result {
                Ok(connection) => break Ok(connection),
                Err(error)
                    if matches!(
                        error,
                        "SSH TCP connection timed out"
                            | "SSH TCP connection failed"
                            | "SSH key exchange timed out"
                            | "SSH connection or key exchange failed"
                            | "SSH authentication response timed out"
                            | "Tailscale SSH authentication failed"
                    ) =>
                {
                    // Terminate any detached russh KEX IO before retrying.
                    drop(io_lifetime.take());
                    let _ = events.try_send(session::text_event(
                        1,
                        &format!("Connecting… waiting for host or network (attempt {attempt})"),
                    ));
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
                Err(error) => break Err(error), // trust, auth and exec failures are terminal
            }
        }
    };
    let Some((handle, stream)) =
        setup_or_cancel(setup, &mut cancel, Duration::from_secs(180)).await?
    else {
        return Ok(());
    };
    // Drop plaintext key copies before entering the long-running protocol loop.
    let width = options.width;
    let height = options.height;
    drop(options);
    let result = tokio::select! {
        result = session::run(stream, width, height, commands, events.clone(), cancel.clone()) => result,
        _ = async {
            crate::desktop::run(&handle, actions, events.clone(), cancel.clone()).await;
            std::future::pending::<()>().await;
        } => unreachable!(),
    };
    let _ = tokio::time::timeout(
        Duration::from_millis(300),
        handle.disconnect(russh::Disconnect::ByApplication, "session closed", "en"),
    )
    .await;
    drop(io_lifetime);
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pin_rejects_missing_noncanonical_and_changed_fingerprints() {
        assert!(pin("").is_err());
        assert!(pin("SHA256:abc").is_err());
        assert!(pin("MD5:00:11").is_err());
        let key = keys::PublicKey::from_openssh(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIE5Kb13sQHbG2anYstRxsE3Ge6mOG1DJJ6dRQPUpQq0f",
        )
        .unwrap();
        let fp = key.fingerprint(HashAlg::Sha256);
        assert_eq!(pin(&fp.to_string()).unwrap(), fp);
        assert!(pin(&(fp.to_string() + " ")).is_err());
        let host = PinnedHost {
            fingerprint: Some(fp),
            discovered: Arc::new(AtomicBool::new(false)),
            rejected: Arc::new(AtomicBool::new(false)),
            verified: false,
            events: None,
            approval_sent: Arc::new(AtomicBool::new(false)),
        };
        assert!(host.accepts(&key.into()));
        let other = keys::PublicKey::from_openssh(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        )
        .unwrap();
        assert!(!host.accepts(&other.into()));
    }
}

#[cfg(test)]
mod ssh_tests {
    use super::*;
    use russh::server::ChannelOpenHandle;
    use russh::{server, Channel, ChannelId};
    use std::sync::atomic::AtomicUsize;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    struct TestServer {
        allowed: keys::PublicKey,
        allow_none: bool,
        auth_calls: Arc<AtomicUsize>,
        commands: Arc<std::sync::Mutex<Vec<Vec<u8>>>>,
        channel: Option<Channel<server::Msg>>,
    }
    impl server::Handler for TestServer {
        type Error = russh::Error;
        async fn auth_none(&mut self, user: &str) -> Result<server::Auth, Self::Error> {
            self.auth_calls.fetch_add(1, Ordering::Relaxed);
            Ok(if self.allow_none && user == "test-user" {
                server::Auth::Accept
            } else {
                server::Auth::reject()
            })
        }
        async fn authentication_banner(&mut self) -> Result<Option<String>, Self::Error> {
            Ok(self.allow_none.then(|| {
                "To authenticate, visit: https://login.tailscale.com/a/test-approval".into()
            }))
        }
        async fn auth_publickey(
            &mut self,
            user: &str,
            key: server::VerifiedPublicKey<'_>,
        ) -> Result<server::Auth, Self::Error> {
            self.auth_calls.fetch_add(1, Ordering::Relaxed);
            Ok(if user == "test-user" && key.public_key == &self.allowed {
                server::Auth::Accept
            } else {
                server::Auth::reject()
            })
        }
        async fn channel_open_session(
            &mut self,
            channel: Channel<server::Msg>,
            reply: ChannelOpenHandle,
            _session: &mut server::Session,
        ) -> Result<(), Self::Error> {
            self.channel = Some(channel);
            reply.accept().await;
            Ok(())
        }
        async fn exec_request(
            &mut self,
            channel: ChannelId,
            data: &[u8],
            session: &mut server::Session,
        ) -> Result<(), Self::Error> {
            self.commands.lock().unwrap().push(data.to_vec());
            session.channel_success(channel)?;
            // Extended data must not corrupt the protocol byte stream.
            session.extended_data(channel, 1, b"not protocol".to_vec())?;
            session.data(channel, b"ready".to_vec())?;
            Ok(())
        }
        async fn data(
            &mut self,
            channel: ChannelId,
            data: &[u8],
            session: &mut server::Session,
        ) -> Result<(), Self::Error> {
            session.data(channel, data.to_vec())?;
            Ok(())
        }
    }
    fn test_key(seed: u8) -> keys::PrivateKey {
        // Synthetic deterministic fixtures, never imported credentials.
        keys::ssh_key::private::Ed25519Keypair::from_seed(&[seed; 32]).into()
    }

    #[tokio::test]
    async fn in_memory_ssh_checks_pin_before_auth_and_uses_only_fixed_exec() {
        for correct_pin in [true, false] {
            let host = test_key(11);
            let auth = test_key(22);
            let expected = if correct_pin {
                host.public_key().fingerprint(HashAlg::Sha256)
            } else {
                test_key(33).public_key().fingerprint(HashAlg::Sha256)
            };
            let config = server::Config {
                keys: vec![host],
                auth_rejection_time: Duration::from_millis(1),
                auth_rejection_time_initial: Some(Duration::from_millis(1)),
                ..Default::default()
            };
            let auth_calls = Arc::new(AtomicUsize::new(0));
            let commands = Arc::new(std::sync::Mutex::new(Vec::new()));
            let handler = TestServer {
                allowed: auth.public_key().clone(),
                allow_none: false,
                auth_calls: auth_calls.clone(),
                commands: commands.clone(),
                channel: None,
            };
            let (client_io, server_io) = tokio::io::duplex(262144);
            let server_task = tokio::spawn(async move {
                if let Ok(running) = server::run_stream(Arc::new(config), server_io, handler).await
                {
                    let _ = running.await;
                }
            });
            let options = ConnectOptions {
                host: "memory-only".into(),
                port: 22,
                user: "test-user".into(),
                private_key: Zeroizing::new(String::new()),
                passphrase: Zeroizing::new(String::new()),
                fingerprint: expected.to_string(),
                width: 1280,
                height: 720,
                headless: false,
                output: None,
            };
            let rejected = Arc::new(AtomicBool::new(false));
            let result = tokio::time::timeout(
                Duration::from_secs(10),
                establish(client_io, &options, Some(auth), rejected.clone(), None),
            )
            .await
            .unwrap();
            if correct_pin {
                let (handle, mut stream) = result.unwrap();
                let mut ready = [0; 5];
                stream.read_exact(&mut ready).await.unwrap();
                assert_eq!(&ready, b"ready");
                stream.write_all(b"echo").await.unwrap();
                let mut echo = [0; 4];
                stream.read_exact(&mut echo).await.unwrap();
                assert_eq!(&echo, b"echo");
                assert_eq!(auth_calls.load(Ordering::Relaxed), 1);
                assert_eq!(
                    *commands.lock().unwrap(),
                    vec![crate::SERVER_COMMAND.as_bytes().to_vec()]
                );
                let _ = handle
                    .disconnect(russh::Disconnect::ByApplication, "done", "en")
                    .await;
            } else {
                assert!(result.is_err());
                assert!(rejected.load(Ordering::Relaxed));
                assert_eq!(auth_calls.load(Ordering::Relaxed), 0);
                assert!(commands.lock().unwrap().is_empty());
            }
            server_task.abort();
            let _ = server_task.await;
        }
    }
    #[tokio::test]
    async fn tailscale_none_auth_requires_pin_and_server_acceptance_and_forwards_approval() {
        for (correct_pin, allow_none) in [(true, true), (false, true), (true, false)] {
            let host = test_key(44);
            let expected = if correct_pin {
                host.public_key().fingerprint(HashAlg::Sha256)
            } else {
                test_key(55).public_key().fingerprint(HashAlg::Sha256)
            };
            let config = server::Config {
                keys: vec![host],
                auth_rejection_time: Duration::from_millis(1),
                auth_rejection_time_initial: Some(Duration::from_millis(1)),
                ..Default::default()
            };
            let auth_calls = Arc::new(AtomicUsize::new(0));
            let commands = Arc::new(std::sync::Mutex::new(Vec::new()));
            let handler = TestServer {
                allowed: test_key(66).public_key().clone(),
                allow_none,
                auth_calls: auth_calls.clone(),
                commands: commands.clone(),
                channel: None,
            };
            let (client_io, server_io) = tokio::io::duplex(262144);
            let server_task = tokio::spawn(async move {
                if let Ok(running) = server::run_stream(Arc::new(config), server_io, handler).await
                {
                    let _ = running.await;
                }
            });
            let options = ConnectOptions {
                host: "memory-only".into(),
                port: 22,
                user: "test-user".into(),
                private_key: Zeroizing::new(String::new()),
                passphrase: Zeroizing::new(String::new()),
                fingerprint: expected.to_string(),
                width: 1280,
                height: 720,
                headless: false,
                output: None,
            };
            assert!(options.validate().is_ok());
            let (events, mut output) = mpsc::channel(4);
            let result = tokio::time::timeout(
                Duration::from_secs(2),
                establish(
                    client_io,
                    &options,
                    None,
                    Arc::new(AtomicBool::new(false)),
                    Some(events),
                ),
            )
            .await
            .unwrap();
            if correct_pin && allow_none {
                let (handle, _stream) = result.unwrap();
                assert_eq!(auth_calls.load(Ordering::Relaxed), 1);
                assert_eq!(
                    *commands.lock().unwrap(),
                    vec![crate::SERVER_COMMAND.as_bytes().to_vec()]
                );
                assert_eq!(
                    output.try_recv().unwrap(),
                    session::text_event(7, "https://login.tailscale.com/a/test-approval")
                );
                let _ = handle
                    .disconnect(russh::Disconnect::ByApplication, "done", "en")
                    .await;
            } else {
                assert!(result.is_err());
                assert!(commands.lock().unwrap().is_empty());
                assert_eq!(auth_calls.load(Ordering::Relaxed), usize::from(correct_pin));
                assert!(output.try_recv().is_err());
            }
            server_task.abort();
            let _ = server_task.await;
        }
    }
    #[test]
    fn auth_approval_links_require_explicit_https_tailscale_login_origin() {
        assert_eq!(
            approval_url("visit https://login.tailscale.com/a/test"),
            Some("https://login.tailscale.com/a/test")
        );
        for bad in [
            "http://login.tailscale.com/a/test",
            "https://login.tailscale.com.evil/a/test",
            "https://login.tailscale.com@evil/a/test",
            "https://evil/a/test",
            "https://login.tailscale.com/a/test\\evil",
        ] {
            assert_eq!(approval_url(bad), None);
        }
    }
}

#[cfg(test)]
mod cancellation_tests {
    use super::*;
    use std::{future::Future, task::Poll};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[tokio::test]
    async fn cancellation_interrupts_stalled_ssh_setup_and_drops_stream() {
        let key: keys::PrivateKey =
            keys::ssh_key::private::Ed25519Keypair::from_seed(&[99; 32]).into();
        let options = ConnectOptions {
            host: "memory-only".into(),
            port: 22,
            user: "test-user".into(),
            private_key: Zeroizing::new(String::new()),
            passphrase: Zeroizing::new(String::new()),
            fingerprint: key.public_key().fingerprint(HashAlg::Sha256).to_string(),
            width: 1280,
            height: 720,
            headless: false,
            output: None,
        };
        for after_banner in [false, true] {
            let (client, mut server) = tokio::io::duplex(65536);
            let (client, lifetime) = controlled_io(client);
            let (cancel, mut cancel_rx) = watch::channel(false);
            let setup = establish(
                client,
                &options,
                Some(key.clone()),
                Arc::new(AtomicBool::new(false)),
                None,
            );
            let mut task = Box::pin(setup_or_cancel(
                setup,
                &mut cancel_rx,
                Duration::from_secs(25),
            ));
            std::future::poll_fn(|cx| match task.as_mut().poll(cx) {
                Poll::Pending => Poll::Ready(()),
                Poll::Ready(_) => panic!("SSH peer should be stalled"),
            })
            .await;
            let mut banner = [0; 4096];
            assert!(server.read(&mut banner).await.unwrap() > 0);
            if after_banner {
                // This crosses russh's background-task spawn and stalls inside KEX.
                server.write_all(b"SSH-2.0-memory-test\r\n").await.unwrap();
                std::future::poll_fn(|cx| match task.as_mut().poll(cx) {
                    Poll::Pending => Poll::Ready(()),
                    Poll::Ready(_) => panic!("KEX should be stalled"),
                })
                .await;
                assert!(
                    tokio::time::timeout(Duration::from_millis(500), server.read(&mut banner))
                        .await
                        .unwrap()
                        .unwrap()
                        > 0
                );
            }
            cancel.send(true).unwrap();
            assert!(matches!(
                tokio::time::timeout(Duration::from_millis(500), task)
                    .await
                    .unwrap(),
                Ok(None)
            ));
            drop(lifetime); // same lifetime boundary as production run's early return
            let mut remaining = Vec::new();
            tokio::time::timeout(
                Duration::from_millis(500),
                server.read_to_end(&mut remaining),
            )
            .await
            .unwrap()
            .unwrap();
            assert!(remaining.len() < 65536);
            assert!(server.write_all(b"SSH-2.0-test\r\n").await.is_err());
        }
    }
}

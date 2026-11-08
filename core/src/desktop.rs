//! Typed desktop actions over auxiliary channels of the verified SSH connection.
use russh::{client, ChannelMsg};
use std::time::Duration;
use tokio::sync::{mpsc, watch};

pub const QUEUE: usize = 8;
pub struct Action {
    pub kind: u32,
    pub identity: String,
}
impl Action {
    pub fn new(kind: i32, identity: String) -> Option<Self> {
        let valid = match kind {
            2 => {
                identity.starts_with("0x")
                    && (3..=18).contains(&identity.len())
                    && identity[2..].bytes().all(|b| b.is_ascii_hexdigit())
            }
            5 => {
                !identity.is_empty()
                    && identity.len() <= 9
                    && identity.bytes().all(|b| b.is_ascii_digit())
            }
            15 => !identity.is_empty() && identity.len() <= 16384 && !identity.contains('\0'),
            1 | 3 | 4 | 6..=14 => identity.is_empty(),
            _ => false,
        };
        valid.then_some(Self {
            kind: kind as u32,
            identity,
        })
    }
}
pub fn valid_output(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && name.as_bytes()[0].is_ascii_alphanumeric()
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_.:".contains(&b))
}
fn command(action: &Action) -> String {
    // Only the compile-time script is shell-quoted; identities are strict ASCII
    // address/decimal literals, never executable fragments or arbitrary strings.
    let script = include_str!("desktop.py").replace('\'', "'\\''");
    format!(
        "python3 -c '{}' {} {}",
        script,
        action.kind,
        if action.kind == 15 {
            ""
        } else {
            action.identity.as_str()
        }
    )
}
async fn execute<H: client::Handler>(
    handle: &client::Handle<H>,
    action: &Action,
) -> Result<Vec<u8>, &'static str> {
    let mut channel = tokio::time::timeout(Duration::from_secs(3), handle.channel_open_session())
        .await
        .map_err(|_| "desktop channel timed out")?
        .map_err(|_| "desktop channel failed")?;
    let result = tokio::time::timeout(Duration::from_secs(8), async {
        channel
            .exec(true, command(action))
            .await
            .map_err(|_| "desktop exec failed")?;
        let mut accepted = false;
        let mut status = None;
        let mut output = Vec::new();
        while let Some(message) = channel.wait().await {
            match message {
                ChannelMsg::Success => {
                    accepted = true;
                    if action.kind == 15 {
                        channel
                            .data(action.identity.as_bytes())
                            .await
                            .map_err(|_| "text input failed")?;
                    }
                    channel
                        .eof()
                        .await
                        .map_err(|_| "desktop stdin close failed")?;
                }
                ChannelMsg::Failure => return Err("desktop command rejected"),
                ChannelMsg::Data { data } => {
                    if output.len().saturating_add(data.len()) > 65536 {
                        return Err("desktop response too large");
                    }
                    output.extend_from_slice(&data);
                }
                ChannelMsg::ExitStatus { exit_status } => status = Some(exit_status),
                ChannelMsg::Close => break,
                _ => {} // stderr is intentionally not retained or exposed.
            }
        }
        if !accepted || status != Some(0) {
            return Err("desktop action unavailable or failed");
        }
        if action.kind == 1 {
            std::str::from_utf8(&output).map_err(|_| "invalid desktop metadata")?;
        }
        Ok(output)
    })
    .await
    .unwrap_or(Err("desktop action timed out"));
    let _ = tokio::time::timeout(Duration::from_millis(200), channel.close()).await;
    result
}
pub async fn run<H: client::Handler>(
    handle: &client::Handle<H>,
    mut actions: mpsc::Receiver<Action>,
    events: mpsc::Sender<Vec<u8>>,
    mut cancel: watch::Receiver<bool>,
) {
    loop {
        tokio::select! {
            biased;
            _ = crate::session::cancelled(&mut cancel) => return,
            action = actions.recv() => {
                let Some(action) = action else { return; };
                let work = async {
                    let result = execute(handle, &action).await;
                    if action.kind == 1 {
                        if let Ok(data) = &result {
                            let mut event = Vec::with_capacity(data.len()+1); event.push(11); event.extend_from_slice(data);
                            tokio::time::timeout(Duration::from_secs(2),events.send(event)).await.ok();
                        }
                    }
                    let mut event = vec![12]; event.extend_from_slice(&action.kind.to_le_bytes()); event.push(u8::from(result.is_ok()));
                    if let Err(message) = result { event.extend_from_slice(message.as_bytes()); }
                    tokio::time::timeout(Duration::from_secs(2),events.send(event)).await.ok();
                };
                tokio::select! { biased; _ = crate::session::cancelled(&mut cancel) => return, _ = work => {} }
            }
        }
    }
}

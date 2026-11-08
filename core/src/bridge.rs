//! Direct JNI bridge described in project INTERFACE.md. No retained JVM objects.

use crate::{
    session::{self, Control},
    transport::{self, ConnectOptions},
};
use jni::{
    objects::{JObject, JString},
    sys::{jboolean, jbyteArray, jdouble, jint, jlong, JNI_FALSE, JNI_TRUE},
    JNIEnv,
};
use std::collections::VecDeque;
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc, Mutex, OnceLock,
};
use tokio::{
    runtime::Runtime,
    sync::{mpsc, watch},
};
use zeroize::Zeroizing;

struct Entry {
    commands: mpsc::Sender<Control>,
    actions: mpsc::Sender<crate::desktop::Action>,
    events: Mutex<mpsc::Receiver<Vec<u8>>>,
    terminal: Mutex<VecDeque<Vec<u8>>>,
    cancel: watch::Sender<bool>,
}
struct Registry {
    entries: Mutex<Vec<(u64, Arc<Entry>)>>,
    active: AtomicBool,
    next_id: AtomicU64,
}
struct Registration {
    id: u64,
    entry: Arc<Entry>,
    commands: mpsc::Receiver<Control>,
    actions: mpsc::Receiver<crate::desktop::Action>,
    events: mpsc::Sender<Vec<u8>>,
    cancel: watch::Receiver<bool>,
}
static REGISTRY: Registry = Registry::new();
static RUNTIME: OnceLock<Result<Runtime, ()>> = OnceLock::new();

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|poison| poison.into_inner())
}
fn discard_events(entry: &Entry) -> Option<Vec<u8>> {
    let mut receiver = lock(&entry.events);
    receiver.close();
    let mut host_key = None;
    while let Ok(event) = receiver.try_recv() {
        // Preserve one bounded discovery event while releasing queued video.
        if event.first() == Some(&8) && event.len() <= 100 {
            host_key = Some(event);
        }
    }
    host_key
}
impl Registry {
    const fn new() -> Self {
        Self {
            entries: Mutex::new(Vec::new()),
            active: AtomicBool::new(false),
            next_id: AtomicU64::new(1),
        }
    }
    fn get(&self, id: jlong) -> Option<Arc<Entry>> {
        if id <= 0 {
            return None;
        }
        lock(&self.entries)
            .iter()
            .find(|(key, _)| *key == id as u64)
            .map(|(_, entry)| entry.clone())
    }
    fn register(&self) -> Option<Registration> {
        let mut entries = lock(&self.entries);
        if !entries.is_empty()
            || self
                .active
                .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                .is_err()
        {
            return None;
        }
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        if id > i64::MAX as u64 {
            self.active.store(false, Ordering::Release);
            return None;
        }
        let (commands, rx) = mpsc::channel(session::CONTROL_QUEUE);
        let (actions, action_rx) = mpsc::channel(crate::desktop::QUEUE);
        let (events, event_rx) = mpsc::channel(session::EVENT_QUEUE);
        let (cancel, cancel_rx) = watch::channel(false);
        let entry = Arc::new(Entry {
            commands,
            actions,
            events: Mutex::new(event_rx),
            terminal: Mutex::new(VecDeque::new()),
            cancel,
        });
        entries.push((id, entry.clone()));
        Some(Registration {
            id,
            entry,
            commands: rx,
            actions: action_rx,
            events,
            cancel: cancel_rx,
        })
    }
    fn finish(&self, entry: &Entry, result: Result<(), &'static str>) {
        // No completed worker retains queued video allocations waiting for poll.
        // Preserve only two bounded terminal events until explicit handle close.
        let host_key = discard_events(entry);
        let mut terminal = lock(&entry.terminal);
        terminal.clear();
        if let Err(error) = result {
            if error == transport::HOST_KEY_DISCOVERY {
                if let Some(event) = host_key {
                    terminal.push_back(event);
                }
            } else {
                terminal.push_back(session::text_event(5, error));
            }
        }
        terminal.push_back(vec![6]);
        self.active.store(false, Ordering::Release);
    }
    fn close(&self, id: jlong) {
        let mut entries = lock(&self.entries);
        if let Some(index) = entries.iter().position(|(key, _)| *key == id as u64) {
            let (_, entry) = entries.swap_remove(index);
            let _ = entry.cancel.send(true);
            let _ = discard_events(&entry);
            lock(&entry.terminal).clear();
        }
    }
}
fn entry(id: jlong) -> Option<Arc<Entry>> {
    REGISTRY.get(id)
}
fn start(options: ConnectOptions) -> Option<u64> {
    options.validate().ok()?;
    let runtime = RUNTIME
        .get_or_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
                .map_err(|_| ())
        })
        .as_ref()
        .ok()?;
    let Registration {
        id,
        entry,
        commands,
        actions,
        events,
        cancel,
    } = REGISTRY.register()?;
    runtime.spawn(async move {
        let _ = events.try_send(session::text_event(1, "Connecting with pinned host key"));
        let result = tokio::spawn(transport::run_with_desktop(
            options, commands, actions, events, cancel,
        ))
        .await
        .unwrap_or(Err("session worker failed"));
        REGISTRY.finish(&entry, result);
    });
    Some(id)
}

/// Java length is checked before JNI requests its modified-UTF8 representation.
/// The conversion then decodes CESU-8, including non-BMP clipboard characters.
fn string(env: &mut JNIEnv<'_>, value: &JString<'_>, max: usize) -> Option<String> {
    if value.is_null() {
        return None;
    }
    let len = env
        .call_method(value, "length", "()I", &[])
        .ok()?
        .i()
        .ok()?;
    if len < 0 || len as usize > max {
        return None;
    }
    let text: String = env.get_string(value).ok()?.into();
    (text.len() <= max).then_some(text)
}

fn open_session(
    mut env: JNIEnv<'_>,
    fields: [JString<'_>; 5],
    dimensions: [jint; 3],
    headless: bool,
    output: Option<JString<'_>>,
) -> jlong {
    let [host, user, private_key, passphrase, fingerprint] = fields;
    let [port, width, height] = dimensions;
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let options = ConnectOptions {
            host: string(&mut env, &host, 253)?,
            port: u16::try_from(port).ok()?,
            user: string(&mut env, &user, 128)?,
            private_key: Zeroizing::new(string(&mut env, &private_key, 65536)?),
            passphrase: Zeroizing::new(string(&mut env, &passphrase, 1024)?),
            fingerprint: string(&mut env, &fingerprint, 100)?,
            width: u32::try_from(width).ok()?,
            height: u32::try_from(height).ok()?,
            headless,
            output: match output {
                Some(ref name) => Some(string(&mut env, name, 128)?),
                None => None,
            },
        };
        start(options).map(|id| id as jlong)
    }))
    .ok()
    .flatten()
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_openSession(
    env: JNIEnv<'_>,
    _object: JObject<'_>,
    host: JString<'_>,
    port: jint,
    user: JString<'_>,
    private_key: JString<'_>,
    passphrase: JString<'_>,
    fingerprint: JString<'_>,
    width: jint,
    height: jint,
) -> jlong {
    open_session(
        env,
        [host, user, private_key, passphrase, fingerprint],
        [port, width, height],
        false,
        None,
    )
}

#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_openSessionWithMode(
    env: JNIEnv<'_>,
    _object: JObject<'_>,
    host: JString<'_>,
    port: jint,
    user: JString<'_>,
    private_key: JString<'_>,
    passphrase: JString<'_>,
    fingerprint: JString<'_>,
    width: jint,
    height: jint,
    headless: jboolean,
) -> jlong {
    if headless != JNI_FALSE && headless != JNI_TRUE {
        return 0;
    }
    open_session(
        env,
        [host, user, private_key, passphrase, fingerprint],
        [port, width, height],
        headless == JNI_TRUE,
        None,
    )
}

#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_pollEvent(
    env: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jbyteArray {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let entry = entry(handle)?;
        let event = lock(&entry.events)
            .try_recv()
            .ok()
            .or_else(|| lock(&entry.terminal).pop_front())?;
        env.byte_array_from_slice(&event)
            .ok()
            .map(|array| array.into_raw())
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_sendControl(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
    kind: jint,
    a: jdouble,
    b: jdouble,
    c: jint,
    d: jint,
) -> jboolean {
    std::panic::catch_unwind(|| {
        let entry = entry(handle)?;
        let command = Control::from_jni(kind, a, b, c, d)?;
        entry.commands.try_send(command).ok()?;
        Some(JNI_TRUE)
    })
    .ok()
    .flatten()
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_setClipboard(
    mut env: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
    text: JString<'_>,
) -> jboolean {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let entry = entry(handle)?;
        let text = string(&mut env, &text, crate::protocol::TEXT_LIMIT)?;
        entry.commands.try_send(Control::SetClipboard(text)).ok()?;
        Some(JNI_TRUE)
    }))
    .ok()
    .flatten()
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_requestClipboard(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) -> jboolean {
    std::panic::catch_unwind(|| {
        entry(handle)?
            .commands
            .try_send(Control::RequestClipboard)
            .ok()?;
        Some(JNI_TRUE)
    })
    .ok()
    .flatten()
    .unwrap_or(JNI_FALSE)
}

#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_closeSession(
    _env: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
) {
    let _ = std::panic::catch_unwind(|| REGISTRY.close(handle));
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn completed_handles_are_bounded_and_release_undrained_frames() {
        let registry = Registry::new();
        let mut previous = 0;
        for _ in 0..32 {
            let registration = registry.register().unwrap();
            let id = registration.id;
            assert!(id > previous);
            previous = id;
            // Fill every event slot without polling, as a slow Android consumer.
            for _ in 0..session::EVENT_QUEUE {
                registration.events.try_send(vec![3; 256 * 1024]).unwrap();
            }
            registry.finish(&registration.entry, Err("synthetic terminal failure"));
            assert!(lock(&registration.entry.events).try_recv().is_err());
            assert_eq!(lock(&registration.entry.terminal).len(), 2);
            for _ in 0..64 {
                assert!(registry.register().is_none());
            }
            assert_eq!(lock(&registry.entries).len(), 1);
            registry.close(id as jlong);
            registry.close(id as jlong); // idempotent
            assert!(registry.get(id as jlong).is_none());
            assert!(lock(&registration.entry.terminal).is_empty());
            assert!(lock(&registry.entries).is_empty());
        }
    }
    #[test]
    fn close_during_shutdown_blocks_replacement_until_worker_finishes() {
        let registry = Registry::new();
        let first = registry.register().unwrap();
        registry.close(first.id as jlong);
        assert!(*first.cancel.borrow());
        assert!(registry.register().is_none());
        registry.finish(&first.entry, Ok(()));
        let second = registry.register().unwrap();
        assert_ne!(first.id, second.id);
        registry.close(second.id as jlong);
        registry.finish(&second.entry, Ok(()));
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_remoteAction(
    mut env: JNIEnv<'_>,
    _object: JObject<'_>,
    handle: jlong,
    kind: jint,
    identity: JString<'_>,
) -> jboolean {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let entry = entry(handle)?;
        let identity = string(&mut env, &identity, if kind == 15 { 16384 } else { 128 })?;
        let action = crate::desktop::Action::new(kind, identity)?;
        entry.actions.try_send(action).ok()?;
        Some(JNI_TRUE)
    }))
    .ok()
    .flatten()
    .unwrap_or(JNI_FALSE)
}
#[no_mangle]
pub extern "system" fn Java_dev_omarchy_gliff_NativeCore_openSessionOnOutput(
    env: JNIEnv<'_>,
    _object: JObject<'_>,
    host: JString<'_>,
    port: jint,
    user: JString<'_>,
    key: JString<'_>,
    passphrase: JString<'_>,
    fingerprint: JString<'_>,
    width: jint,
    height: jint,
    output: JString<'_>,
) -> jlong {
    open_session(
        env,
        [host, user, key, passphrase, fingerprint],
        [port, width, height],
        false,
        Some(output),
    )
}

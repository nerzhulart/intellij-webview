// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

#![cfg(target_os = "windows")]

use std::{
    cell::RefCell,
    collections::{HashMap, VecDeque},
    ffi::c_void,
    rc::Rc,
    sync::{
        atomic::{AtomicBool, AtomicI64, AtomicU8, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::Instant,
};

use jni::{
    objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue},
    sys::{jboolean, jint, jlong, jstring},
    JNIEnv, JavaVM,
};
use webview2_com::{Microsoft::Web::WebView2::Win32::*, *};
use windows::{
    core::{w, IUnknown, Interface, HSTRING, PCWSTR, PWSTR},
    Win32::{
        Foundation::*,
        Graphics::{
            Direct3D::{D3D_DRIVER_TYPE, D3D_DRIVER_TYPE_HARDWARE, D3D_DRIVER_TYPE_WARP},
            Direct3D11::{
                D3D11CreateDevice, ID3D11Device, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                D3D11_SDK_VERSION,
            },
            DirectComposition::{
                DCompositionCreateDevice, IDCompositionDevice, IDCompositionRectangleClip,
                IDCompositionTarget, IDCompositionVisual,
            },
            Dxgi::IDXGIDevice,
            Gdi::{ScreenToClient, HBRUSH},
        },
        System::{Com::*, LibraryLoader::GetModuleHandleW, Threading::GetCurrentThreadId},
        UI::{
            Input::KeyboardAndMouse::{
                GetKeyState, SetFocus, VIRTUAL_KEY, VK_CONTROL, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
            },
            Shell::SHCreateMemStream,
            WindowsAndMessaging::*,
        },
    },
};

type BridgeResult<T> = std::result::Result<T, String>;
type NativeHandle = Rc<RefCell<NativeWebView>>;
type EventRegistrationToken = i64;

const MODIFIER_SHIFT: jint = 1;
const MODIFIER_CONTROL: jint = 1 << 1;
const MODIFIER_ALT: jint = 1 << 2;
const MODIFIER_META: jint = 1 << 3;
const NATIVE_ABI_VERSION: &str = "wvi-root-windowed-v18";
const WM_AWT_WEBVIEW_COMMAND: u32 = WM_APP + 0x35A;
/// Sent to the holder window to drain the command queue on AWT-Windows synchronously.
const WM_AWT_WEBVIEW_BARRIER: u32 = WM_APP + 0x35B;
const PARK_BARRIER_TIMEOUT_MILLIS: u32 = 2_000;
const WEBVIEW_ASSET_CUSTOM_SCHEME: &str = "ij-webview-asset";
const WEBVIEW_ASSET_CUSTOM_SCHEME_FILTER: &str = "ij-webview-asset://assets/*";
const WEBVIEW_ASSET_HTTPS_FILTER: &str = "https://ij-webview-assets.local/*";
const DIAGNOSTIC_TRACE: jint = 0;
const DIAGNOSTIC_DEBUG: jint = 1;
const DIAGNOSTIC_INFO: jint = 2;
const DIAGNOSTIC_WARN: jint = 3;
const DIAGNOSTIC_ERROR: jint = 4;
const PRESENTATION_ENV: &str = "WEBVIEW_WIN_PRESENTATION";
const COMPOSITION_POC_CLIP_ENV: &str = "WEBVIEW_WIN_COMPOSITION_POC_CLIP";
const COMPOSITION_POC_STALE_BOUNDS_ENV: &str = "WEBVIEW_WIN_COMPOSITION_POC_STALE_BOUNDS";

static NEXT_NATIVE_HANDLE: AtomicI64 = AtomicI64::new(1);
static TRANSPORT: OnceLock<Mutex<HookTransport>> = OnceLock::new();
static PRESENTATION_SELECTION: OnceLock<PresentationSelection> = OnceLock::new();
static PRESENTATION_WARNING_EMITTED: AtomicBool = AtomicBool::new(false);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum PresentationMode {
    Windowed,
    WindowedRootPoc,
    CompositionPoc,
    CompositionRootPoc,
}

impl PresentationMode {
    fn name(self) -> &'static str {
        match self {
            Self::Windowed => "windowed",
            Self::WindowedRootPoc => "windowed-root-poc",
            Self::CompositionPoc => "composition-poc",
            Self::CompositionRootPoc => "composition-root-poc",
        }
    }

    fn is_root_host(self) -> bool {
        matches!(self, Self::WindowedRootPoc | Self::CompositionRootPoc)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct PresentationSelection {
    mode: PresentationMode,
    warning: Option<String>,
    forced_clip: Option<(i32, i32)>,
    stale_controller_bounds: bool,
}

struct WindowedHost;

struct CompositionHost {
    // Retain the complete device chain for the lifetime of this view. DirectComposition itself
    // holds references, but explicit ownership makes teardown and later device-loss work clear.
    _d3d_device: ID3D11Device,
    _dxgi_device: IDXGIDevice,
    d3d_driver: &'static str,
    d3d_hardware_error: Option<String>,
    device: IDCompositionDevice,
    target: IDCompositionTarget,
    _container_visual: IDCompositionVisual,
    webview_visual: IDCompositionVisual,
    clip: IDCompositionRectangleClip,
    composition_controller: Option<ICoreWebView2CompositionController>,
    visual_clip_bounds: RECT,
    maximum_controller_bounds: RECT,
    commit_generation: u64,
}

enum PresentationHost {
    Windowed(WindowedHost),
    WindowedRootPoc(WindowedHost),
    CompositionPoc(CompositionHost),
    CompositionRootPoc(CompositionHost),
}

enum ControllerCompletedHandler {
    Windowed {
        _handler: ICoreWebView2CreateCoreWebView2ControllerCompletedHandler,
    },
    Composition {
        _handler: ICoreWebView2CreateCoreWebView2CompositionControllerCompletedHandler,
    },
}

impl PresentationHost {
    fn mode(&self) -> PresentationMode {
        match self {
            Self::Windowed(_) => PresentationMode::Windowed,
            Self::WindowedRootPoc(_) => PresentationMode::WindowedRootPoc,
            Self::CompositionPoc(_) => PresentationMode::CompositionPoc,
            Self::CompositionRootPoc(_) => PresentationMode::CompositionRootPoc,
        }
    }
}

fn parse_positive_size(value: &str) -> Option<(i32, i32)> {
    let (width, height) = value.trim().split_once(['x', 'X'])?;
    let width = width.trim().parse::<i32>().ok()?;
    let height = height.trim().parse::<i32>().ok()?;
    (width > 0 && height > 0).then_some((width, height))
}

fn parse_presentation_selection(
    presentation: Option<&str>,
    forced_clip: Option<&str>,
    stale_controller_bounds: Option<&str>,
) -> PresentationSelection {
    let mut warnings = Vec::new();
    let mode = match presentation.map(str::trim) {
        None | Some("") | Some("windowed-root-poc") => PresentationMode::WindowedRootPoc,
        Some("windowed") => PresentationMode::Windowed,
        Some("composition-poc") => PresentationMode::CompositionPoc,
        Some("composition-root-poc") => PresentationMode::CompositionRootPoc,
        Some(value) => {
            warnings.push(format!(
                "Invalid {PRESENTATION_ENV} value {value:?}; using windowed-root-poc"
            ));
            PresentationMode::WindowedRootPoc
        }
    };
    let forced_clip = if mode == PresentationMode::CompositionPoc {
        forced_clip.and_then(|value| match parse_positive_size(value) {
            Some(size) => Some(size),
            None => {
                warnings.push(format!(
                    "Invalid {COMPOSITION_POC_CLIP_ENV} value {value:?}; expected WIDTHxHEIGHT"
                ));
                None
            }
        })
    } else {
        None
    };
    let stale_controller_bounds = mode == PresentationMode::CompositionPoc
        && stale_controller_bounds.is_some_and(|value| value.trim() == "1");
    PresentationSelection {
        mode,
        warning: (!warnings.is_empty()).then(|| warnings.join("; ")),
        forced_clip,
        stale_controller_bounds,
    }
}

fn presentation_selection() -> &'static PresentationSelection {
    PRESENTATION_SELECTION.get_or_init(|| {
        let presentation = std::env::var(PRESENTATION_ENV).ok();
        let forced_clip = std::env::var(COMPOSITION_POC_CLIP_ENV).ok();
        let stale_controller_bounds = std::env::var(COMPOSITION_POC_STALE_BOUNDS_ENV).ok();
        parse_presentation_selection(
            presentation.as_deref(),
            forced_clip.as_deref(),
            stale_controller_bounds.as_deref(),
        )
    })
}

struct HookTransport {
    threads: HashMap<u32, HookThread>,
    routes: HashMap<jlong, Route>,
}

struct HookThread {
    queue: VecDeque<NativeCommand>,
    wake_pending: bool,
    get_message_hook: usize,
    call_wnd_proc_hook: usize,
}

#[derive(Clone, Copy)]
struct Route {
    owner_tid: u32,
    closing: bool,
    /// Holder window of this view's top-level root, the only window whose `wndproc` is ours.
    /// Read off the owner thread to address the park barrier.
    holder_hwnd: isize,
}

enum NativeCommand {
    Create {
        handle: jlong,
        host_hwnd: jlong,
        generation: jlong,
        user_data_dir: String,
        document_start_script: String,
        background_color: u32,
        callbacks: Arc<JavaCallbacks>,
    },
    Destroy {
        handle: jlong,
    },
    /// The whole Swing-side placement in one command. The native side reconciles it: the parent is
    /// re-set only when it really changed, and visibility is expressed as geometry.
    SetHostState {
        handle: jlong,
        host_hwnd: jlong,
        screen_x: i32,
        screen_y: i32,
        width: i32,
        height: i32,
        visible: bool,
        generation: jlong,
    },
    Park {
        handle: jlong,
        host_hwnd: jlong,
        generation: jlong,
        completion: Arc<AtomicU8>,
    },
    Focus {
        handle: jlong,
    },
    ClearFocus {
        handle: jlong,
    },
    LoadUrl {
        handle: jlong,
        url: String,
    },
    SetVirtualHostMapping {
        handle: jlong,
        host_name: String,
        folder_path: String,
    },
    LoadHtml {
        handle: jlong,
        html: String,
    },
    Evaluate {
        handle: jlong,
        eval_id: jlong,
        script: String,
    },
    CallDevTools {
        handle: jlong,
        call_id: jlong,
        method_name: String,
        params_json: String,
    },
    TransferToJs {
        handle: jlong,
        raw_json: String,
    },
    CompleteAsset {
        handle: jlong,
        request_id: u64,
        response: BridgeResult<Option<NativeAssetResponse>>,
    },
}

impl NativeCommand {
    fn handle(&self) -> jlong {
        match self {
            Self::Create { handle, .. }
            | Self::Destroy { handle }
            | Self::SetHostState { handle, .. }
            | Self::Park { handle, .. }
            | Self::Focus { handle }
            | Self::ClearFocus { handle }
            | Self::LoadUrl { handle, .. }
            | Self::SetVirtualHostMapping { handle, .. }
            | Self::LoadHtml { handle, .. }
            | Self::Evaluate { handle, .. }
            | Self::CallDevTools { handle, .. }
            | Self::TransferToJs { handle, .. }
            | Self::CompleteAsset { handle, .. } => *handle,
        }
    }
}

fn transport() -> &'static Mutex<HookTransport> {
    TRANSPORT.get_or_init(|| {
        Mutex::new(HookTransport {
            threads: HashMap::new(),
            routes: HashMap::new(),
        })
    })
}

fn register_route(host_hwnd: jlong) -> BridgeResult<(jlong, u32)> {
    let hwnd = HWND(host_hwnd as *mut c_void);
    let owner_tid = unsafe { GetWindowThreadProcessId(hwnd, None) };
    if owner_tid == 0 {
        return Err("Cannot resolve the AWT owner thread for the Canvas HWND".to_string());
    }

    let mut transport = transport()
        .lock()
        .map_err(|_| "WebView2 hook transport is poisoned".to_string())?;
    if !transport.threads.contains_key(&owner_tid) {
        let get_message_hook = unsafe {
            SetWindowsHookExW(
                WH_GETMESSAGE,
                Some(webview_get_message_hook),
                None,
                owner_tid,
            )
            .map_err(format_windows_error)?
        };
        let call_wnd_proc_hook = match unsafe {
            SetWindowsHookExW(
                WH_CALLWNDPROC,
                Some(webview_call_wnd_proc_hook),
                None,
                owner_tid,
            )
        } {
            Ok(hook) => hook,
            Err(error) => {
                unsafe {
                    let _ = UnhookWindowsHookEx(get_message_hook);
                }
                return Err(format_windows_error(error));
            }
        };
        transport.threads.insert(
            owner_tid,
            HookThread {
                queue: VecDeque::new(),
                wake_pending: false,
                get_message_hook: get_message_hook.0 as usize,
                call_wnd_proc_hook: call_wnd_proc_hook.0 as usize,
            },
        );
    }

    let handle = NEXT_NATIVE_HANDLE.fetch_add(1, Ordering::Relaxed).max(1);
    transport.routes.insert(
        handle,
        Route {
            owner_tid,
            closing: false,
            holder_hwnd: 0,
        },
    );
    Ok((handle, owner_tid))
}

fn enqueue_command(command: NativeCommand) -> BridgeResult<()> {
    let handle = command.handle();
    let mut transport = transport()
        .lock()
        .map_err(|_| "WebView2 hook transport is poisoned".to_string())?;
    let route = transport
        .routes
        .get(&handle)
        .copied()
        .ok_or_else(|| format!("Unknown WebView2 native handle: {handle}"))?;
    if route.closing {
        return Err("WebView2 native handle is closing".to_string());
    }
    let thread = transport
        .threads
        .get_mut(&route.owner_tid)
        .ok_or_else(|| "AWT-Windows hook transport is unavailable".to_string())?;
    thread.queue.push_back(command);
    if !thread.wake_pending {
        thread.wake_pending = true;
        if let Err(error) = unsafe {
            PostThreadMessageW(
                route.owner_tid,
                WM_AWT_WEBVIEW_COMMAND,
                WPARAM(0),
                LPARAM(0),
            )
        } {
            thread.wake_pending = false;
            let _ = thread.queue.pop_back();
            return Err(format_windows_error(error));
        }
    }
    Ok(())
}

fn enqueue_destroy(handle: jlong) -> BridgeResult<()> {
    let mut transport = transport()
        .lock()
        .map_err(|_| "WebView2 hook transport is poisoned".to_string())?;
    let Some(route) = transport.routes.get(&handle).copied() else {
        return Ok(());
    };
    if route.closing {
        return Ok(());
    }
    transport.routes.get_mut(&handle).unwrap().closing = true;

    let Some(thread) = transport.threads.get_mut(&route.owner_tid) else {
        transport.routes.get_mut(&handle).unwrap().closing = false;
        return Err("AWT-Windows hook transport is unavailable".to_string());
    };
    thread.queue.push_back(NativeCommand::Destroy { handle });
    if !thread.wake_pending {
        thread.wake_pending = true;
        if let Err(error) = unsafe {
            PostThreadMessageW(
                route.owner_tid,
                WM_AWT_WEBVIEW_COMMAND,
                WPARAM(0),
                LPARAM(0),
            )
        } {
            thread.wake_pending = false;
            let _ = thread.queue.pop_back();
            transport.routes.get_mut(&handle).unwrap().closing = false;
            return Err(format_windows_error(error));
        }
    }
    Ok(())
}

fn remove_route(handle: jlong) {
    let hooks = {
        let Ok(mut transport) = transport().lock() else {
            return;
        };
        let Some(route) = transport.routes.remove(&handle) else {
            return;
        };
        let owner_still_used = transport
            .routes
            .values()
            .any(|candidate| candidate.owner_tid == route.owner_tid);
        if owner_still_used {
            None
        } else {
            transport.threads.remove(&route.owner_tid)
        }
    };
    if let Some(thread) = hooks {
        unsafe {
            let _ = UnhookWindowsHookEx(HHOOK(thread.get_message_hook as *mut c_void));
            let _ = UnhookWindowsHookEx(HHOOK(thread.call_wnd_proc_hook as *mut c_void));
        }
    }
}

unsafe extern "system" fn webview_get_message_hook(
    code: i32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    let result = unsafe { CallNextHookEx(None, code, wparam, lparam) };
    if code >= 0 && lparam.0 != 0 {
        let message = unsafe { &mut *(lparam.0 as *mut MSG) };
        if message.message == WM_AWT_WEBVIEW_COMMAND && message.hwnd.0.is_null() {
            message.message = WM_NULL;
            drain_owner_commands(unsafe { GetCurrentThreadId() });
        }
    }
    result
}

unsafe extern "system" fn webview_call_wnd_proc_hook(
    code: i32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if code >= 0 && lparam.0 != 0 {
        let message = unsafe { &*(lparam.0 as *const CWPSTRUCT) };
        if message.message == WM_SIZE {
            resize_webview_from_canvas_wm_size(message.hwnd);
        }
    }
    unsafe { CallNextHookEx(None, code, wparam, lparam) }
}

fn resize_webview_from_canvas_wm_size(canvas: HWND) {
    let views = NATIVE_VIEWS.with(|views| {
        let Ok(views) = views.try_borrow() else {
            return None;
        };
        Some(
            views
                .values()
                .filter_map(|native| {
                    let view = native.try_borrow().ok()?;
                    (!view.presentation.mode().is_root_host() && view.canvas == canvas)
                        .then(|| native.clone())
                })
                .collect::<Vec<_>>(),
        )
    });
    let Some(views) = views else {
        return;
    };
    for native in views {
        let requested_bounds = canvas_client_bounds(canvas);
        let result = requested_bounds.and_then(|bounds| {
            apply_clip_geometry(&native, bounds)?;
            let extra = format!("bounds={}", format_rect(bounds));
            trace_placement("wm-size", &native, Some(&extra));
            Ok(())
        });
        if let Err(message) = result {
            emit_diagnostic(
                &native,
                DIAGNOSTIC_WARN,
                "placement.wm-size-hook.failed",
                message,
                diagnostic_data(vec![
                    ("canvasHwnd", (canvas.0 as usize as u64).to_string()),
                    ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
                ]),
            );
        }
    }
}

/// Placement diagnostics, off unless `WEBVIEW_WIN_PAINT_TRACE=1`. One snapshot per placement
/// decision is all the tracing there is, so the placement code itself stays one line per call site.
fn placement_trace_enabled() -> bool {
    static FLAG: OnceLock<bool> = OnceLock::new();
    *FLAG.get_or_init(|| {
        std::env::var("WEBVIEW_WIN_PAINT_TRACE")
            .is_ok_and(|value| value == "1" || value.eq_ignore_ascii_case("true"))
    })
}

fn format_rect(r: RECT) -> String {
    format!(
        "{},{} {}x{}",
        r.left,
        r.top,
        (r.right - r.left).max(0),
        (r.bottom - r.top).max(0)
    )
}

/// Reports the Canvas, bridge-owned clip host, and bounds WebView2 itself reports. State is
/// snapshotted before any Win32, COM, or JNI call so tracing cannot create a reentrant borrow.
fn trace_placement(tag: &str, native: &NativeHandle, extra: Option<&str>) {
    if !placement_trace_enabled() {
        return;
    }
    let (canvas, target_parent, clip_host, clip_parent, clip_bounds, controller, presentation_desc) = {
        let Ok(view) = native.try_borrow() else {
            return;
        };
        let presentation_desc = match &view.presentation {
            PresentationHost::Windowed(_) | PresentationHost::WindowedRootPoc(_) => {
                view.presentation.mode().name().to_string()
            }
            PresentationHost::CompositionPoc(host) | PresentationHost::CompositionRootPoc(host) => {
                format!(
                    "{}:d3dDriver={}:visualClip={}:commitGeneration={}",
                    view.presentation.mode().name(),
                    host.d3d_driver,
                    format_rect(host.visual_clip_bounds),
                    host.commit_generation,
                )
            }
        };
        (
            view.canvas,
            view.target_parent,
            view.clip_host,
            view.clip_parent,
            view.clip_bounds,
            view.controller.clone(),
            presentation_desc,
        )
    };

    let (canvas_desc, frame_desc) = if canvas.0.is_null() {
        ("none".to_string(), "none".to_string())
    } else {
        let mut client = RECT::default();
        let _ = unsafe { GetClientRect(canvas, &mut client) };
        let mut screen = RECT::default();
        let _ = unsafe { GetWindowRect(canvas, &mut screen) };
        let frame = unsafe { GetParent(canvas) }.unwrap_or_default();
        let mut frame_rect = RECT::default();
        let _ = unsafe { GetWindowRect(frame, &mut frame_rect) };
        (
            format!(
                "{}:{}:{}:{}",
                canvas.0 as usize,
                format_rect(screen),
                format_rect(client),
                unsafe { IsWindowVisible(canvas) }.as_bool()
            ),
            format!("{}:{}", frame.0 as usize, format_rect(frame_rect)),
        )
    };

    let mut clip_client = RECT::default();
    let _ = unsafe { GetClientRect(clip_host, &mut clip_client) };
    let mut clip_screen = RECT::default();
    let _ = unsafe { GetWindowRect(clip_host, &mut clip_screen) };
    let actual_clip_parent = unsafe { GetParent(clip_host) }.unwrap_or_default();
    let clip_desc = format!(
        "{}:{}:{}:parent={}",
        clip_host.0 as usize,
        format_rect(clip_screen),
        format_rect(clip_client),
        actual_clip_parent.0 as usize,
    );

    let controller_bounds = match controller.as_ref() {
        Some(controller) => {
            let mut bounds = RECT::default();
            if unsafe { controller.Bounds(&mut bounds) }.is_ok() {
                format_rect(bounds)
            } else {
                "error".to_string()
            }
        }
        None => "none".to_string(),
    };

    let mut message = format!(
        "placement.{} presentation={} canvas={} targetParent={} frame={} clipHost={} trackedClipParent={} trackedClipBounds={} controllerBounds={}",
        tag,
        presentation_desc,
        canvas_desc,
        target_parent.0 as usize,
        frame_desc,
        clip_desc,
        clip_parent.0 as usize,
        format_rect(clip_bounds),
        controller_bounds,
    );
    if let Some(extra) = extra {
        message.push(' ');
        message.push_str(extra);
    }

    emit_diagnostic(
        native,
        DIAGNOSTIC_TRACE,
        "placement.trace",
        message,
        String::new(),
    );
}

fn drain_owner_commands(owner_tid: u32) {
    let commands = {
        let Ok(mut transport) = transport().lock() else {
            return;
        };
        let Some(thread) = transport.threads.get_mut(&owner_tid) else {
            return;
        };
        thread.wake_pending = false;
        thread.queue.drain(..).collect::<Vec<_>>()
    };
    for command in commands {
        execute_native_command(command);
    }
}

struct NativeAssetResponse {
    status_code: i32,
    status_text: String,
    headers: String,
    bytes: Vec<u8>,
}

#[derive(Clone, PartialEq, Eq)]
struct EnvironmentKey {
    user_data_dir: String,
}

impl EnvironmentKey {
    fn new(user_data_dir: String) -> Self {
        Self { user_data_dir }
    }
}

struct JavaCallbacks {
    vm: JavaVM,
    object: GlobalRef,
}

impl JavaCallbacks {
    fn on_created(&self, handle: jlong) {
        self.with_env(|env, object| {
            env.call_method(object, "onCreated", "(J)V", &[JValue::Long(handle)])?;
            Ok(())
        });
    }

    fn on_create_failed(&self, message: String) {
        self.with_env(|env, object| {
            let message = JObject::from(env.new_string(message)?);
            env.call_method(
                object,
                "onCreateFailed",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&message)],
            )?;
            Ok(())
        });
    }

    fn on_destroyed(&self, handle: jlong) {
        self.with_env(|env, object| {
            env.call_method(object, "onDestroyed", "(J)V", &[JValue::Long(handle)])?;
            Ok(())
        });
    }

    fn on_message(&self, raw: String) {
        self.with_env(|env, object| {
            let raw = JObject::from(env.new_string(raw)?);
            env.call_method(
                object,
                "onMessage",
                "(Ljava/lang/String;)V",
                &[JValue::Object(&raw)],
            )?;
            Ok(())
        });
    }

    fn on_evaluation_result(&self, eval_id: jlong, result: String) {
        self.with_env(|env, object| {
            let result = JObject::from(env.new_string(result)?);
            env.call_method(
                object,
                "onEvaluationResult",
                "(JLjava/lang/String;)V",
                &[JValue::Long(eval_id), JValue::Object(&result)],
            )?;
            Ok(())
        });
    }

    fn on_evaluation_error(&self, eval_id: jlong, message: String) {
        self.with_env(|env, object| {
            let message = JObject::from(env.new_string(message)?);
            env.call_method(
                object,
                "onEvaluationError",
                "(JLjava/lang/String;)V",
                &[JValue::Long(eval_id), JValue::Object(&message)],
            )?;
            Ok(())
        });
    }

    fn on_dev_tools_protocol_method_result(
        &self,
        call_id: jlong,
        result: Option<String>,
        error: Option<String>,
    ) {
        self.with_env(|env, object| {
            let result = match result {
                Some(result) => JObject::from(env.new_string(result)?),
                None => JObject::null(),
            };
            let error = match error {
                Some(error) => JObject::from(env.new_string(error)?),
                None => JObject::null(),
            };
            env.call_method(
                object,
                "onDevToolsProtocolMethodResult",
                "(JLjava/lang/String;Ljava/lang/String;)V",
                &[
                    JValue::Long(call_id),
                    JValue::Object(&result),
                    JValue::Object(&error),
                ],
            )?;
            Ok(())
        });
    }

    fn on_accelerator_key_pressed(
        &self,
        key_event_kind: jint,
        virtual_key: jint,
        modifiers: jint,
        key_event_lparam: jint,
    ) -> bool {
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return false;
        };
        env.call_method(
            self.object.as_obj(),
            "onAcceleratorKeyPressed",
            "(IIII)Z",
            &[
                JValue::Int(key_event_kind),
                JValue::Int(virtual_key),
                JValue::Int(modifiers),
                JValue::Int(key_event_lparam),
            ],
        )
        .ok()
        .and_then(|value| value.z().ok())
        .unwrap_or(false)
    }

    fn on_focus_gained(&self) {
        self.with_env(|env, object| {
            env.call_method(object, "onFocusGained", "()V", &[])?;
            Ok(())
        });
    }

    #[allow(dead_code)]
    fn on_log(&self, level: jint, message: String) {
        self.with_env(|env, object| {
            let message = JObject::from(env.new_string(message)?);
            env.call_method(
                object,
                "onLog",
                "(ILjava/lang/String;)V",
                &[JValue::Int(level), JValue::Object(&message)],
            )?;
            Ok(())
        });
    }

    fn on_native_diagnostic(&self, level: jint, event: &str, message: String, data: String) {
        self.with_env(|env, object| {
            let event = JObject::from(env.new_string(event)?);
            let message = JObject::from(env.new_string(message)?);
            let data = JObject::from(env.new_string(data)?);
            env.call_method(
                object,
                "onNativeDiagnostic",
                "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                &[
                    JValue::Int(level),
                    JValue::Object(&event),
                    JValue::Object(&message),
                    JValue::Object(&data),
                ],
            )?;
            Ok(())
        });
    }

    fn on_asset_requested(&self, handle: jlong, request_id: u64, url: String) -> BridgeResult<()> {
        let mut env = self.vm.attach_current_thread().map_err(format_jni_error)?;
        let url = JObject::from(env.new_string(url).map_err(format_jni_error)?);
        env.call_method(
            self.object.as_obj(),
            "onAssetRequested",
            "(JJLjava/lang/String;)V",
            &[
                JValue::Long(handle),
                JValue::Long(request_id as jlong),
                JValue::Object(&url),
            ],
        )
        .map_err(format_jni_error)?;
        Ok(())
    }

    fn with_env<F>(&self, action: F)
    where
        F: FnOnce(&mut JNIEnv<'_>, &JObject<'_>) -> jni::errors::Result<()>,
    {
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return;
        };
        let _ = action(&mut env, self.object.as_obj());
    }
}

struct SharedEnvironmentState {
    key: EnvironmentKey,
    generation: u64,
    started_at: Instant,
    environment: Option<ICoreWebView2Environment>,
    creating: bool,
    waiters: Vec<NativeHandle>,
    active_views: Vec<NativeHandle>,
    environment_completed_handler:
        Option<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>,
}

#[derive(Default)]
struct SharedWebView2EnvironmentManager {
    state: Option<SharedEnvironmentState>,
    next_generation: u64,
}

enum SharedEnvironmentAction {
    None,
    StartEnvironment {
        user_data_dir: String,
        generation: u64,
        handler: ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler,
    },
    CreateController {
        environment: ICoreWebView2Environment,
        generation: u64,
    },
}

impl SharedWebView2EnvironmentManager {
    fn ensure_environment(
        &mut self,
        key: EnvironmentKey,
        native: NativeHandle,
    ) -> BridgeResult<SharedEnvironmentAction> {
        if let Some(state) = self.state.as_mut() {
            if state.key != key {
                return Err(format!(
                    "WebView2 shared environment already exists for another user data directory: requested={}, active={}",
                    key.user_data_dir, state.key.user_data_dir
                ));
            }
            if let Some(environment) = &state.environment {
                return Ok(SharedEnvironmentAction::CreateController {
                    environment: environment.clone(),
                    generation: state.generation,
                });
            }
            if state.creating {
                state.waiters.push(native);
                return Ok(SharedEnvironmentAction::None);
            }
        }

        let generation = self.next_generation;
        self.next_generation += 1;
        let handler = create_shared_environment_completed_handler(generation);
        self.state = Some(SharedEnvironmentState {
            key: key.clone(),
            generation,
            started_at: Instant::now(),
            environment: None,
            creating: true,
            waiters: vec![native],
            active_views: Vec::new(),
            environment_completed_handler: Some(handler.clone()),
        });
        Ok(SharedEnvironmentAction::StartEnvironment {
            user_data_dir: key.user_data_dir,
            generation,
            handler,
        })
    }

    fn clear_start_failure(&mut self, generation: u64) {
        if self
            .state
            .as_ref()
            .is_some_and(|state| state.generation == generation && state.creating)
        {
            self.state = None;
        }
    }

    fn complete_environment_failure(&mut self, generation: u64) -> Vec<NativeHandle> {
        let Some(state) = self.state.as_ref() else {
            return Vec::new();
        };
        if state.generation != generation {
            return Vec::new();
        }
        let state = self.state.take().unwrap();
        state.waiters
    }

    fn complete_environment_success(
        &mut self,
        generation: u64,
        environment: ICoreWebView2Environment,
    ) -> Vec<NativeHandle> {
        let Some(state) = self.state.as_mut() else {
            return Vec::new();
        };
        if state.generation != generation {
            return Vec::new();
        }
        state.environment = Some(environment);
        state.creating = false;
        state.environment_completed_handler = None;
        std::mem::take(&mut state.waiters)
    }

    fn register_active_view(&mut self, generation: u64, native: NativeHandle) -> bool {
        let Some(state) = self.state.as_mut() else {
            return false;
        };
        if state.generation != generation || is_native_destroyed(&native) {
            return false;
        }
        prune_destroyed_views(&mut state.active_views);
        let handle = native_handle(&native);
        if handle != 0
            && state
                .active_views
                .iter()
                .all(|view| native_handle(view) != handle)
        {
            state.active_views.push(native);
        }
        true
    }

    fn unregister_view(&mut self, handle: jlong) {
        let Some(state) = self.state.as_mut() else {
            return;
        };
        remove_view_handle(&mut state.waiters, handle);
        remove_view_handle(&mut state.active_views, handle);
    }

    fn active_views(&mut self, generation: u64) -> Vec<NativeHandle> {
        let Some(state) = self.state.as_mut() else {
            return Vec::new();
        };
        if state.generation != generation {
            return Vec::new();
        }
        prune_destroyed_views(&mut state.active_views);
        state.active_views.clone()
    }

    fn invalidate_environment(&mut self, generation: u64) -> Vec<NativeHandle> {
        let Some(state) = self.state.as_ref() else {
            return Vec::new();
        };
        if state.generation != generation {
            return Vec::new();
        }
        let mut state = self.state.take().unwrap();
        prune_destroyed_views(&mut state.waiters);
        prune_destroyed_views(&mut state.active_views);
        state.waiters.extend(state.active_views);
        state.waiters
    }

    fn is_current_generation(&self, generation: u64) -> bool {
        self.state
            .as_ref()
            .is_some_and(|state| state.generation == generation)
    }

    fn environment_started_at(&self, generation: u64) -> Option<Instant> {
        self.state.as_ref().and_then(|state| {
            if state.generation == generation {
                Some(state.started_at)
            } else {
                None
            }
        })
    }
}

thread_local! {
    static SHARED_ENVIRONMENT_MANAGER: RefCell<SharedWebView2EnvironmentManager> =
        RefCell::new(SharedWebView2EnvironmentManager::default());
    static NATIVE_VIEWS: RefCell<HashMap<jlong, NativeHandle>> = RefCell::new(HashMap::new());
}

struct PendingAssetRequest {
    environment: ICoreWebView2Environment,
    args: ICoreWebView2WebResourceRequestedEventArgs,
    deferral: ICoreWebView2Deferral,
}

#[derive(Clone, Copy)]
struct NavigationTiming {
    requested_at: Option<Instant>,
    started_at: Instant,
}

struct NativeWebView {
    handle: jlong,
    /// The AWT Canvas currently presenting the view, or the top-level HWND in the root-host PoC.
    /// Null means the ordinary Canvas path is parked.
    canvas: HWND,
    /// Desired parent of `clip_host`: the Canvas while attached, otherwise the parking holder.
    target_parent: HWND,
    /// Stable bridge-owned parent passed to WebView2 exactly once during controller creation.
    clip_host: HWND,
    /// Parent currently applied to `clip_host` through Win32 `SetParent`.
    clip_parent: HWND,
    /// Client rectangle currently applied to `clip_host` through `SetWindowPos`.
    clip_bounds: RECT,
    presentation: PresentationHost,
    controller: Option<ICoreWebView2Controller>,
    webview: Option<ICoreWebView2>,
    controller_completed_handler: Option<ControllerCompletedHandler>,
    controller_create_started_at: Option<Instant>,
    last_navigation_requested_at: Option<Instant>,
    navigation_timings: HashMap<u64, NavigationTiming>,
    unidentified_navigation_timing: Option<NavigationTiming>,
    add_script_handlers: Vec<(
        u64,
        ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler,
    )>,
    execute_script_handlers: Vec<(u64, ICoreWebView2ExecuteScriptCompletedHandler)>,
    dev_tools_handlers: Vec<(u64, ICoreWebView2CallDevToolsProtocolMethodCompletedHandler)>,
    pending_asset_requests: HashMap<u64, PendingAssetRequest>,
    next_asset_request_id: u64,
    next_script_handler_id: u64,
    document_start_scripts: Vec<String>,
    web_message_token: EventRegistrationToken,
    web_resource_requested_token: Option<EventRegistrationToken>,
    accelerator_key_pressed_token: Option<EventRegistrationToken>,
    got_focus_token: Option<EventRegistrationToken>,
    callbacks: Arc<JavaCallbacks>,
    destroyed: bool,
    /// Whether Swing shows the host. Pushed to the controller as `put_IsVisible`, so a host nobody
    /// looks at stops rendering and Chromium is free to freeze its renderer.
    visible: bool,
    /// Host background as ARGB, applied to the controller so that a frame without page content
    /// looks exactly like the Canvas behind it.
    background_color: u32,
    /// Bounds last pushed to the controller, so a repeated snapshot never becomes a resize.
    controller_bounds: RECT,
    /// Visibility last pushed to the controller. A freshly created controller is visible.
    controller_visible: bool,
    width: i32,
    height: i32,
    /// Physical screen position of the lightweight Swing host in the root-HWND PoC.
    screen_x: i32,
    screen_y: i32,
    host_generation: jlong,
}

struct DestroyResources {
    clip_host: HWND,
    presentation: PresentationHost,
    controller: Option<ICoreWebView2Controller>,
    webview: Option<ICoreWebView2>,
    accelerator_token: Option<EventRegistrationToken>,
    got_focus_token: Option<EventRegistrationToken>,
    web_resource_token: Option<EventRegistrationToken>,
    pending_asset_requests: HashMap<u64, PendingAssetRequest>,
}

fn destroy_native_state(native: &NativeHandle) -> BridgeResult<()> {
    let mut resources = {
        let mut view = native
            .try_borrow_mut()
            .map_err(|_| "WebView2 state is busy while collecting destroy resources".to_string())?;
        if view.destroyed {
            return Ok(());
        }
        view.destroyed = true;
        let resources = DestroyResources {
            clip_host: view.clip_host,
            presentation: std::mem::replace(
                &mut view.presentation,
                PresentationHost::Windowed(WindowedHost),
            ),
            controller: view.controller.take(),
            webview: view.webview.take(),
            accelerator_token: view.accelerator_key_pressed_token.take(),
            got_focus_token: view.got_focus_token.take(),
            web_resource_token: view.web_resource_requested_token.take(),
            pending_asset_requests: std::mem::take(&mut view.pending_asset_requests),
        };
        view.controller_completed_handler = None;
        view.add_script_handlers.clear();
        view.execute_script_handlers.clear();
        view.dev_tools_handlers.clear();
        // The Canvas belongs to AWT. Only the bridge-owned clip host is destroyed below.
        view.canvas = HWND::default();
        view.target_parent = HWND::default();
        view.clip_host = HWND::default();
        view.clip_parent = HWND::default();
        view.clip_bounds = RECT::default();
        resources
    };

    if let (Some(controller), Some(token)) = (&resources.controller, resources.accelerator_token) {
        unsafe {
            let _ = controller.remove_AcceleratorKeyPressed(token);
        }
    }
    if let (Some(controller), Some(token)) = (&resources.controller, resources.got_focus_token) {
        unsafe {
            let _ = controller.remove_GotFocus(token);
        }
    }
    if let (Some(webview), Some(token)) = (&resources.webview, resources.web_resource_token) {
        unsafe {
            let _ = webview.remove_WebResourceRequested(token);
        }
        remove_web_resource_requested_filter(webview, WEBVIEW_ASSET_CUSTOM_SCHEME_FILTER);
        remove_web_resource_requested_filter(webview, WEBVIEW_ASSET_HTTPS_FILTER);
    }
    for (_, request) in resources.pending_asset_requests {
        unsafe {
            let _ = request.deferral.Complete();
        }
    }
    let composition_controller = match &mut resources.presentation {
        PresentationHost::CompositionPoc(host) | PresentationHost::CompositionRootPoc(host) => {
            host.composition_controller.take()
        }
        PresentationHost::Windowed(_) | PresentationHost::WindowedRootPoc(_) => None,
    };
    if let Some(composition_controller) = &composition_controller {
        unsafe {
            let _ = composition_controller.SetRootVisualTarget(None::<&IUnknown>);
        }
    }
    // WebView2 owns native descendants of the clip host. Release the controller before destroying
    // that host so Chromium can tear its windows down in its normal order.
    drop(resources.webview.take());
    drop(resources.controller.take());
    drop(composition_controller);
    if let PresentationHost::CompositionPoc(host) | PresentationHost::CompositionRootPoc(host) =
        &resources.presentation
    {
        unsafe {
            let _ = host.target.SetRoot(None::<&IDCompositionVisual>);
            let _ = host.device.Commit();
        }
    }
    if !resources.clip_host.0.is_null() && unsafe { IsWindow(Some(resources.clip_host)) }.as_bool()
    {
        if let Err(error) = unsafe { DestroyWindow(resources.clip_host) } {
            emit_diagnostic(
                native,
                DIAGNOSTIC_WARN,
                "clip-host.destroy.failed",
                format_windows_error(error),
                diagnostic_data(vec![(
                    "clipHostHwnd",
                    (resources.clip_host.0 as usize as u64).to_string(),
                )]),
            );
        }
    }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_abiVersionNative(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    env.new_string(NATIVE_ABI_VERSION)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_createNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    parent_hwnd: jlong,
    generation: jlong,
    user_data_dir: JString<'_>,
    document_start_script: JString<'_>,
    background_color: jint,
    callbacks: JObject<'_>,
) -> jlong {
    let result = (|| {
        let user_data_dir = jstring_to_string(&mut env, user_data_dir)?;
        let document_start_script = jstring_to_string(&mut env, document_start_script)?;
        let callbacks = Arc::new(JavaCallbacks {
            vm: env.get_java_vm().map_err(format_jni_error)?,
            object: env.new_global_ref(callbacks).map_err(format_jni_error)?,
        });
        let (handle, _) = register_route(parent_hwnd)?;
        if let Err(message) = enqueue_command(NativeCommand::Create {
            handle,
            host_hwnd: parent_hwnd,
            generation,
            user_data_dir,
            document_start_script,
            background_color: background_color as u32,
            callbacks,
        }) {
            remove_route(handle);
            return Err(message);
        }
        Ok(handle)
    })();
    match result {
        Ok(handle) => handle,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_destroyNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    if let Err(message) = enqueue_destroy(handle) {
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_setHostStateNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    parent_hwnd: jlong,
    screen_x: jint,
    screen_y: jint,
    width: jint,
    height: jint,
    visible: jboolean,
    generation: jlong,
) {
    enqueue_or_throw(
        &mut env,
        NativeCommand::SetHostState {
            handle,
            host_hwnd: parent_hwnd,
            screen_x,
            screen_y,
            width,
            height,
            visible: visible != 0,
            generation,
        },
    );
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_parkBeforePeerDisposeNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    host_hwnd: jlong,
    generation: jlong,
) -> jboolean {
    let holder = holder_window_of(handle);
    let completion = Arc::new(AtomicU8::new(0));
    if let Err(message) = enqueue_command(NativeCommand::Park {
        handle,
        host_hwnd,
        generation,
        completion: completion.clone(),
    }) {
        let _ = env.throw_new("java/lang/IllegalStateException", message);
        return 0;
    }

    // The barrier is the whole point of owning a window on AWT-Windows: this send is handled by
    // `holder_window_proc` there, which drains the queued Park before the peer is destroyed.
    if holder.0.is_null() {
        return 0;
    }
    let mut ignored_result = 0usize;
    unsafe {
        let _ = SendMessageTimeoutW(
            holder,
            WM_AWT_WEBVIEW_BARRIER,
            WPARAM(0),
            LPARAM(0),
            SMTO_ABORTIFHUNG | SMTO_BLOCK,
            PARK_BARRIER_TIMEOUT_MILLIS,
            Some(&mut ignored_result),
        );
    }
    if completion.load(Ordering::Acquire) == 1 {
        1
    } else {
        let _ = completion.compare_exchange(0, 3, Ordering::AcqRel, Ordering::Acquire);
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_focusNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    enqueue_or_throw(&mut env, NativeCommand::Focus { handle });
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_clearFocusNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    enqueue_or_throw(&mut env, NativeCommand::ClearFocus { handle });
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_loadUrlNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    url: JString<'_>,
) {
    let Ok(url) = jstring_to_string(&mut env, url) else {
        return;
    };
    enqueue_or_throw(&mut env, NativeCommand::LoadUrl { handle, url });
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_setVirtualHostNameToFolderMappingNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    host_name: JString<'_>,
    folder_path: JString<'_>,
) {
    let host_name = match jstring_to_string(&mut env, host_name) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            return;
        }
    };
    let folder_path = match jstring_to_string(&mut env, folder_path) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            return;
        }
    };
    enqueue_or_throw(
        &mut env,
        NativeCommand::SetVirtualHostMapping {
            handle,
            host_name,
            folder_path,
        },
    );
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_loadHtmlNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    html: JString<'_>,
    _base_url: JObject<'_>,
) {
    let Ok(html) = jstring_to_string(&mut env, html) else {
        return;
    };
    enqueue_or_throw(&mut env, NativeCommand::LoadHtml { handle, html });
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_evaluateJavaScriptNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    eval_id: jlong,
    script: JString<'_>,
) {
    let Ok(script) = jstring_to_string(&mut env, script) else {
        return;
    };
    enqueue_or_throw(
        &mut env,
        NativeCommand::Evaluate {
            handle,
            eval_id,
            script,
        },
    );
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_callDevToolsProtocolMethodNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    call_id: jlong,
    method_name: JString<'_>,
    params_json: JString<'_>,
) {
    let method_name = match jstring_to_string(&mut env, method_name) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            return;
        }
    };
    let params_json = match jstring_to_string(&mut env, params_json) {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            return;
        }
    };
    enqueue_or_throw(
        &mut env,
        NativeCommand::CallDevTools {
            handle,
            call_id,
            method_name,
            params_json,
        },
    );
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_transferToJsNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    raw_json: JString<'_>,
) {
    let Ok(raw_json) = jstring_to_string(&mut env, raw_json) else {
        return;
    };
    enqueue_or_throw(&mut env, NativeCommand::TransferToJs { handle, raw_json });
}

#[no_mangle]
pub extern "system" fn Java_io_github_nerzhulart_webview_impl_windows_WinWebView2Bridge_completeAssetRequestNative(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    request_id: jlong,
    response: JObject<'_>,
) {
    let response = asset_response_from_java(&mut env, response);
    enqueue_or_throw(
        &mut env,
        NativeCommand::CompleteAsset {
            handle,
            request_id: request_id as u64,
            response,
        },
    );
}

fn enqueue_or_throw(env: &mut JNIEnv<'_>, command: NativeCommand) {
    if let Err(message) = enqueue_command(command) {
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }
}

const CLIP_HOST_WINDOW_CLASS: PCWSTR = w!("IJWebView2ClipHost");

/// A plain bridge-owned child HWND used as the stable WebView2 parent, parking unit, and optional
/// DirectComposition target. In the windowed path its client rectangle is not a hard visual clip.
unsafe extern "system" fn clip_host_window_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    unsafe { DefWindowProcW(hwnd, message, wparam, lparam) }
}

fn ensure_clip_host_window_class() -> BridgeResult<()> {
    static REGISTERED: OnceLock<BridgeResult<()>> = OnceLock::new();
    REGISTERED
        .get_or_init(|| {
            let class = WNDCLASSW {
                lpfnWndProc: Some(clip_host_window_proc),
                hInstance: unsafe { GetModuleHandleW(None) }
                    .map_err(format_windows_error)?
                    .into(),
                hbrBackground: HBRUSH::default(),
                lpszClassName: CLIP_HOST_WINDOW_CLASS,
                ..Default::default()
            };
            if unsafe { RegisterClassW(&class) } == 0 {
                return Err("Failed to register the WebView2 clip-host window class".to_string());
            }
            Ok(())
        })
        .clone()
}

fn canvas_client_bounds(canvas: HWND) -> BridgeResult<RECT> {
    if canvas.0.is_null() {
        return Err("WebView2 Canvas HWND is null".to_string());
    }
    let mut client = RECT::default();
    unsafe { GetClientRect(canvas, &mut client) }.map_err(format_windows_error)?;
    Ok(RECT {
        left: 0,
        top: 0,
        right: (client.right - client.left).max(0),
        bottom: (client.bottom - client.top).max(0),
    })
}

fn create_clip_host_window(canvas: HWND) -> BridgeResult<(HWND, RECT)> {
    let bounds = canvas_client_bounds(canvas)?;
    create_clip_host_window_at(canvas, bounds)
}

fn create_clip_host_window_at(parent: HWND, bounds: RECT) -> BridgeResult<(HWND, RECT)> {
    ensure_clip_host_window_class()?;
    let clip_host = unsafe {
        CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            CLIP_HOST_WINDOW_CLASS,
            PCWSTR::null(),
            WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN,
            bounds.left,
            bounds.top,
            (bounds.right - bounds.left).max(0),
            (bounds.bottom - bounds.top).max(0),
            Some(parent),
            None,
            None,
            None,
        )
        .map_err(format_windows_error)?
    };
    Ok((clip_host, bounds))
}

fn set_clip_host_bounds_window(clip_host: HWND, bounds: RECT) -> BridgeResult<()> {
    unsafe {
        SetWindowPos(
            clip_host,
            None,
            bounds.left,
            bounds.top,
            (bounds.right - bounds.left).max(0),
            (bounds.bottom - bounds.top).max(0),
            SWP_NOACTIVATE | SWP_NOZORDER,
        )
    }
    .map_err(format_windows_error)
}

fn screen_rect_to_client(
    parent: HWND,
    screen_x: i32,
    screen_y: i32,
    width: i32,
    height: i32,
) -> BridgeResult<RECT> {
    if parent.0.is_null() {
        return Err("WebView2 root HWND is null".to_string());
    }
    let mut origin = POINT {
        x: screen_x,
        y: screen_y,
    };
    if !unsafe { ScreenToClient(parent, &mut origin) }.as_bool() {
        return Err(format!(
            "ScreenToClient failed for root HWND: {}",
            format_windows_error(windows::core::Error::from_win32())
        ));
    }
    Ok(RECT {
        left: origin.x,
        top: origin.y,
        right: origin.x + width.max(0),
        bottom: origin.y + height.max(0),
    })
}

fn set_clip_host_parent_window(clip_host: HWND, parent: HWND) -> BridgeResult<()> {
    unsafe { SetParent(clip_host, Some(parent)) }
        .map(|_| ())
        .map_err(format_windows_error)
}

fn rect_from_size(width: i32, height: i32) -> RECT {
    RECT {
        left: 0,
        top: 0,
        right: width.max(0),
        bottom: height.max(0),
    }
}

fn effective_composition_clip_bounds(bounds: RECT) -> RECT {
    if bounds.right <= bounds.left || bounds.bottom <= bounds.top {
        return RECT::default();
    }
    presentation_selection()
        .forced_clip
        .map(|(width, height)| rect_from_size(width, height))
        .unwrap_or(bounds)
}

fn set_composition_clip_objects(
    clip: &IDCompositionRectangleClip,
    device: &IDCompositionDevice,
    bounds: RECT,
) -> BridgeResult<()> {
    unsafe {
        clip.SetLeft2(bounds.left as f32)
            .map_err(format_windows_error)?;
        clip.SetTop2(bounds.top as f32)
            .map_err(format_windows_error)?;
        clip.SetRight2(bounds.right as f32)
            .map_err(format_windows_error)?;
        clip.SetBottom2(bounds.bottom as f32)
            .map_err(format_windows_error)?;
        device.Commit().map_err(format_windows_error)
    }
}

fn create_d3d11_device(driver_type: D3D_DRIVER_TYPE) -> BridgeResult<ID3D11Device> {
    let mut device = None;
    unsafe {
        D3D11CreateDevice(
            None::<&windows::Win32::Graphics::Dxgi::IDXGIAdapter>,
            driver_type,
            HMODULE::default(),
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            None,
            D3D11_SDK_VERSION,
            Some(&mut device),
            None,
            None,
        )
    }
    .map_err(format_windows_error)?;
    device.ok_or_else(|| "D3D11CreateDevice succeeded without returning a device".to_string())
}

fn create_composition_host(surface: HWND, bounds: RECT) -> BridgeResult<CompositionHost> {
    let (d3d_device, d3d_driver, d3d_hardware_error) =
        match create_d3d11_device(D3D_DRIVER_TYPE_HARDWARE) {
            Ok(device) => (device, "hardware", None),
            Err(hardware_error) => match create_d3d11_device(D3D_DRIVER_TYPE_WARP) {
                Ok(device) => (device, "warp", Some(hardware_error)),
                Err(warp_error) => {
                    return Err(format!(
                "D3D11CreateDevice failed for hardware ({hardware_error}) and WARP ({warp_error})"
            ))
                }
            },
        };
    let dxgi_device = d3d_device.cast::<IDXGIDevice>().map_err(|error| {
        format!(
            "ID3D11Device -> IDXGIDevice failed: {}",
            format_windows_error(error)
        )
    })?;
    let device: IDCompositionDevice =
        unsafe { DCompositionCreateDevice(&dxgi_device) }.map_err(|error| {
            format!(
                "DCompositionCreateDevice failed: {}",
                format_windows_error(error)
            )
        })?;
    let target = unsafe { device.CreateTargetForHwnd(surface, true) }.map_err(|error| {
        format!(
            "CreateTargetForHwnd failed: {}",
            format_windows_error(error)
        )
    })?;
    let container_visual = unsafe { device.CreateVisual() }.map_err(|error| {
        format!(
            "CreateVisual(container) failed: {}",
            format_windows_error(error)
        )
    })?;
    let webview_visual = unsafe { device.CreateVisual() }.map_err(|error| {
        format!(
            "CreateVisual(WebView2 root) failed: {}",
            format_windows_error(error)
        )
    })?;
    let clip = unsafe { device.CreateRectangleClip() }.map_err(|error| {
        format!(
            "CreateRectangleClip failed: {}",
            format_windows_error(error)
        )
    })?;
    let visual_clip_bounds = effective_composition_clip_bounds(bounds);
    unsafe {
        container_visual
            .SetClip(&clip)
            .map_err(|error| format!("SetClip failed: {}", format_windows_error(error)))?;
        container_visual
            .AddVisual(&webview_visual, false, None::<&IDCompositionVisual>)
            .map_err(|error| format!("AddVisual failed: {}", format_windows_error(error)))?;
        target
            .SetRoot(&container_visual)
            .map_err(|error| format!("SetRoot failed: {}", format_windows_error(error)))?;
    }
    set_composition_clip_objects(&clip, &device, visual_clip_bounds)
        .map_err(|error| format!("initial DirectComposition clip commit failed: {error}"))?;

    Ok(CompositionHost {
        _d3d_device: d3d_device,
        _dxgi_device: dxgi_device,
        d3d_driver,
        d3d_hardware_error,
        device,
        target,
        _container_visual: container_visual,
        webview_visual,
        clip,
        composition_controller: None,
        visual_clip_bounds,
        maximum_controller_bounds: bounds,
        commit_generation: 1,
    })
}

const HOLDER_WINDOW_CLASS: PCWSTR = w!("IJWebView2Holder");
/// Keeps the parked widget away from the visible client area even for the frames where the parent
/// clip has not been applied yet.
const HOLDER_WINDOW_OFFSET: i32 = -32_000;

/// The shared parking window never paints and has no client area. It outlives transient Canvas
/// peers and owns the wndproc used by the synchronous park barrier.
unsafe extern "system" fn holder_window_proc(
    hwnd: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_AWT_WEBVIEW_BARRIER => {
            drain_owner_commands(unsafe { GetCurrentThreadId() });
            LRESULT(0)
        }
        WM_NCDESTROY => {
            // A newer Canvas attachment queued before this teardown must win.
            drain_owner_commands(unsafe { GetCurrentThreadId() });
            destroy_views_parked_on(hwnd);
            unsafe { DefWindowProcW(hwnd, message, wparam, lparam) }
        }
        _ => unsafe { DefWindowProcW(hwnd, message, wparam, lparam) },
    }
}

fn ensure_holder_window_class() -> BridgeResult<()> {
    static REGISTERED: OnceLock<BridgeResult<()>> = OnceLock::new();
    REGISTERED
        .get_or_init(|| {
            let class = WNDCLASSW {
                lpfnWndProc: Some(holder_window_proc),
                hInstance: unsafe { GetModuleHandleW(None) }
                    .map_err(format_windows_error)?
                    .into(),
                hbrBackground: HBRUSH::default(),
                lpszClassName: HOLDER_WINDOW_CLASS,
                ..Default::default()
            };
            if unsafe { RegisterClassW(&class) } == 0 {
                return Err("Failed to register the WebView2 holder window class".to_string());
            }
            Ok(())
        })
        .clone()
}

thread_local! {
    /// One holder window per top-level root, reused for the lifetime of that root.
    static HOLDER_WINDOWS: RefCell<HashMap<isize, HWND>> = RefCell::new(HashMap::new());
}

/// Resolves the holder for the root the Canvas belongs to, creating it on first use.
fn resolve_holder_window(canvas: HWND) -> BridgeResult<HWND> {
    if canvas.0.is_null() {
        return Err("WebView2 Canvas HWND is null".to_string());
    }
    let owner_tid = unsafe { GetWindowThreadProcessId(canvas, None) };
    let current_tid = unsafe { GetCurrentThreadId() };
    if owner_tid == 0 || owner_tid != current_tid {
        return Err("WebView2 Canvas HWND is not owned by AWT-Windows".to_string());
    }

    // GA_ROOTOWNER, not GA_ROOT: for an ordinary frame the two are the same window, so parking
    // stays inside one composition tree; for a floating dialog it resolves to the owning frame,
    // which outlives the dialog. Parking under a window that is about to be disposed would
    // destroy the controller together with it.
    let root_owner = unsafe { GetAncestor(canvas, GA_ROOTOWNER) };
    if root_owner.0.is_null()
        || unsafe { GetWindowThreadProcessId(root_owner, None) } != current_tid
    {
        return Err("Cannot resolve an AWT root for the WebView2 holder window".to_string());
    }

    ensure_holder_window(root_owner)
}

/// Returns the holder window for `root`: a `WS_VISIBLE`, zero-sized child of it. The parked
/// controller is hidden through `put_IsVisible`, and the holder only has to be a live parent that
/// outlives the Canvas peer - it is kept visible and zero-sized so that a controller which is shown
/// again before it is re-parented still has nothing to present into.
fn ensure_holder_window(root: HWND) -> BridgeResult<HWND> {
    let key = root.0 as isize;
    if let Some(existing) = HOLDER_WINDOWS.with(|windows| windows.borrow().get(&key).copied()) {
        if unsafe { IsWindow(Some(existing)) }.as_bool() {
            return Ok(existing);
        }
        HOLDER_WINDOWS.with(|windows| {
            windows.borrow_mut().remove(&key);
        });
    }

    ensure_holder_window_class()?;
    // Far outside the client area, not at (0, 0): the parked controller keeps its real size, and
    // for a frame or two after the reparent its widget is presented before the parent clip is
    // re-applied. At the origin that shows up as a white ghost in the top-left of the frame.
    let holder = unsafe {
        CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            HOLDER_WINDOW_CLASS,
            PCWSTR::null(),
            WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN,
            HOLDER_WINDOW_OFFSET,
            HOLDER_WINDOW_OFFSET,
            0,
            0,
            Some(root),
            None,
            None,
            None,
        )
        .map_err(format_windows_error)?
    };
    HOLDER_WINDOWS.with(|windows| {
        windows.borrow_mut().insert(key, holder);
    });
    Ok(holder)
}

/// Reads the holder recorded for `handle`. Called off the owner thread by the park barrier.
fn holder_window_of(handle: jlong) -> HWND {
    let Ok(transport) = transport().lock() else {
        return HWND::default();
    };
    transport
        .routes
        .get(&handle)
        .map(|route| HWND(route.holder_hwnd as *mut c_void))
        .unwrap_or_default()
}

/// Publishes the holder to the route so the park barrier, which runs on the Swing thread, can
/// address a window whose `wndproc` drains the queue.
fn publish_holder_window(handle: jlong, canvas: HWND) {
    let Ok(holder) = resolve_holder_window(canvas) else {
        return;
    };
    if let Ok(mut transport) = transport().lock() {
        if let Some(route) = transport.routes.get_mut(&handle) {
            route.holder_hwnd = holder.0 as isize;
        }
    }
}

fn execute_native_command(command: NativeCommand) {
    let handle = command.handle();
    let result = match command {
        NativeCommand::Create {
            handle,
            host_hwnd,
            generation,
            user_data_dir,
            document_start_script,
            background_color,
            callbacks,
        } => {
            let result = create_native_on_owner(
                handle,
                host_hwnd,
                generation,
                user_data_dir,
                document_start_script,
                background_color,
                callbacks.clone(),
            );
            if let Err(message) = &result {
                callbacks.on_create_failed(message.clone());
                callbacks.on_destroyed(handle);
                remove_route(handle);
            }
            result
        }
        NativeCommand::Destroy { handle } => destroy_native_on_owner(handle),
        NativeCommand::SetHostState {
            handle,
            host_hwnd,
            screen_x,
            screen_y,
            width,
            height,
            visible,
            generation,
        } => with_native(handle, |native| {
            let host = HWND(host_hwnd as *mut c_void);
            if !host.0.is_null()
                && unsafe { GetWindowThreadProcessId(host, None) }
                    != unsafe { GetCurrentThreadId() }
            {
                return Err("Canvas HWND is not owned by AWT-Windows".to_string());
            }
            {
                let mut view = native
                    .try_borrow_mut()
                    .map_err(|_| "WebView2 state is busy while applying host state".to_string())?;
                if generation < view.host_generation {
                    return Ok(());
                }
                view.host_generation = generation;
                view.width = width.max(0);
                view.height = height.max(0);
                view.screen_x = screen_x;
                view.screen_y = screen_y;
                view.visible = visible;
                if !host.0.is_null() {
                    view.canvas = host;
                    view.target_parent = host;
                }
            }
            if !host.0.is_null() {
                // The Canvas may have landed under a different top-level, and the barrier has to
                // reach the holder of the root it is in now.
                publish_holder_window(handle, host);
            }
            reconcile(&native)
        }),
        NativeCommand::Park {
            handle,
            host_hwnd,
            generation,
            completion,
        } => {
            if completion
                .compare_exchange(0, 2, Ordering::AcqRel, Ordering::Acquire)
                .is_err()
            {
                return;
            }
            let result = with_native(handle, |native| {
                let parking_target = resolve_holder_window(HWND(host_hwnd as *mut c_void))?;
                let controller_present = {
                    let mut view = native
                        .try_borrow_mut()
                        .map_err(|_| "WebView2 state is busy while parking Canvas".to_string())?;
                    if view.host_generation != generation
                        || view.canvas.0 as isize as jlong != host_hwnd
                    {
                        return Ok(());
                    }
                    view.canvas = HWND::default();
                    view.target_parent = parking_target;
                    view.visible = false;
                    view.controller.is_some()
                };
                // `reconcile` hides the controller, shrinks the clip host to zero, then moves that
                // stable host into the holder before AWT destroys the Canvas peer.
                reconcile(&native)?;
                trace_placement("park", &native, None);
                emit_diagnostic(
                    &native,
                    DIAGNOSTIC_TRACE,
                    "host.state",
                    "WebView2 controller parked in the holder window".to_string(),
                    diagnostic_data(vec![
                        ("phase", "parked".to_string()),
                        ("hostHwnd", host_hwnd.to_string()),
                        ("holderHwnd", (parking_target.0 as usize as u64).to_string()),
                        (
                            "holderVisible",
                            unsafe { IsWindowVisible(parking_target) }
                                .as_bool()
                                .to_string(),
                        ),
                        ("generation", generation.to_string()),
                        ("controller", controller_present.to_string()),
                    ]),
                );
                Ok(())
            });
            completion.store(if result.is_ok() { 1 } else { 2 }, Ordering::Release);
            result
        }
        NativeCommand::Focus { handle } => with_native(handle, |native| {
            let (canvas, controller) = {
                let view = native
                    .try_borrow()
                    .map_err(|_| "WebView2 state is busy while focusing Canvas".to_string())?;
                (view.canvas, view.controller.clone())
            };
            unsafe {
                if !canvas.0.is_null() {
                    let _ = SetFocus(Some(canvas));
                }
                if let Some(controller) = controller {
                    controller
                        .MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC)
                        .map_err(format_windows_error)?;
                }
            }
            Ok(())
        }),
        NativeCommand::ClearFocus { handle } => with_native(handle, |native| {
            let target_parent = native
                .try_borrow()
                .map_err(|_| "WebView2 state is busy while clearing focus".to_string())?
                .target_parent;
            unsafe {
                if !target_parent.0.is_null() {
                    let root = GetAncestor(target_parent, GA_ROOT);
                    if !root.0.is_null() {
                        let _ = SetFocus(Some(root));
                    }
                }
            }
            Ok(())
        }),
        NativeCommand::LoadUrl { handle, url } => {
            with_native(handle, |native| load_url(&native, url))
        }
        NativeCommand::SetVirtualHostMapping {
            handle,
            host_name,
            folder_path,
        } => with_native(handle, |native| {
            let webview = native
                .try_borrow()
                .map_err(|_| "WebView2 state is busy while setting host mapping".to_string())?
                .webview
                .clone()
                .ok_or_else(|| "WebView2 is not ready".to_string())?;
            set_virtual_host_name_to_folder_mapping(&webview, &host_name, &folder_path)
        }),
        NativeCommand::LoadHtml { handle, html } => {
            with_native(handle, |native| load_html(&native, html))
        }
        NativeCommand::Evaluate {
            handle,
            eval_id,
            script,
        } => with_native(handle, |native| evaluate_script(&native, eval_id, script)),
        NativeCommand::CallDevTools {
            handle,
            call_id,
            method_name,
            params_json,
        } => with_native(handle, |native| {
            call_dev_tools(&native, call_id, method_name, params_json)
        }),
        NativeCommand::TransferToJs { handle, raw_json } => {
            with_native(handle, |native| transfer_to_js(&native, raw_json))
        }
        NativeCommand::CompleteAsset {
            handle,
            request_id,
            response,
        } => with_native(handle, |native| {
            complete_asset_request(&native, request_id, response)
        }),
    };

    if let Err(message) = result {
        NATIVE_VIEWS.with(|views| {
            if let Some(native) = views.borrow().get(&handle) {
                emit_diagnostic(
                    native,
                    DIAGNOSTIC_WARN,
                    "command.failed",
                    message,
                    String::new(),
                );
            }
        });
    }
}

fn with_native(
    action_handle: jlong,
    action: impl FnOnce(NativeHandle) -> BridgeResult<()>,
) -> BridgeResult<()> {
    let native = NATIVE_VIEWS
        .with(|views| views.borrow().get(&action_handle).cloned())
        .ok_or_else(|| format!("Unknown WebView2 native handle on AWT-Windows: {action_handle}"))?;
    action(native)
}

fn destroy_native_on_owner(handle: jlong) -> BridgeResult<()> {
    let native = NATIVE_VIEWS.with(|views| views.borrow().get(&handle).cloned());
    let Some(native) = native else {
        remove_route(handle);
        return Ok(());
    };
    unregister_shared_environment_view(handle);
    let callbacks = native
        .try_borrow()
        .map_err(|_| "WebView2 state is busy while destroying".to_string())?
        .callbacks
        .clone();
    destroy_native_state(&native)?;
    NATIVE_VIEWS.with(|views| {
        views.borrow_mut().remove(&handle);
    });
    remove_route(handle);
    callbacks.on_destroyed(handle);
    Ok(())
}

fn destroy_views_parked_on(hwnd: HWND) {
    let handles = NATIVE_VIEWS.with(|views| {
        views
            .borrow()
            .iter()
            .filter_map(|(handle, native)| {
                native.try_borrow().ok().and_then(|view| {
                    (view.canvas.0.is_null() && view.target_parent == hwnd).then_some(*handle)
                })
            })
            .collect::<Vec<_>>()
    });
    for handle in handles {
        if let Ok(mut transport) = transport().lock() {
            if let Some(route) = transport.routes.get_mut(&handle) {
                route.closing = true;
            }
        }
        let _ = destroy_native_on_owner(handle);
    }
}

fn load_url(native: &NativeHandle, url: String) -> BridgeResult<()> {
    let webview = {
        let mut view = native.borrow_mut();
        view.last_navigation_requested_at = Some(Instant::now());
        view.navigation_timings.clear();
        view.unidentified_navigation_timing = None;
        view.webview
            .clone()
            .ok_or_else(|| "WebView2 is not ready".to_string())?
    };
    measure_perf(
        native,
        "perf.webview2.navigation.loadUrl.call",
        vec![("urlChars", url.len().to_string())],
        || unsafe {
            webview
                .Navigate(&HSTRING::from(url.as_str()))
                .map_err(format_windows_error)
        },
    )
}

fn load_html(native: &NativeHandle, html: String) -> BridgeResult<()> {
    let webview = {
        let mut view = native.borrow_mut();
        view.last_navigation_requested_at = Some(Instant::now());
        view.navigation_timings.clear();
        view.unidentified_navigation_timing = None;
        view.webview
            .clone()
            .ok_or_else(|| "WebView2 is not ready".to_string())?
    };
    measure_perf(
        native,
        "perf.webview2.navigation.loadHtml.call",
        vec![("htmlChars", html.len().to_string())],
        || unsafe {
            webview
                .NavigateToString(&HSTRING::from(html.as_str()))
                .map_err(format_windows_error)
        },
    )
}

fn evaluate_script(native: &NativeHandle, eval_id: jlong, script: String) -> BridgeResult<()> {
    let (webview, handler, handler_id) = {
        let mut view = native.borrow_mut();
        let webview = view
            .webview
            .clone()
            .ok_or_else(|| "WebView2 is not ready".to_string())?;
        let callbacks = view.callbacks.clone();
        let handler_id = view.next_script_handler_id;
        view.next_script_handler_id += 1;
        let native_for_callback = native.clone();
        let handler = ExecuteScriptCompletedHandler::create(Box::new(move |error_code, result| {
            remove_execute_script_handler(&native_for_callback, handler_id);
            match error_code {
                Ok(()) => callbacks.on_evaluation_result(eval_id, result),
                Err(error) => callbacks.on_evaluation_error(eval_id, format_windows_error(error)),
            }
            Ok(())
        }));
        view.execute_script_handlers
            .push((handler_id, handler.clone()));
        (webview, handler, handler_id)
    };
    if let Err(error) = unsafe { webview.ExecuteScript(&HSTRING::from(script), &handler) } {
        remove_execute_script_handler(native, handler_id);
        return Err(format_windows_error(error));
    }
    Ok(())
}

fn call_dev_tools(
    native: &NativeHandle,
    call_id: jlong,
    method_name: String,
    params_json: String,
) -> BridgeResult<()> {
    let (webview, handler, handler_id) = {
        let mut view = native.borrow_mut();
        let webview = view
            .webview
            .clone()
            .ok_or_else(|| "WebView2 is not ready".to_string())?;
        let callbacks = view.callbacks.clone();
        let handler_id = view.next_script_handler_id;
        view.next_script_handler_id += 1;
        let native_for_callback = native.clone();
        let handler = CallDevToolsProtocolMethodCompletedHandler::create(Box::new(
            move |error_code, result| {
                remove_dev_tools_handler(&native_for_callback, handler_id);
                match error_code {
                    Ok(()) => {
                        callbacks.on_dev_tools_protocol_method_result(call_id, Some(result), None)
                    }
                    Err(error) => callbacks.on_dev_tools_protocol_method_result(
                        call_id,
                        None,
                        Some(format_windows_error(error)),
                    ),
                }
                Ok(())
            },
        ));
        view.dev_tools_handlers.push((handler_id, handler.clone()));
        (webview, handler, handler_id)
    };
    if let Err(error) = unsafe {
        webview.CallDevToolsProtocolMethod(
            &HSTRING::from(method_name),
            &HSTRING::from(params_json),
            &handler,
        )
    } {
        remove_dev_tools_handler(native, handler_id);
        return Err(format_windows_error(error));
    }
    Ok(())
}

fn transfer_to_js(native: &NativeHandle, raw_json: String) -> BridgeResult<()> {
    let script = format!(
        /*language=JavaScript*/ "window.__WVI__ && window.__WVI__.__deliver({});",
        js_string_literal(&raw_json)
    );
    let (webview, handler, handler_id) = {
        let mut view = native.borrow_mut();
        let webview = view
            .webview
            .clone()
            .ok_or_else(|| "WebView2 is not ready".to_string())?;
        let handler_id = view.next_script_handler_id;
        view.next_script_handler_id += 1;
        let native_for_callback = native.clone();
        let handler = ExecuteScriptCompletedHandler::create(Box::new(move |_, _| {
            remove_execute_script_handler(&native_for_callback, handler_id);
            Ok(())
        }));
        view.execute_script_handlers
            .push((handler_id, handler.clone()));
        (webview, handler, handler_id)
    };
    if let Err(error) = unsafe { webview.ExecuteScript(&HSTRING::from(script), &handler) } {
        remove_execute_script_handler(native, handler_id);
        return Err(format_windows_error(error));
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn create_native_on_owner(
    handle: jlong,
    parent_hwnd: jlong,
    generation: jlong,
    user_data_dir: String,
    document_start_script: String,
    background_color: u32,
    callbacks: Arc<JavaCallbacks>,
) -> BridgeResult<()> {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED).ok();
    }

    let canvas = HWND(parent_hwnd as *mut c_void);
    // This is the first per-view native action and therefore freezes the process-wide experiment
    // selection before any WebView2 controller is requested.
    let selection = presentation_selection().clone();
    let (clip_host, clip_bounds) = if selection.mode.is_root_host() {
        create_clip_host_window_at(canvas, RECT::default())?
    } else {
        create_clip_host_window(canvas)?
    };
    let presentation = match selection.mode {
        PresentationMode::Windowed => PresentationHost::Windowed(WindowedHost),
        PresentationMode::WindowedRootPoc => PresentationHost::WindowedRootPoc(WindowedHost),
        PresentationMode::CompositionPoc => match create_composition_host(clip_host, clip_bounds) {
            Ok(host) => PresentationHost::CompositionPoc(host),
            Err(error) => {
                unsafe {
                    let _ = DestroyWindow(clip_host);
                }
                return Err(format!(
                    "composition-poc presentation initialization failed: {error}"
                ));
            }
        },
        PresentationMode::CompositionRootPoc => {
            match create_composition_host(clip_host, RECT::default()) {
                Ok(host) => PresentationHost::CompositionRootPoc(host),
                Err(error) => {
                    unsafe {
                        let _ = DestroyWindow(clip_host);
                    }
                    return Err(format!(
                        "composition-root-poc presentation initialization failed: {error}"
                    ));
                }
            }
        }
    };
    let document_start_scripts = if document_start_script.is_empty() {
        Vec::new()
    } else {
        vec![document_start_script]
    };

    let native = Rc::new(RefCell::new(NativeWebView {
        handle,
        canvas,
        target_parent: canvas,
        clip_host,
        clip_parent: canvas,
        clip_bounds,
        presentation,
        controller: None,
        webview: None,
        controller_completed_handler: None,
        controller_create_started_at: None,
        last_navigation_requested_at: None,
        navigation_timings: HashMap::new(),
        unidentified_navigation_timing: None,
        add_script_handlers: Vec::new(),
        execute_script_handlers: Vec::new(),
        dev_tools_handlers: Vec::new(),
        pending_asset_requests: HashMap::new(),
        next_asset_request_id: 1,
        next_script_handler_id: 0,
        document_start_scripts,
        web_message_token: EventRegistrationToken::default(),
        web_resource_requested_token: None,
        accelerator_key_pressed_token: None,
        got_focus_token: None,
        callbacks,
        destroyed: false,
        visible: false,
        background_color,
        controller_bounds: RECT::default(),
        controller_visible: true,
        width: 0,
        height: 0,
        screen_x: 0,
        screen_y: 0,
        host_generation: generation,
    }));

    NATIVE_VIEWS.with(|views| {
        views.borrow_mut().insert(handle, native.clone());
    });
    let (d3d_driver, d3d_hardware_error) = native
        .try_borrow()
        .ok()
        .and_then(|view| match &view.presentation {
            PresentationHost::CompositionPoc(host) | PresentationHost::CompositionRootPoc(host) => {
                Some((host.d3d_driver, host.d3d_hardware_error.clone()))
            }
            PresentationHost::Windowed(_) | PresentationHost::WindowedRootPoc(_) => None,
        })
        .unwrap_or_default();
    emit_diagnostic(
        &native,
        DIAGNOSTIC_TRACE,
        "presentation.selected",
        format!("Selected {} WebView2 presentation", selection.mode.name()),
        diagnostic_data(vec![
            ("mode", selection.mode.name().to_string()),
            ("d3dDriver", d3d_driver.to_string()),
            (
                "d3dHardwareError",
                d3d_hardware_error.clone().unwrap_or_default(),
            ),
            (
                "forcedClip",
                selection
                    .forced_clip
                    .map(|(width, height)| format!("{width}x{height}"))
                    .unwrap_or_default(),
            ),
            (
                "visualClip",
                format_rect(effective_composition_clip_bounds(clip_bounds)),
            ),
            (
                "staleControllerBounds",
                selection.stale_controller_bounds.to_string(),
            ),
            ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
        ]),
    );
    if let Some(hardware_error) = d3d_hardware_error {
        emit_diagnostic(
            &native,
            DIAGNOSTIC_INFO,
            "direct3d.hardware-unavailable",
            "Using the D3D11 WARP device for the composition PoC".to_string(),
            diagnostic_data(vec![("hardwareError", hardware_error)]),
        );
    }
    if let Some(warning) = selection.warning {
        if !PRESENTATION_WARNING_EMITTED.swap(true, Ordering::AcqRel) {
            emit_diagnostic(
                &native,
                DIAGNOSTIC_WARN,
                "presentation.invalid-selection",
                warning,
                diagnostic_data(vec![("selectedMode", selection.mode.name().to_string())]),
            );
        }
    }
    // Eagerly, not on the first park: `removeNotify` can arrive before anything else, and the
    // barrier needs a window to talk to by then.
    publish_holder_window(handle, canvas);
    if let Err(message) = ensure_shared_environment(native.clone(), user_data_dir) {
        let _ = destroy_native_state(&native);
        NATIVE_VIEWS.with(|views| {
            views.borrow_mut().remove(&handle);
        });
        return Err(message);
    }
    Ok(())
}

fn ensure_shared_environment(native: NativeHandle, user_data_dir: String) -> BridgeResult<()> {
    let key = EnvironmentKey::new(user_data_dir);
    let action = SHARED_ENVIRONMENT_MANAGER
        .with(|manager| manager.borrow_mut().ensure_environment(key, native.clone()))?;
    match action {
        SharedEnvironmentAction::None => Ok(()),
        SharedEnvironmentAction::CreateController {
            environment,
            generation,
        } => begin_create_controller(native, environment, generation),
        SharedEnvironmentAction::StartEnvironment {
            user_data_dir,
            generation,
            handler,
        } => start_shared_environment_creation(user_data_dir, generation, handler),
    }
}

fn start_shared_environment_creation(
    user_data_dir: String,
    generation: u64,
    handler: ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler,
) -> BridgeResult<()> {
    let options = CoreWebView2EnvironmentOptions::default();
    configure_asset_custom_scheme(&options);
    let options = ICoreWebView2EnvironmentOptions::from(options);
    unsafe {
        if let Err(error) =
            // CalculateNativeWinOcclusion must be off: a host that Swing shows can still be
            // covered by another heavyweight peer, and the occlusion tracker would throttle it
            // until rAF stops entirely. Hiding is reported explicitly through put_IsVisible, so
            // nothing is lost by not guessing it from window geometry.
            options.SetAdditionalBrowserArguments(w!(
                "--disable-features=ElasticOverscroll,CalculateNativeWinOcclusion"
            ))
        {
            SHARED_ENVIRONMENT_MANAGER.with(|manager| {
                manager.borrow_mut().clear_start_failure(generation);
            });
            return Err(format_windows_error(error));
        }
    }
    let user_data_dir = HSTRING::from(user_data_dir);
    unsafe {
        if let Err(error) = CreateCoreWebView2EnvironmentWithOptions(
            PCWSTR::null(),
            &user_data_dir,
            &options,
            &handler,
        ) {
            SHARED_ENVIRONMENT_MANAGER.with(|manager| {
                manager.borrow_mut().clear_start_failure(generation);
            });
            return Err(format_windows_error(error));
        }
    }
    Ok(())
}

fn configure_asset_custom_scheme(options: &CoreWebView2EnvironmentOptions) {
    let registration =
        CoreWebView2CustomSchemeRegistration::new(WEBVIEW_ASSET_CUSTOM_SCHEME.to_string());
    unsafe {
        // Keep the fixed authority (`ij-webview-asset://assets/...`): WebView2 ES modules need
        // a non-opaque custom-scheme origin, and per-view routing is done by WebResourceRequested.
        registration.set_has_authority_component(true);
        registration.set_treat_as_secure(true);
    }
    let registration: ICoreWebView2CustomSchemeRegistration = registration.into();
    unsafe {
        options.set_scheme_registrations(vec![Some(registration)]);
    }
}

fn create_shared_environment_completed_handler(
    generation: u64,
) -> ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler {
    CreateCoreWebView2EnvironmentCompletedHandler::create(Box::new(
        move |error_code, environment| {
            if let Err(error) = error_code {
                fail_shared_environment_creation(generation, format_windows_error(error));
                return Ok(());
            }

            let Some(environment) = environment else {
                fail_shared_environment_creation(
                    generation,
                    "WebView2 environment callback returned null".to_string(),
                );
                return Ok(());
            };

            let environment_started_at = shared_environment_started_at(generation);
            let waiters = SHARED_ENVIRONMENT_MANAGER.with(|manager| {
                manager
                    .borrow_mut()
                    .complete_environment_success(generation, environment.clone())
            });
            let diagnostic_target = waiters
                .iter()
                .find(|view| !is_native_destroyed(view))
                .cloned();
            if let Some(native) = &diagnostic_target {
                log_environment_metadata(&environment, native);
                if let Some(started_at) = environment_started_at {
                    emit_perf_diagnostic(
                        native,
                        "perf.webview2.environment.create",
                        started_at,
                        vec![("generation", generation.to_string())],
                    );
                }
            }
            if let Err(message) = attach_environment_diagnostics(&environment, generation) {
                if let Some(native) = &diagnostic_target {
                    emit_diagnostic(
                        native,
                        DIAGNOSTIC_WARN,
                        "diagnostics.attach-environment-failed",
                        message,
                        String::new(),
                    );
                }
            }

            for native in waiters {
                if is_native_destroyed(&native) || !is_shared_environment_current(generation) {
                    continue;
                }
                if let Err(message) =
                    begin_create_controller(native.clone(), environment.clone(), generation)
                {
                    fail_create(&native, message);
                }
            }
            Ok(())
        },
    ))
}

fn fail_shared_environment_creation(generation: u64, message: String) {
    let waiters = SHARED_ENVIRONMENT_MANAGER.with(|manager| {
        manager
            .borrow_mut()
            .complete_environment_failure(generation)
    });
    for native in waiters {
        if !is_native_destroyed(&native) {
            fail_create(&native, message.clone());
        }
    }
}

fn begin_create_controller(
    native: NativeHandle,
    environment: ICoreWebView2Environment,
    generation: u64,
) -> BridgeResult<()> {
    let mode = native
        .try_borrow()
        .map_err(|_| "WebView2 state is busy while selecting controller type".to_string())?
        .presentation
        .mode();
    match mode {
        PresentationMode::Windowed | PresentationMode::WindowedRootPoc => {
            begin_create_windowed_controller(native, environment, generation)
        }
        PresentationMode::CompositionPoc => {
            begin_create_composition_controller(native, environment, generation)
        }
        PresentationMode::CompositionRootPoc => {
            begin_create_composition_controller(native, environment, generation)
        }
    }
}

fn configure_controller_options(options: &ICoreWebView2ControllerOptions) -> BridgeResult<()> {
    if let Ok(options4) = options.cast::<ICoreWebView2ControllerOptions4>() {
        unsafe { options4.SetAllowHostInputProcessing(true) }.map_err(format_windows_error)?;
    }
    Ok(())
}

fn begin_create_windowed_controller(
    native: NativeHandle,
    environment: ICoreWebView2Environment,
    generation: u64,
) -> BridgeResult<()> {
    if is_native_destroyed(&native) {
        return Ok(());
    }
    if !register_shared_environment_active_view(generation, native.clone()) {
        return Ok(());
    }
    // The controller keeps this stable parent for its whole lifetime. Canvas peer changes move the
    // bridge-owned clip host instead of re-parenting Chromium's internal window hierarchy.
    let hwnd = native.borrow().clip_host;
    emit_diagnostic(
        &native,
        DIAGNOSTIC_TRACE,
        "controller.create.requested",
        "WebView2 controller creation requested".to_string(),
        diagnostic_data(vec![
            ("generation", generation.to_string()),
            (
                "parentHwnd",
                (native.borrow().target_parent.0 as usize as u64).to_string(),
            ),
            ("containerHwnd", (hwnd.0 as usize as u64).to_string()),
            ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
        ]),
    );
    let environment_for_callback = environment.clone();
    let native_for_callback = native.clone();
    let handler = CreateCoreWebView2ControllerCompletedHandler::create(Box::new(
        move |error_code, controller| {
            let (controller_started_at, native_destroyed) =
                if let Ok(mut view) = native_for_callback.try_borrow_mut() {
                    view.controller_completed_handler = None;
                    (view.controller_create_started_at.take(), view.destroyed)
                } else {
                    (None, false)
                };
            let generation_current = is_shared_environment_current(generation);
            let result = match &error_code {
                Ok(()) => "S_OK".to_string(),
                Err(error) => format_windows_error(error),
            };
            emit_diagnostic(
                &native_for_callback,
                DIAGNOSTIC_TRACE,
                "controller.create.callback",
                "WebView2 controller creation callback invoked".to_string(),
                diagnostic_data(vec![
                    ("generation", generation.to_string()),
                    ("generationCurrent", generation_current.to_string()),
                    ("nativeDestroyed", native_destroyed.to_string()),
                    ("controllerPresent", controller.is_some().to_string()),
                    ("result", result),
                    ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
                ]),
            );
            if native_destroyed || !generation_current {
                return Ok(());
            }
            if let Err(error) = error_code {
                fail_create(&native_for_callback, format_windows_error(error));
                return Ok(());
            }

            let Some(controller) = controller else {
                fail_create(
                    &native_for_callback,
                    "WebView2 controller callback returned null".to_string(),
                );
                return Ok(());
            };

            if let Some(started_at) = controller_started_at {
                emit_perf_diagnostic(
                    &native_for_callback,
                    "perf.webview2.controller.create",
                    started_at,
                    vec![("generation", generation.to_string())],
                );
            }
            match finish_create(
                native_for_callback.clone(),
                environment_for_callback.clone(),
                controller,
                generation,
            ) {
                Ok(()) => {}
                Err(message) => fail_create(&native_for_callback, message),
            }
            Ok(())
        },
    ));
    {
        let mut view = native.borrow_mut();
        view.controller_completed_handler = Some(ControllerCompletedHandler::Windowed {
            _handler: handler.clone(),
        });
        view.controller_create_started_at = Some(Instant::now());
    }
    let create_result = if let Ok(environment10) = environment.cast::<ICoreWebView2Environment10>()
    {
        match unsafe { environment10.CreateCoreWebView2ControllerOptions() } {
            Ok(options) => configure_controller_options(&options).and_then(|()| {
                unsafe {
                    environment10.CreateCoreWebView2ControllerWithOptions(hwnd, &options, &handler)
                }
                .map_err(format_windows_error)
            }),
            Err(error) => Err(format_windows_error(error)),
        }
    } else {
        unsafe { environment.CreateCoreWebView2Controller(hwnd, &handler) }
            .map_err(format_windows_error)
    };
    if let Err(error) = create_result {
        unregister_shared_environment_view(native_handle(&native));
        if let Ok(mut view) = native.try_borrow_mut() {
            view.controller_create_started_at = None;
        }
        return Err(error);
    }
    emit_diagnostic(
        &native,
        DIAGNOSTIC_TRACE,
        "controller.create.accepted",
        "WebView2 controller creation request accepted".to_string(),
        diagnostic_data(vec![
            ("generation", generation.to_string()),
            ("containerHwnd", (hwnd.0 as usize as u64).to_string()),
            ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
        ]),
    );
    Ok(())
}

fn begin_create_composition_controller(
    native: NativeHandle,
    environment: ICoreWebView2Environment,
    generation: u64,
) -> BridgeResult<()> {
    if is_native_destroyed(&native) {
        return Ok(());
    }
    if !register_shared_environment_active_view(generation, native.clone()) {
        return Ok(());
    }
    let (surface, target_parent) = {
        let view = native.try_borrow().map_err(|_| {
            "WebView2 state is busy while starting composition controller creation".to_string()
        })?;
        (view.clip_host, view.target_parent)
    };
    emit_diagnostic(
        &native,
        DIAGNOSTIC_TRACE,
        "controller.create.requested",
        "WebView2 composition controller creation requested".to_string(),
        diagnostic_data(vec![
            ("generation", generation.to_string()),
            (
                "presentationMode",
                PresentationMode::CompositionPoc.name().to_string(),
            ),
            ("parentHwnd", (target_parent.0 as usize as u64).to_string()),
            ("containerHwnd", (surface.0 as usize as u64).to_string()),
            ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
        ]),
    );

    let environment_for_callback = environment.clone();
    let native_for_callback = native.clone();
    let handler = CreateCoreWebView2CompositionControllerCompletedHandler::create(Box::new(
        move |error_code, composition_controller| {
            let (controller_started_at, native_destroyed) =
                if let Ok(mut view) = native_for_callback.try_borrow_mut() {
                    view.controller_completed_handler = None;
                    (view.controller_create_started_at.take(), view.destroyed)
                } else {
                    (None, false)
                };
            let generation_current = is_shared_environment_current(generation);
            let result = match &error_code {
                Ok(()) => "S_OK".to_string(),
                Err(error) => format_windows_error(error),
            };
            emit_diagnostic(
                &native_for_callback,
                DIAGNOSTIC_TRACE,
                "controller.create.callback",
                "WebView2 composition controller creation callback invoked".to_string(),
                diagnostic_data(vec![
                    ("generation", generation.to_string()),
                    (
                        "presentationMode",
                        PresentationMode::CompositionPoc.name().to_string(),
                    ),
                    ("generationCurrent", generation_current.to_string()),
                    ("nativeDestroyed", native_destroyed.to_string()),
                    (
                        "controllerPresent",
                        composition_controller.is_some().to_string(),
                    ),
                    ("result", result),
                    ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
                ]),
            );
            if native_destroyed || !generation_current {
                return Ok(());
            }
            if let Err(error) = error_code {
                fail_create(
                    &native_for_callback,
                    format!(
                        "composition-poc controller creation failed: {}",
                        format_windows_error(error)
                    ),
                );
                return Ok(());
            }
            let Some(composition_controller) = composition_controller else {
                fail_create(
                    &native_for_callback,
                    "composition-poc controller callback returned null".to_string(),
                );
                return Ok(());
            };

            if let Some(started_at) = controller_started_at {
                emit_perf_diagnostic(
                    &native_for_callback,
                    "perf.webview2.controller.create",
                    started_at,
                    vec![
                        ("generation", generation.to_string()),
                        (
                            "presentationMode",
                            PresentationMode::CompositionPoc.name().to_string(),
                        ),
                    ],
                );
            }

            let finish_result = (|| {
                let (webview_visual, device) = {
                    let view = native_for_callback.try_borrow().map_err(|_| {
                        "WebView2 state is busy while attaching the root visual".to_string()
                    })?;
                    match &view.presentation {
                        PresentationHost::CompositionPoc(host)
                        | PresentationHost::CompositionRootPoc(host) => {
                            (host.webview_visual.clone(), host.device.clone())
                        }
                        PresentationHost::Windowed(_) | PresentationHost::WindowedRootPoc(_) => {
                            return Err(
                                "composition-poc callback reached a windowed host".to_string()
                            )
                        }
                    }
                };
                let root_visual = webview_visual
                    .cast::<IUnknown>()
                    .map_err(format_windows_error)?;
                let commit_started_at = Instant::now();
                unsafe {
                    composition_controller
                        .SetRootVisualTarget(&root_visual)
                        .map_err(|error| {
                            format!(
                                "SetRootVisualTarget failed: {}",
                                format_windows_error(error)
                            )
                        })?;
                    device.Commit().map_err(|error| {
                        format!(
                            "DirectComposition commit after SetRootVisualTarget failed: {}",
                            format_windows_error(error)
                        )
                    })?;
                }
                emit_perf_diagnostic(
                    &native_for_callback,
                    "perf.direct-composition.root-visual.commit",
                    commit_started_at,
                    vec![("generation", generation.to_string())],
                );
                let controller = composition_controller
                    .cast::<ICoreWebView2Controller>()
                    .map_err(|error| {
                        format!(
                            "ICoreWebView2CompositionController -> ICoreWebView2Controller failed: {}",
                            format_windows_error(error)
                        )
                    })?;
                {
                    let mut view = native_for_callback.try_borrow_mut().map_err(|_| {
                        "WebView2 state is busy while retaining the composition controller"
                            .to_string()
                    })?;
                    match &mut view.presentation {
                        PresentationHost::CompositionPoc(host)
                        | PresentationHost::CompositionRootPoc(host) => {
                            host.composition_controller = Some(composition_controller.clone());
                        }
                        PresentationHost::Windowed(_) | PresentationHost::WindowedRootPoc(_) => {
                            return Err(
                                "composition-poc callback reached a windowed host".to_string()
                            )
                        }
                    }
                }
                finish_create(
                    native_for_callback.clone(),
                    environment_for_callback.clone(),
                    controller,
                    generation,
                )
            })();
            if let Err(message) = finish_result {
                fail_create(&native_for_callback, message);
            }
            Ok(())
        },
    ));
    {
        let mut view = native.borrow_mut();
        view.controller_completed_handler = Some(ControllerCompletedHandler::Composition {
            _handler: handler.clone(),
        });
        view.controller_create_started_at = Some(Instant::now());
    }

    let create_without_options = || -> BridgeResult<()> {
        match environment.cast::<ICoreWebView2Environment3>() {
            Ok(environment3) => {
                unsafe { environment3.CreateCoreWebView2CompositionController(surface, &handler) }
                    .map_err(format_windows_error)
            }
            Err(error) => Err(format!(
                "ICoreWebView2Environment3 is unavailable: {}",
                format_windows_error(error)
            )),
        }
    };
    let create_result = if let Ok(environment10) = environment.cast::<ICoreWebView2Environment10>()
    {
        match unsafe { environment10.CreateCoreWebView2ControllerOptions() } {
            Ok(options) => configure_controller_options(&options).and_then(|()| {
                unsafe {
                    environment10.CreateCoreWebView2CompositionControllerWithOptions(
                        surface, &options, &handler,
                    )
                }
                .map_err(format_windows_error)
            }),
            Err(_) => create_without_options(),
        }
    } else {
        create_without_options()
    };
    if let Err(error) = create_result {
        unregister_shared_environment_view(native_handle(&native));
        if let Ok(mut view) = native.try_borrow_mut() {
            view.controller_create_started_at = None;
        }
        return Err(format!(
            "composition-poc controller request failed: {error}"
        ));
    }
    emit_diagnostic(
        &native,
        DIAGNOSTIC_TRACE,
        "controller.create.accepted",
        "WebView2 composition controller creation request accepted".to_string(),
        diagnostic_data(vec![
            ("generation", generation.to_string()),
            (
                "presentationMode",
                PresentationMode::CompositionPoc.name().to_string(),
            ),
            ("containerHwnd", (surface.0 as usize as u64).to_string()),
            ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
        ]),
    );
    Ok(())
}

fn finish_create(
    native: NativeHandle,
    environment: ICoreWebView2Environment,
    controller: ICoreWebView2Controller,
    generation: u64,
) -> BridgeResult<()> {
    let finish_started_at = Instant::now();
    let webview = measure_perf(
        &native,
        "perf.webview2.finish.core-webview2",
        vec![("generation", generation.to_string())],
        || unsafe { controller.CoreWebView2().map_err(format_windows_error) },
    )?;

    measure_perf(
        &native,
        "perf.webview2.finish.settings",
        vec![("generation", generation.to_string())],
        || configure_webview_application_settings(&webview),
    )?;

    let script_count = native
        .try_borrow()
        .map(|view| view.document_start_scripts.len())
        .unwrap_or_default();
    measure_perf(
        &native,
        "perf.webview2.finish.document-start-scripts",
        vec![
            ("generation", generation.to_string()),
            ("scriptCount", script_count.to_string()),
        ],
        || install_document_start_scripts(&webview, native.clone()),
    )?;

    let token = measure_perf(
        &native,
        "perf.webview2.finish.ipc-handler",
        vec![("generation", generation.to_string())],
        || attach_ipc_handler(&webview, native.clone()),
    )?;

    let web_resource_token = measure_perf(
        &native,
        "perf.webview2.finish.resource-handler",
        vec![("generation", generation.to_string())],
        || attach_web_resource_requested_handler(&environment, &webview, native.clone()),
    )?;

    let accelerator_token = measure_perf(
        &native,
        "perf.webview2.finish.accelerator-handler",
        vec![("generation", generation.to_string())],
        || attach_accelerator_key_handler(&controller, native.clone()),
    )?;

    let got_focus_token = measure_perf(
        &native,
        "perf.webview2.finish.focus-handler",
        vec![("generation", generation.to_string())],
        || attach_got_focus_handler(&controller, native.clone()),
    )?;

    // Without this the controller paints its own near-white default before the first frame of the
    // page reaches the screen, which is the white flash seen on reattach. The Canvas peer is
    // already erased with the same color, so the transition becomes invisible.
    apply_default_background_color(&native, &controller);

    measure_perf(
        &native,
        "perf.webview2.finish.diagnostics",
        vec![("generation", generation.to_string())],
        || {
            if let Err(message) = attach_webview_diagnostics(&webview, native.clone()) {
                emit_diagnostic(
                    &native,
                    DIAGNOSTIC_WARN,
                    "diagnostics.attach-webview-failed",
                    message,
                    String::new(),
                );
            }
            Ok(())
        },
    )?;

    let (callbacks, handle) = {
        let mut view = native.borrow_mut();
        if view.destroyed || !is_shared_environment_current(generation) {
            return Ok(());
        }

        view.web_message_token = token;
        view.web_resource_requested_token = Some(web_resource_token);
        view.accelerator_key_pressed_token = Some(accelerator_token);
        view.got_focus_token = Some(got_focus_token);
        view.controller = Some(controller.clone());
        view.webview = Some(webview);
        (view.callbacks.clone(), view.handle)
    };

    // Swing keeps its own snapshot, so the latest state is applied once here and every later
    // change arrives as its own SetHostState command.
    reconcile(&native)?;

    callbacks.on_created(handle);
    emit_perf_diagnostic(
        &native,
        "perf.webview2.finish.total",
        finish_started_at,
        vec![
            ("generation", generation.to_string()),
            ("handle", handle.to_string()),
        ],
    );
    Ok(())
}

/// Paints the controller with the host background instead of the WebView2 default, so a frame
/// without page content is indistinguishable from the Canvas behind it.
fn apply_default_background_color(native: &NativeHandle, controller: &ICoreWebView2Controller) {
    let color = match native.try_borrow() {
        Ok(view) => view.background_color,
        Err(_) => return,
    };
    let Ok(controller2) = controller.cast::<ICoreWebView2Controller2>() else {
        emit_diagnostic(
            native,
            DIAGNOSTIC_WARN,
            "controller.background.unsupported",
            "ICoreWebView2Controller2 is unavailable, keeping the default background".to_string(),
            String::new(),
        );
        return;
    };
    let value = COREWEBVIEW2_COLOR {
        A: ((color >> 24) & 0xFF) as u8,
        R: ((color >> 16) & 0xFF) as u8,
        G: ((color >> 8) & 0xFF) as u8,
        B: (color & 0xFF) as u8,
    };
    if let Err(error) = unsafe { controller2.SetDefaultBackgroundColor(value) } {
        emit_diagnostic(
            native,
            DIAGNOSTIC_WARN,
            "controller.background.failed",
            format_windows_error(error),
            String::new(),
        );
    }
}

fn configure_webview_application_settings(webview: &ICoreWebView2) -> BridgeResult<()> {
    let settings = unsafe { webview.Settings().map_err(format_windows_error)? };
    unsafe {
        settings
            .SetIsScriptEnabled(true)
            .map_err(format_windows_error)?;
        settings
            .SetIsWebMessageEnabled(true)
            .map_err(format_windows_error)?;
        settings
            .SetAreDefaultScriptDialogsEnabled(false)
            .map_err(format_windows_error)?;
        settings
            .SetIsStatusBarEnabled(false)
            .map_err(format_windows_error)?;
        // This blocks user entry points only; host code can still call OpenDevToolsWindow.
        settings
            .SetAreDevToolsEnabled(false)
            .map_err(format_windows_error)?;
        settings
            .SetAreDefaultContextMenusEnabled(false)
            .map_err(format_windows_error)?;
        settings
            .SetAreHostObjectsAllowed(false)
            .map_err(format_windows_error)?;
        settings
            .SetIsZoomControlEnabled(false)
            .map_err(format_windows_error)?;
        settings
            .SetIsBuiltInErrorPageEnabled(false)
            .map_err(format_windows_error)?;
    }

    if let Ok(settings3) = settings.cast::<ICoreWebView2Settings3>() {
        unsafe {
            settings3
                .SetAreBrowserAcceleratorKeysEnabled(false)
                .map_err(format_windows_error)?;
        }
    }
    if let Ok(settings4) = settings.cast::<ICoreWebView2Settings4>() {
        unsafe {
            settings4
                .SetIsGeneralAutofillEnabled(false)
                .map_err(format_windows_error)?;
            settings4
                .SetIsPasswordAutosaveEnabled(false)
                .map_err(format_windows_error)?;
        }
    }
    if let Ok(settings6) = settings.cast::<ICoreWebView2Settings6>() {
        unsafe {
            settings6
                .SetIsSwipeNavigationEnabled(false)
                .map_err(format_windows_error)?;
        }
    }

    Ok(())
}

fn emit_diagnostic(native: &NativeHandle, level: jint, event: &str, message: String, data: String) {
    let callbacks = match native.try_borrow() {
        Ok(view) => view.callbacks.clone(),
        Err(_) => return,
    };
    callbacks.on_native_diagnostic(level, event, message, data);
}

fn emit_perf_diagnostic(
    native: &NativeHandle,
    event: &str,
    started_at: Instant,
    mut data: Vec<(&str, String)>,
) {
    let elapsed = started_at.elapsed();
    data.push(("elapsedMs", elapsed.as_millis().to_string()));
    emit_diagnostic(
        native,
        DIAGNOSTIC_TRACE,
        event,
        "WebView2 perf timing".to_string(),
        diagnostic_data(data),
    );
}

fn measure_perf<T>(
    native: &NativeHandle,
    event: &str,
    data: Vec<(&str, String)>,
    action: impl FnOnce() -> BridgeResult<T>,
) -> BridgeResult<T> {
    let started_at = Instant::now();
    let result = action();
    if result.is_ok() {
        emit_perf_diagnostic(native, event, started_at, data);
    }
    result
}

fn record_navigation_start(
    native: &NativeHandle,
    navigation_id: Option<u64>,
    data: &mut Vec<(&str, String)>,
) {
    let now = Instant::now();
    if let Ok(mut view) = native.try_borrow_mut() {
        let timing = NavigationTiming {
            requested_at: view.last_navigation_requested_at,
            started_at: now,
        };
        if let Some(requested_at) = timing.requested_at {
            append_elapsed_ms(data, "sinceLoadCallMs", requested_at);
        }
        if let Some(navigation_id) = navigation_id {
            view.navigation_timings.insert(navigation_id, timing);
        } else {
            view.unidentified_navigation_timing = Some(timing);
        }
    }
}

fn append_navigation_progress_timings(
    native: &NativeHandle,
    navigation_id: Option<u64>,
    data: &mut Vec<(&str, String)>,
) {
    if let Ok(view) = native.try_borrow() {
        if let Some(timing) = current_navigation_timing(&view, navigation_id) {
            append_navigation_timing(data, timing);
        }
    }
}

fn complete_navigation_timings(
    native: &NativeHandle,
    navigation_id: Option<u64>,
    data: &mut Vec<(&str, String)>,
) {
    if let Ok(mut view) = native.try_borrow_mut() {
        let timing = if let Some(navigation_id) = navigation_id {
            view.navigation_timings.remove(&navigation_id)
        } else if view.navigation_timings.len() == 1 {
            let navigation_id = *view.navigation_timings.keys().next().unwrap();
            view.navigation_timings.remove(&navigation_id)
        } else {
            view.unidentified_navigation_timing.take()
        };
        if let Some(timing) = timing {
            append_navigation_timing(data, timing);
        }
        if view.navigation_timings.is_empty() && view.unidentified_navigation_timing.is_none() {
            view.last_navigation_requested_at = None;
        }
    }
}

fn current_navigation_timing(
    view: &NativeWebView,
    navigation_id: Option<u64>,
) -> Option<NavigationTiming> {
    if let Some(navigation_id) = navigation_id {
        return view.navigation_timings.get(&navigation_id).copied();
    }
    if view.navigation_timings.len() == 1 {
        return view.navigation_timings.values().next().copied();
    }
    view.unidentified_navigation_timing
}

fn append_navigation_timing(data: &mut Vec<(&str, String)>, timing: NavigationTiming) {
    if let Some(requested_at) = timing.requested_at {
        append_elapsed_ms(data, "sinceLoadCallMs", requested_at);
    }
    append_elapsed_ms(data, "sinceNavigationStartMs", timing.started_at);
}

fn append_elapsed_ms(data: &mut Vec<(&str, String)>, name: &'static str, started_at: Instant) {
    data.push((name, started_at.elapsed().as_millis().to_string()));
}

fn log_environment_metadata(environment: &ICoreWebView2Environment, native: &NativeHandle) {
    let mut data = Vec::new();
    if let Some(version) = unsafe { get_pwstr(|value| environment.BrowserVersionString(value)) } {
        data.push(("browserVersion", version));
    }
    if let Ok(environment7) = environment.cast::<ICoreWebView2Environment7>() {
        if let Some(user_data_folder) =
            unsafe { get_pwstr(|value| environment7.UserDataFolder(value)) }
        {
            data.push(("userDataFolder", user_data_folder));
        }
    }
    if let Ok(environment11) = environment.cast::<ICoreWebView2Environment11>() {
        if let Some(failure_report_folder) =
            unsafe { get_pwstr(|value| environment11.FailureReportFolderPath(value)) }
        {
            data.push(("failureReportFolder", failure_report_folder));
        }
    }
    emit_diagnostic(
        native,
        DIAGNOSTIC_INFO,
        "runtime.environment",
        "WebView2 environment created".to_string(),
        diagnostic_data(data),
    );
}

fn attach_environment_diagnostics(
    environment: &ICoreWebView2Environment,
    generation: u64,
) -> BridgeResult<()> {
    let version_handler = NewBrowserVersionAvailableEventHandler::create(Box::new(move |_, _| {
        emit_shared_environment_diagnostic(
            generation,
            DIAGNOSTIC_INFO,
            "runtime.new-browser-version-available",
            "A new WebView2 runtime version is available".to_string(),
            String::new(),
        );
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        environment
            .add_NewBrowserVersionAvailable(&version_handler, &mut token)
            .map_err(format_windows_error)?;
    }

    if let Ok(environment5) = environment.cast::<ICoreWebView2Environment5>() {
        let handler = BrowserProcessExitedEventHandler::create(Box::new(move |_, args| {
            if let Some(args) = args {
                handle_browser_process_exited(generation, &args);
            }
            Ok(())
        }));
        let mut token = EventRegistrationToken::default();
        unsafe {
            environment5
                .add_BrowserProcessExited(&handler, &mut token)
                .map_err(format_windows_error)?;
        }
    }

    if let Ok(environment8) = environment.cast::<ICoreWebView2Environment8>() {
        log_process_infos(&environment8, generation);
        let environment_for_processes = environment8.clone();
        let handler = ProcessInfosChangedEventHandler::create(Box::new(move |_, _| {
            log_process_infos(&environment_for_processes, generation);
            Ok(())
        }));
        let mut token = EventRegistrationToken::default();
        unsafe {
            environment8
                .add_ProcessInfosChanged(&handler, &mut token)
                .map_err(format_windows_error)?;
        }
    }

    Ok(())
}

fn attach_webview_diagnostics(webview: &ICoreWebView2, native: NativeHandle) -> BridgeResult<()> {
    attach_process_failed_handler(webview, native.clone())?;
    attach_navigation_handlers(webview, native.clone())?;
    attach_trace_webview_handlers(webview, native)?;
    Ok(())
}

fn attach_process_failed_handler(
    webview: &ICoreWebView2,
    native: NativeHandle,
) -> BridgeResult<()> {
    let handler = ProcessFailedEventHandler::create(Box::new(move |_, args| {
        if let Some(args) = args {
            handle_process_failed(&native, &args);
        }
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        webview
            .add_ProcessFailed(&handler, &mut token)
            .map_err(format_windows_error)?;
    }
    Ok(())
}

fn attach_navigation_handlers(webview: &ICoreWebView2, native: NativeHandle) -> BridgeResult<()> {
    let native_for_start = native.clone();
    let starting_handler = NavigationStartingEventHandler::create(Box::new(move |_, args| {
        if let Some(args) = args {
            let mut data = Vec::new();
            if let Some(uri) = unsafe { get_pwstr(|value| args.Uri(value)) } {
                data.push(("uri", uri));
            }
            let navigation_id = unsafe { get_u64(|value| args.NavigationId(value)) };
            if let Some(navigation_id) = navigation_id {
                data.push(("navigationId", navigation_id.to_string()));
            }
            record_navigation_start(&native_for_start, navigation_id, &mut data);
            emit_diagnostic(
                &native_for_start,
                DIAGNOSTIC_DEBUG,
                "navigation.starting",
                "WebView2 navigation starting".to_string(),
                diagnostic_data(data),
            );
        }
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        webview
            .add_NavigationStarting(&starting_handler, &mut token)
            .map_err(format_windows_error)?;
    }

    let native_for_completed = native.clone();
    let completed_handler = NavigationCompletedEventHandler::create(Box::new(move |_, args| {
        if let Some(args) = args {
            handle_navigation_completed(&native_for_completed, &args);
        }
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        webview
            .add_NavigationCompleted(&completed_handler, &mut token)
            .map_err(format_windows_error)?;
    }

    let native_for_content = native.clone();
    let content_handler = ContentLoadingEventHandler::create(Box::new(move |_, args| {
        if let Some(args) = args {
            let mut data = Vec::new();
            let navigation_id = unsafe { get_u64(|value| args.NavigationId(value)) };
            if let Some(navigation_id) = navigation_id {
                data.push(("navigationId", navigation_id.to_string()));
            }
            if let Some(is_error_page) = unsafe { get_bool(|value| args.IsErrorPage(value)) } {
                data.push(("isErrorPage", is_error_page.to_string()));
            }
            append_navigation_progress_timings(&native_for_content, navigation_id, &mut data);
            emit_diagnostic(
                &native_for_content,
                DIAGNOSTIC_DEBUG,
                "navigation.content-loading",
                "WebView2 content loading".to_string(),
                diagnostic_data(data),
            );
        }
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        webview
            .add_ContentLoading(&content_handler, &mut token)
            .map_err(format_windows_error)?;
    }

    if let Ok(webview2) = webview.cast::<ICoreWebView2_2>() {
        let native_for_dom = native.clone();
        let handler = DOMContentLoadedEventHandler::create(Box::new(move |_, args| {
            if let Some(args) = args {
                let mut data = Vec::new();
                let navigation_id = unsafe { get_u64(|value| args.NavigationId(value)) };
                if let Some(navigation_id) = navigation_id {
                    data.push(("navigationId", navigation_id.to_string()));
                }
                append_navigation_progress_timings(&native_for_dom, navigation_id, &mut data);
                emit_diagnostic(
                    &native_for_dom,
                    DIAGNOSTIC_DEBUG,
                    "navigation.dom-content-loaded",
                    "WebView2 DOMContentLoaded".to_string(),
                    diagnostic_data(data),
                );
            }
            Ok(())
        }));
        let mut token = EventRegistrationToken::default();
        unsafe {
            webview2
                .add_DOMContentLoaded(&handler, &mut token)
                .map_err(format_windows_error)?;
        }
    }

    let native_for_source = native.clone();
    let source_handler = SourceChangedEventHandler::create(Box::new(move |_, args| {
        if let Some(args) = args {
            let mut data = Vec::new();
            if let Some(is_new_document) = unsafe { get_bool(|value| args.IsNewDocument(value)) } {
                data.push(("isNewDocument", is_new_document.to_string()));
            }
            append_navigation_progress_timings(&native_for_source, None, &mut data);
            emit_diagnostic(
                &native_for_source,
                DIAGNOSTIC_DEBUG,
                "navigation.source-changed",
                "WebView2 source changed".to_string(),
                diagnostic_data(data),
            );
        }
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        webview
            .add_SourceChanged(&source_handler, &mut token)
            .map_err(format_windows_error)?;
    }

    let native_for_history = native.clone();
    let history_handler = HistoryChangedEventHandler::create(Box::new(move |_, _| {
        emit_diagnostic(
            &native_for_history,
            DIAGNOSTIC_DEBUG,
            "navigation.history-changed",
            "WebView2 history changed".to_string(),
            String::new(),
        );
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        webview
            .add_HistoryChanged(&history_handler, &mut token)
            .map_err(format_windows_error)?;
    }

    Ok(())
}

fn attach_trace_webview_handlers(
    webview: &ICoreWebView2,
    native: NativeHandle,
) -> BridgeResult<()> {
    if let Ok(webview2) = webview.cast::<ICoreWebView2_2>() {
        let native_for_resource = native.clone();
        let resource_handler =
            WebResourceResponseReceivedEventHandler::create(Box::new(move |_, args| {
                if let Some(args) = args {
                    handle_web_resource_response_received(&native_for_resource, &args);
                }
                Ok(())
            }));
        let mut token = EventRegistrationToken::default();
        unsafe {
            webview2
                .add_WebResourceResponseReceived(&resource_handler, &mut token)
                .map_err(format_windows_error)?;
        }
    }

    let native_for_permission = native.clone();
    let permission_handler = PermissionRequestedEventHandler::create(Box::new(move |_, args| {
        if let Some(args) = args {
            let mut data = Vec::new();
            if let Some(uri) = unsafe { get_pwstr(|value| args.Uri(value)) } {
                data.push(("uri", uri));
            }
            if let Some(kind) = unsafe { get_permission_kind(|value| args.PermissionKind(value)) } {
                data.push(("kind", permission_kind_name(kind).to_string()));
                data.push(("kindCode", kind.0.to_string()));
            }
            if let Some(is_user_initiated) =
                unsafe { get_bool(|value| args.IsUserInitiated(value)) }
            {
                data.push(("isUserInitiated", is_user_initiated.to_string()));
            }
            emit_diagnostic(
                &native_for_permission,
                DIAGNOSTIC_TRACE,
                "security.permission-requested",
                "WebView2 permission requested".to_string(),
                diagnostic_data(data),
            );
        }
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        webview
            .add_PermissionRequested(&permission_handler, &mut token)
            .map_err(format_windows_error)?;
    }

    let native_for_new_window = native.clone();
    let new_window_handler = NewWindowRequestedEventHandler::create(Box::new(move |_, args| {
        if let Some(args) = args {
            let mut data = Vec::new();
            if let Some(uri) = unsafe { get_pwstr(|value| args.Uri(value)) } {
                data.push(("uri", uri));
            }
            if let Some(is_user_initiated) =
                unsafe { get_bool(|value| args.IsUserInitiated(value)) }
            {
                data.push(("isUserInitiated", is_user_initiated.to_string()));
            }
            emit_diagnostic(
                &native_for_new_window,
                DIAGNOSTIC_TRACE,
                "security.new-window-requested",
                "WebView2 new window requested".to_string(),
                diagnostic_data(data),
            );
        }
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        webview
            .add_NewWindowRequested(&new_window_handler, &mut token)
            .map_err(format_windows_error)?;
    }

    if let Ok(webview10) = webview.cast::<ICoreWebView2_10>() {
        let native_for_auth = native.clone();
        let handler = BasicAuthenticationRequestedEventHandler::create(Box::new(move |_, args| {
            if let Some(args) = args {
                let mut data = Vec::new();
                if let Some(uri) = unsafe { get_pwstr(|value| args.Uri(value)) } {
                    data.push(("uri", uri));
                }
                if let Some(challenge) = unsafe { get_pwstr(|value| args.Challenge(value)) } {
                    data.push(("challenge", challenge));
                }
                emit_diagnostic(
                    &native_for_auth,
                    DIAGNOSTIC_TRACE,
                    "security.basic-auth-requested",
                    "WebView2 basic authentication requested".to_string(),
                    diagnostic_data(data),
                );
            }
            Ok(())
        }));
        let mut token = EventRegistrationToken::default();
        unsafe {
            webview10
                .add_BasicAuthenticationRequested(&handler, &mut token)
                .map_err(format_windows_error)?;
        }
    }

    if let Ok(webview14) = webview.cast::<ICoreWebView2_14>() {
        let native_for_cert = native.clone();
        let handler =
            ServerCertificateErrorDetectedEventHandler::create(Box::new(move |_, args| {
                if let Some(args) = args {
                    let mut data = Vec::new();
                    if let Some(uri) = unsafe { get_pwstr(|value| args.RequestUri(value)) } {
                        data.push(("uri", uri));
                    }
                    if let Some(error_status) =
                        unsafe { get_web_error_status(|value| args.ErrorStatus(value)) }
                    {
                        data.push(("errorStatus", format!("{error_status:?}")));
                        data.push(("errorStatusCode", error_status.0.to_string()));
                    }
                    emit_diagnostic(
                        &native_for_cert,
                        DIAGNOSTIC_TRACE,
                        "security.server-certificate-error",
                        "WebView2 server certificate error detected".to_string(),
                        diagnostic_data(data),
                    );
                }
                Ok(())
            }));
        let mut token = EventRegistrationToken::default();
        unsafe {
            webview14
                .add_ServerCertificateErrorDetected(&handler, &mut token)
                .map_err(format_windows_error)?;
        }
    }

    Ok(())
}

fn handle_navigation_completed(
    native: &NativeHandle,
    args: &ICoreWebView2NavigationCompletedEventArgs,
) {
    let mut data = Vec::new();
    let is_success = unsafe { get_bool(|value| args.IsSuccess(value)) }.unwrap_or(false);
    data.push(("isSuccess", is_success.to_string()));
    let navigation_id = unsafe { get_u64(|value| args.NavigationId(value)) };
    if let Some(navigation_id) = navigation_id {
        data.push(("navigationId", navigation_id.to_string()));
    }
    if let Some(web_error_status) =
        unsafe { get_web_error_status(|value| args.WebErrorStatus(value)) }
    {
        data.push(("webErrorStatus", format!("{web_error_status:?}")));
        data.push(("webErrorStatusCode", web_error_status.0.to_string()));
    }
    if let Ok(args2) = args.cast::<ICoreWebView2NavigationCompletedEventArgs2>() {
        if let Some(http_status_code) = unsafe { get_i32(|value| args2.HttpStatusCode(value)) } {
            data.push(("httpStatusCode", http_status_code.to_string()));
        }
    }
    complete_navigation_timings(native, navigation_id, &mut data);
    emit_diagnostic(
        native,
        if is_success {
            DIAGNOSTIC_INFO
        } else {
            DIAGNOSTIC_WARN
        },
        "navigation.completed",
        if is_success {
            "WebView2 navigation completed".to_string()
        } else {
            "WebView2 navigation failed".to_string()
        },
        diagnostic_data(data),
    );
}

fn handle_web_resource_response_received(
    native: &NativeHandle,
    args: &ICoreWebView2WebResourceResponseReceivedEventArgs,
) {
    let mut data = Vec::new();
    if let Ok(request) = unsafe { args.Request() } {
        if let Some(uri) = unsafe { get_pwstr(|value| request.Uri(value)) } {
            data.push(("uri", uri));
        }
        if let Some(method) = unsafe { get_pwstr(|value| request.Method(value)) } {
            data.push(("method", method));
        }
    }
    if let Ok(response) = unsafe { args.Response() } {
        if let Some(status_code) = unsafe { get_i32(|value| response.StatusCode(value)) } {
            data.push(("statusCode", status_code.to_string()));
        }
        if let Some(reason_phrase) = unsafe { get_pwstr(|value| response.ReasonPhrase(value)) } {
            data.push(("reasonPhrase", reason_phrase));
        }
    }
    emit_diagnostic(
        native,
        DIAGNOSTIC_TRACE,
        "resource.response-received",
        "WebView2 resource response received".to_string(),
        diagnostic_data(data),
    );
}

fn handle_process_failed(native: &NativeHandle, args: &ICoreWebView2ProcessFailedEventArgs) {
    let Some(kind) = (unsafe { get_process_failed_kind(|value| args.ProcessFailedKind(value)) })
    else {
        emit_diagnostic(
            native,
            DIAGNOSTIC_ERROR,
            "process-failed.fatal",
            "WebView2 process failed without kind".to_string(),
            String::new(),
        );
        return;
    };
    let mut data = vec![
        ("kind", process_failed_kind_name(kind).to_string()),
        ("kindCode", kind.0.to_string()),
    ];
    let mut reason = None;
    if let Ok(args2) = args.cast::<ICoreWebView2ProcessFailedEventArgs2>() {
        reason = unsafe { get_process_failed_reason(|value| args2.Reason(value)) };
        if let Some(reason) = reason {
            data.push(("reason", process_failed_reason_name(reason).to_string()));
            data.push(("reasonCode", reason.0.to_string()));
        }
        if let Some(exit_code) = unsafe { get_i32(|value| args2.ExitCode(value)) } {
            data.push(("exitCode", exit_code.to_string()));
        }
        if let Some(description) = unsafe { get_pwstr(|value| args2.ProcessDescription(value)) } {
            data.push(("processDescription", description));
        }
    }
    if let Ok(args3) = args.cast::<ICoreWebView2ProcessFailedEventArgs3>() {
        if let Some(path) = unsafe { get_pwstr(|value| args3.FailureSourceModulePath(value)) } {
            data.push(("failureSourceModulePath", path));
        }
    }

    let event = match (kind, reason) {
        (COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_UNRESPONSIVE, _) => {
            "process-failed.unresponsive"
        }
        (_, Some(COREWEBVIEW2_PROCESS_FAILED_REASON_PROFILE_DELETED)) => "process-failed.fatal",
        (COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED, _)
        | (COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED, _)
        | (COREWEBVIEW2_PROCESS_FAILED_KIND_FRAME_RENDER_PROCESS_EXITED, _) => {
            "process-failed.fatal"
        }
        _ => "process-failed.nonfatal",
    };
    emit_diagnostic(
        native,
        DIAGNOSTIC_ERROR,
        event,
        format!(
            "WebView2 process failed: {}",
            process_failed_kind_name(kind)
        ),
        diagnostic_data(data),
    );
}

fn handle_browser_process_exited(
    generation: u64,
    args: &ICoreWebView2BrowserProcessExitedEventArgs,
) {
    let mut data = Vec::new();
    let exit_kind = unsafe { get_browser_exit_kind(|value| args.BrowserProcessExitKind(value)) };
    if let Some(exit_kind) = exit_kind {
        data.push(("exitKind", browser_exit_kind_name(exit_kind).to_string()));
        data.push(("exitKindCode", exit_kind.0.to_string()));
    }
    if let Some(process_id) = unsafe { get_u32(|value| args.BrowserProcessId(value)) } {
        data.push(("processId", process_id.to_string()));
    }
    let failed = exit_kind == Some(COREWEBVIEW2_BROWSER_PROCESS_EXIT_KIND_FAILED);
    let event = if failed {
        "browser-process-exited.fatal"
    } else {
        "browser-process-exited"
    };
    let targets = if failed {
        invalidate_shared_environment(generation)
    } else {
        shared_environment_active_views(generation)
    };
    for native in targets {
        emit_diagnostic(
            &native,
            if failed {
                DIAGNOSTIC_ERROR
            } else {
                DIAGNOSTIC_INFO
            },
            event,
            "WebView2 browser process exited".to_string(),
            diagnostic_data(data.clone()),
        );
    }
}

fn log_process_infos(environment: &ICoreWebView2Environment8, generation: u64) {
    let Ok(collection) = (unsafe { environment.GetProcessInfos() }) else {
        return;
    };
    let Some(count) = (unsafe { get_u32(|value| collection.Count(value)) }) else {
        return;
    };
    let mut processes = Vec::new();
    for index in 0..count {
        let Ok(info) = (unsafe { collection.GetValueAtIndex(index) }) else {
            continue;
        };
        let process_id = unsafe { get_i32(|value| info.ProcessId(value)) }
            .map(|value| value.to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let kind = unsafe { get_process_kind(|value| info.Kind(value)) }
            .map(|value| process_kind_name(value).to_string())
            .unwrap_or_else(|| "unknown".to_string());
        processes.push(format!("{process_id}:{kind}"));
    }
    emit_shared_environment_diagnostic(
        generation,
        DIAGNOSTIC_TRACE,
        "runtime.process-infos-changed",
        "WebView2 process info snapshot".to_string(),
        diagnostic_data(vec![
            ("count", count.to_string()),
            ("processes", processes.join(",")),
        ]),
    );
}

fn emit_shared_environment_diagnostic(
    generation: u64,
    level: jint,
    event: &str,
    message: String,
    data: String,
) {
    for native in shared_environment_active_views(generation) {
        emit_diagnostic(&native, level, event, message.clone(), data.clone());
    }
}

fn shared_environment_active_views(generation: u64) -> Vec<NativeHandle> {
    SHARED_ENVIRONMENT_MANAGER.with(|manager| manager.borrow_mut().active_views(generation))
}

fn shared_environment_started_at(generation: u64) -> Option<Instant> {
    SHARED_ENVIRONMENT_MANAGER.with(|manager| manager.borrow().environment_started_at(generation))
}

fn invalidate_shared_environment(generation: u64) -> Vec<NativeHandle> {
    SHARED_ENVIRONMENT_MANAGER
        .with(|manager| manager.borrow_mut().invalidate_environment(generation))
}

fn register_shared_environment_active_view(generation: u64, native: NativeHandle) -> bool {
    SHARED_ENVIRONMENT_MANAGER.with(|manager| {
        manager
            .borrow_mut()
            .register_active_view(generation, native)
    })
}

fn unregister_shared_environment_view(handle: jlong) {
    SHARED_ENVIRONMENT_MANAGER.with(|manager| manager.borrow_mut().unregister_view(handle));
}

fn is_shared_environment_current(generation: u64) -> bool {
    SHARED_ENVIRONMENT_MANAGER.with(|manager| manager.borrow().is_current_generation(generation))
}

fn remove_view_handle(views: &mut Vec<NativeHandle>, handle: jlong) {
    views.retain(|view| native_handle(view) != handle && !is_native_destroyed(view));
}

fn prune_destroyed_views(views: &mut Vec<NativeHandle>) {
    views.retain(|view| !is_native_destroyed(view));
}

fn native_handle(native: &NativeHandle) -> jlong {
    native
        .try_borrow()
        .map(|view| view.handle)
        .unwrap_or_default()
}

fn is_native_destroyed(native: &NativeHandle) -> bool {
    native
        .try_borrow()
        .map(|view| view.destroyed)
        .unwrap_or(false)
}

fn attach_web_resource_requested_handler(
    environment: &ICoreWebView2Environment,
    webview: &ICoreWebView2,
    native: NativeHandle,
) -> BridgeResult<EventRegistrationToken> {
    let environment = environment.clone();
    let native_for_callback = native.clone();
    let handler = WebResourceRequestedEventHandler::create(Box::new(move |_, args| {
        if let Some(args) = args {
            if let Err(message) =
                handle_web_resource_requested(&environment, &native_for_callback, &args)
            {
                let callbacks = native_for_callback
                    .try_borrow()
                    .ok()
                    .map(|view| view.callbacks.clone());
                if let Some(callbacks) = callbacks {
                    callbacks.on_log(
                        DIAGNOSTIC_WARN,
                        format!("WinWebView2Bridge: asset request failed: {message}"),
                    );
                }
            }
        }
        Ok(())
    }));
    let mut token = EventRegistrationToken::default();
    unsafe {
        add_web_resource_requested_filter(webview, WEBVIEW_ASSET_CUSTOM_SCHEME_FILTER)?;
        add_web_resource_requested_filter(webview, WEBVIEW_ASSET_HTTPS_FILTER)?;
        webview
            .add_WebResourceRequested(&handler, &mut token)
            .map_err(format_windows_error)?;
    }
    Ok(token)
}

fn add_web_resource_requested_filter(webview: &ICoreWebView2, filter: &str) -> BridgeResult<()> {
    unsafe {
        webview.AddWebResourceRequestedFilter(
            &HSTRING::from(filter),
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL,
        )
    }
    .map_err(format_windows_error)
}

fn remove_web_resource_requested_filter(webview: &ICoreWebView2, filter: &str) {
    unsafe {
        let _ = webview.RemoveWebResourceRequestedFilter(
            &HSTRING::from(filter),
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL,
        );
    }
}

fn handle_web_resource_requested(
    environment: &ICoreWebView2Environment,
    native: &NativeHandle,
    args: &ICoreWebView2WebResourceRequestedEventArgs,
) -> BridgeResult<()> {
    let request = unsafe { args.Request().map_err(format_windows_error)? };
    let mut uri = PWSTR::null();
    unsafe {
        request.Uri(&mut uri).map_err(format_windows_error)?;
    }
    let url = take_pwstr(uri);
    let callbacks = native
        .try_borrow()
        .map_err(|_| "WebView2 state is busy while resolving asset".to_string())?
        .callbacks
        .clone();
    let deferral = unsafe { args.GetDeferral().map_err(format_windows_error)? };
    let (handle, request_id) = {
        let mut view = native
            .try_borrow_mut()
            .map_err(|_| "WebView2 state is busy while deferring asset resolution".to_string())?;
        let request_id = view.next_asset_request_id;
        view.next_asset_request_id = view.next_asset_request_id.wrapping_add(1).max(1);
        view.pending_asset_requests.insert(
            request_id,
            PendingAssetRequest {
                environment: environment.clone(),
                args: args.clone(),
                deferral,
            },
        );
        (view.handle, request_id)
    };

    if let Err(error) = callbacks.on_asset_requested(handle, request_id, url) {
        let request = native
            .try_borrow_mut()
            .map_err(|_| "WebView2 state is busy while cancelling asset deferral".to_string())?
            .pending_asset_requests
            .remove(&request_id);
        if let Some(request) = request {
            unsafe {
                let _ = request.deferral.Complete();
            }
        }
        return Err(error);
    }
    Ok(())
}

fn complete_asset_request(
    native: &NativeHandle,
    request_id: u64,
    response: BridgeResult<Option<NativeAssetResponse>>,
) -> BridgeResult<()> {
    let request = native
        .borrow_mut()
        .pending_asset_requests
        .remove(&request_id)
        .ok_or_else(|| format!("Unknown deferred WebView2 asset request: {request_id}"))?;
    let result = (|| {
        let Some(asset_response) = response? else {
            return Ok(());
        };
        let response = create_web_resource_response(&request.environment, asset_response)?;
        unsafe {
            request
                .args
                .SetResponse(&response)
                .map_err(format_windows_error)
        }
    })();
    let completion = unsafe { request.deferral.Complete().map_err(format_windows_error) };
    result.and(completion)
}

fn set_virtual_host_name_to_folder_mapping(
    webview: &ICoreWebView2,
    host_name: &str,
    folder_path: &str,
) -> BridgeResult<()> {
    let webview3 = webview
        .cast::<ICoreWebView2_3>()
        .map_err(format_windows_error)?;
    unsafe {
        webview3
            .SetVirtualHostNameToFolderMapping(
                &HSTRING::from(host_name),
                &HSTRING::from(folder_path),
                COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_DENY_CORS,
            )
            .map_err(format_windows_error)
    }
}

fn create_web_resource_response(
    environment: &ICoreWebView2Environment,
    asset_response: NativeAssetResponse,
) -> BridgeResult<ICoreWebView2WebResourceResponse> {
    let stream = unsafe { SHCreateMemStream(Some(&asset_response.bytes)) }
        .ok_or_else(|| "SHCreateMemStream returned null".to_string())?;
    unsafe {
        environment
            .CreateWebResourceResponse(
                &stream,
                asset_response.status_code,
                &HSTRING::from(asset_response.status_text),
                &HSTRING::from(asset_response.headers),
            )
            .map_err(format_windows_error)
    }
}

fn attach_ipc_handler(
    webview: &ICoreWebView2,
    native: NativeHandle,
) -> BridgeResult<EventRegistrationToken> {
    let mut token = EventRegistrationToken::default();
    unsafe {
        webview
            .add_WebMessageReceived(
                &WebMessageReceivedEventHandler::create(Box::new(move |_, args| {
                    let Some(args) = args else {
                        return Ok(());
                    };

                    let mut message = PWSTR::null();
                    args.TryGetWebMessageAsString(&mut message)?;
                    let message = take_pwstr(message);
                    let callbacks = native.try_borrow().ok().map(|view| view.callbacks.clone());
                    if let Some(callbacks) = callbacks {
                        callbacks.on_message(message);
                    }
                    Ok(())
                })),
                &mut token,
            )
            .map_err(format_windows_error)?;
    }
    Ok(token)
}

fn attach_accelerator_key_handler(
    controller: &ICoreWebView2Controller,
    native: NativeHandle,
) -> BridgeResult<EventRegistrationToken> {
    let mut token = EventRegistrationToken::default();
    unsafe {
        controller
            .add_AcceleratorKeyPressed(
                &AcceleratorKeyPressedEventHandler::create(Box::new(move |_, args| {
                    let Some(args) = args else {
                        return Ok(());
                    };

                    let mut key_event_kind = COREWEBVIEW2_KEY_EVENT_KIND::default();
                    args.KeyEventKind(&mut key_event_kind)?;
                    let mut virtual_key = 0;
                    args.VirtualKey(&mut virtual_key)?;
                    let mut key_event_lparam = 0;
                    args.KeyEventLParam(&mut key_event_lparam)?;

                    let callbacks = native.try_borrow().ok().map(|view| view.callbacks.clone());
                    let handled = callbacks
                        .map(|callbacks| {
                            callbacks.on_accelerator_key_pressed(
                                key_event_kind.0,
                                virtual_key as jint,
                                current_modifier_flags(),
                                key_event_lparam,
                            )
                        })
                        .unwrap_or(false);
                    if handled {
                        args.SetHandled(true)?;
                    }
                    Ok(())
                })),
                &mut token,
            )
            .map_err(format_windows_error)?;
    }
    Ok(token)
}

fn attach_got_focus_handler(
    controller: &ICoreWebView2Controller,
    native: NativeHandle,
) -> BridgeResult<EventRegistrationToken> {
    let mut token = EventRegistrationToken::default();
    unsafe {
        controller
            .add_GotFocus(
                &FocusChangedEventHandler::create(Box::new(move |_, _| {
                    let callbacks = native.try_borrow().ok().map(|view| view.callbacks.clone());
                    if let Some(callbacks) = callbacks {
                        callbacks.on_focus_gained();
                    }
                    Ok(())
                })),
                &mut token,
            )
            .map_err(format_windows_error)?;
    }
    Ok(token)
}

fn fail_create(native: &NativeHandle, message: String) {
    let callbacks = match native.try_borrow() {
        Ok(view) => view.callbacks.clone(),
        Err(_) => return,
    };
    let handle = native_handle(native);
    if handle != 0 {
        unregister_shared_environment_view(handle);
    }
    let _ = destroy_native_state(native);
    callbacks.on_create_failed(message);
    NATIVE_VIEWS.with(|views| {
        views.borrow_mut().remove(&handle);
    });
    remove_route(handle);
    callbacks.on_destroyed(handle);
}

fn remove_execute_script_handler(native: &NativeHandle, handler_id: u64) {
    if let Ok(mut view) = native.try_borrow_mut() {
        view.execute_script_handlers
            .retain(|(id, _)| *id != handler_id);
    }
}

fn remove_dev_tools_handler(native: &NativeHandle, handler_id: u64) {
    if let Ok(mut view) = native.try_borrow_mut() {
        view.dev_tools_handlers.retain(|(id, _)| *id != handler_id);
    }
}

fn install_document_start_scripts(
    webview: &ICoreWebView2,
    native: NativeHandle,
) -> BridgeResult<()> {
    let scripts = native
        .try_borrow()
        .map_err(|_| "WebView2 state is busy while reading document start scripts".to_string())?
        .document_start_scripts
        .clone();
    for script in scripts {
        install_document_start_script(webview, native.clone(), script)?;
    }
    Ok(())
}

fn install_document_start_script(
    webview: &ICoreWebView2,
    native: NativeHandle,
    script: String,
) -> BridgeResult<()> {
    let (handler, handler_id) = {
        let mut view = native.try_borrow_mut().map_err(|_| {
            "WebView2 state is busy while installing document start script".to_string()
        })?;
        let handler_id = view.next_script_handler_id;
        view.next_script_handler_id += 1;
        let native_for_callback = native.clone();
        let handler =
            AddScriptToExecuteOnDocumentCreatedCompletedHandler::create(Box::new(move |_, _| {
                remove_add_script_handler(&native_for_callback, handler_id);
                Ok(())
            }));
        view.add_script_handlers.push((handler_id, handler.clone()));
        (handler, handler_id)
    };
    let result =
        unsafe { webview.AddScriptToExecuteOnDocumentCreated(&HSTRING::from(script), &handler) };
    if let Err(error) = result {
        remove_add_script_handler(&native, handler_id);
        return Err(format_windows_error(error));
    }
    Ok(())
}

fn remove_add_script_handler(native: &NativeHandle, handler_id: u64) {
    if let Ok(mut view) = native.try_borrow_mut() {
        view.add_script_handlers.retain(|(id, _)| *id != handler_id);
    }
}

fn has_area(bounds: RECT) -> bool {
    bounds.right > bounds.left && bounds.bottom > bounds.top
}

fn store_clip_bounds(native: &NativeHandle, bounds: RECT) -> BridgeResult<()> {
    native
        .try_borrow_mut()
        .map_err(|_| "WebView2 state is busy after resizing the clip host".to_string())?
        .clip_bounds = bounds;
    Ok(())
}

fn store_controller_bounds(native: &NativeHandle, bounds: RECT) -> BridgeResult<()> {
    native
        .try_borrow_mut()
        .map_err(|_| "WebView2 state is busy after resizing the controller".to_string())?
        .controller_bounds = bounds;
    Ok(())
}

fn store_controller_visibility(native: &NativeHandle, visible: bool) -> BridgeResult<()> {
    native
        .try_borrow_mut()
        .map_err(|_| "WebView2 state is busy after changing controller visibility".to_string())?
        .controller_visible = visible;
    Ok(())
}

fn apply_composition_visual_clip(native: &NativeHandle, bounds: RECT) -> BridgeResult<()> {
    let bounds = effective_composition_clip_bounds(bounds);
    let (clip, device, previous_bounds, commit_generation, host_generation) = {
        let view = native
            .try_borrow()
            .map_err(|_| "WebView2 state is busy while reading the composition clip".to_string())?;
        match &view.presentation {
            PresentationHost::CompositionPoc(host) | PresentationHost::CompositionRootPoc(host) => {
                (
                    host.clip.clone(),
                    host.device.clone(),
                    host.visual_clip_bounds,
                    host.commit_generation + 1,
                    view.host_generation,
                )
            }
            PresentationHost::Windowed(_) | PresentationHost::WindowedRootPoc(_) => {
                return Err("composition clip requested for a windowed host".to_string())
            }
        }
    };
    if bounds == previous_bounds {
        return Ok(());
    }

    let started_at = Instant::now();
    if let Err(error) = set_composition_clip_objects(&clip, &device, bounds) {
        emit_diagnostic(
            native,
            DIAGNOSTIC_WARN,
            "direct-composition.clip.commit-failed",
            error.clone(),
            diagnostic_data(vec![
                ("geometryGeneration", host_generation.to_string()),
                ("commitGeneration", commit_generation.to_string()),
                ("visualClip", format_rect(bounds)),
                ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
            ]),
        );
        return Err(error);
    }
    {
        let mut view = native.try_borrow_mut().map_err(|_| {
            "WebView2 state is busy after committing the composition clip".to_string()
        })?;
        match &mut view.presentation {
            PresentationHost::CompositionPoc(host) | PresentationHost::CompositionRootPoc(host) => {
                host.visual_clip_bounds = bounds;
                host.commit_generation = commit_generation;
            }
            PresentationHost::Windowed(_) | PresentationHost::WindowedRootPoc(_) => {
                return Err("composition clip committed for a windowed host".to_string())
            }
        }
    }
    if placement_trace_enabled() {
        emit_diagnostic(
            native,
            DIAGNOSTIC_TRACE,
            "direct-composition.clip.committed",
            "DirectComposition visual clip committed".to_string(),
            diagnostic_data(vec![
                ("geometryGeneration", host_generation.to_string()),
                ("commitGeneration", commit_generation.to_string()),
                ("visualClip", format_rect(bounds)),
                ("commitResult", "S_OK".to_string()),
                (
                    "commitDurationMicros",
                    started_at.elapsed().as_micros().to_string(),
                ),
                ("threadId", unsafe { GetCurrentThreadId() }.to_string()),
            ]),
        );
    }
    Ok(())
}

fn composition_controller_target_bounds(
    native: &NativeHandle,
    requested_bounds: RECT,
) -> BridgeResult<RECT> {
    let stale_controller_bounds = presentation_selection().stale_controller_bounds;
    let mut view = native.try_borrow_mut().map_err(|_| {
        "WebView2 state is busy while selecting composition controller bounds".to_string()
    })?;
    let host = match &mut view.presentation {
        PresentationHost::CompositionPoc(host) | PresentationHost::CompositionRootPoc(host) => host,
        PresentationHost::Windowed(_) | PresentationHost::WindowedRootPoc(_) => {
            return Err("composition controller bounds requested for a windowed host".to_string())
        }
    };
    if has_area(requested_bounds) {
        host.maximum_controller_bounds.right = host
            .maximum_controller_bounds
            .right
            .max(requested_bounds.right);
        host.maximum_controller_bounds.bottom = host
            .maximum_controller_bounds
            .bottom
            .max(requested_bounds.bottom);
    }
    if stale_controller_bounds && has_area(requested_bounds) {
        Ok(host.maximum_controller_bounds)
    } else {
        Ok(requested_bounds)
    }
}

fn apply_composition_geometry(native: &NativeHandle, bounds: RECT) -> BridgeResult<()> {
    let root_host = native
        .try_borrow()
        .map_err(|_| "WebView2 state is busy while selecting composition geometry".to_string())?
        .presentation
        .mode()
        == PresentationMode::CompositionRootPoc;
    let local_bounds = if root_host {
        rect_from_size(bounds.right - bounds.left, bounds.bottom - bounds.top)
    } else {
        bounds
    };
    // The visual clip is the PoC's actual containment boundary. Commit it before changing either
    // the surface HWND or controller bounds so an intentionally stale WebView2 surface is safe.
    apply_composition_visual_clip(native, local_bounds)?;
    let (surface, surface_bounds, controller, controller_bounds) = {
        let view = native
            .try_borrow()
            .map_err(|_| "WebView2 state is busy while reading composition geometry".to_string())?;
        (
            view.clip_host,
            view.clip_bounds,
            view.controller.clone(),
            view.controller_bounds,
        )
    };
    if surface.0.is_null() {
        return Err("WebView2 composition surface HWND is null".to_string());
    }
    if bounds != surface_bounds {
        set_clip_host_bounds_window(surface, bounds)?;
        store_clip_bounds(native, bounds)?;
    }
    let target_bounds = composition_controller_target_bounds(native, local_bounds)?;
    if has_area(target_bounds) && target_bounds != controller_bounds {
        if let Some(controller) = controller {
            unsafe { controller.SetBounds(target_bounds) }.map_err(format_windows_error)?;
            store_controller_bounds(native, target_bounds)?;
        }
    }
    Ok(())
}

fn apply_clip_geometry(native: &NativeHandle, bounds: RECT) -> BridgeResult<()> {
    let mode = native
        .try_borrow()
        .map_err(|_| "WebView2 state is busy while selecting geometry path".to_string())?
        .presentation
        .mode();
    match mode {
        PresentationMode::Windowed => apply_windowed_clip_geometry(native, bounds),
        PresentationMode::WindowedRootPoc => apply_windowed_clip_geometry(native, bounds),
        PresentationMode::CompositionPoc => apply_composition_geometry(native, bounds),
        PresentationMode::CompositionRootPoc => apply_composition_geometry(native, bounds),
    }
}

/// Applies one host rectangle without holding a `RefCell` borrow across Win32 or COM calls. A root
/// host moves the clip HWND in root-client coordinates while the WebView2 controller remains at
/// the clip HWND's local origin. The shrink-first order reduces the stale-size interval.
fn apply_windowed_clip_geometry(native: &NativeHandle, bounds: RECT) -> BridgeResult<()> {
    let (clip_host, controller, clip_bounds, controller_bounds, root_host) = {
        let view = native
            .try_borrow()
            .map_err(|_| "WebView2 state is busy while reading clip geometry".to_string())?;
        (
            view.clip_host,
            view.controller.clone(),
            view.clip_bounds,
            view.controller_bounds,
            view.presentation.mode().is_root_host(),
        )
    };
    if clip_host.0.is_null() {
        return Err("WebView2 clip-host HWND is null".to_string());
    }

    let controller_target_bounds = if root_host {
        rect_from_size(bounds.right - bounds.left, bounds.bottom - bounds.top)
    } else {
        bounds
    };
    let clip_changed = bounds != clip_bounds;
    let shrinking = controller_target_bounds.right - controller_target_bounds.left
        < controller_bounds.right - controller_bounds.left
        || controller_target_bounds.bottom - controller_target_bounds.top
            < controller_bounds.bottom - controller_bounds.top;
    let clip_first = shrinking || !has_area(bounds) || controller.is_none();

    if clip_changed && clip_first {
        set_clip_host_bounds_window(clip_host, bounds)?;
        store_clip_bounds(native, bounds)?;
    }

    if has_area(controller_target_bounds) && controller_target_bounds != controller_bounds {
        if let Some(controller) = controller.as_ref() {
            unsafe { controller.SetBounds(controller_target_bounds) }
                .map_err(format_windows_error)?;
            store_controller_bounds(native, controller_target_bounds)?;
        }
    }

    if clip_changed && !clip_first {
        set_clip_host_bounds_window(clip_host, bounds)?;
        store_clip_bounds(native, bounds)?;
    }

    Ok(())
}

fn apply_clip_parent(native: &NativeHandle, target_parent: HWND) -> BridgeResult<bool> {
    let (clip_host, clip_parent) = {
        let view = native
            .try_borrow()
            .map_err(|_| "WebView2 state is busy while reading clip-host parent".to_string())?;
        (view.clip_host, view.clip_parent)
    };
    if clip_parent == target_parent {
        return Ok(false);
    }
    if target_parent.0.is_null() {
        return Err("WebView2 clip-host target parent is null".to_string());
    }
    set_clip_host_parent_window(clip_host, target_parent)?;
    native
        .try_borrow_mut()
        .map_err(|_| "WebView2 state is busy after re-parenting the clip host".to_string())?
        .clip_parent = target_parent;
    Ok(true)
}

fn apply_controller_visibility(native: &NativeHandle, visible: bool) -> BridgeResult<()> {
    let (controller, controller_visible) = {
        let view = native.try_borrow().map_err(|_| {
            "WebView2 state is busy while reading controller visibility".to_string()
        })?;
        (view.controller.clone(), view.controller_visible)
    };
    let Some(controller) = controller else {
        return Ok(());
    };
    if controller_visible == visible {
        return Ok(());
    }
    unsafe { controller.SetIsVisible(visible) }.map_err(format_windows_error)?;
    store_controller_visibility(native, visible)
}

/// Reconciles the stable clip-host HWND with the current Canvas or parking holder. WebView2 keeps
/// the clip host as its parent for its entire lifetime; only the plain bridge window is reparented.
fn reconcile(native: &NativeHandle) -> BridgeResult<()> {
    let (
        canvas,
        target_parent,
        clip_parent,
        controller,
        visible,
        width,
        height,
        screen_x,
        screen_y,
        presentation_mode,
        controller_visible,
    ) = {
        let view = native
            .try_borrow()
            .map_err(|_| "WebView2 state is busy while reading Canvas state".to_string())?;
        (
            view.canvas,
            view.target_parent,
            view.clip_parent,
            view.controller.clone(),
            view.visible,
            view.width,
            view.height,
            view.screen_x,
            view.screen_y,
            view.presentation.mode(),
            view.controller_visible,
        )
    };

    let bounds = if canvas.0.is_null() {
        RECT::default()
    } else if presentation_mode.is_root_host() {
        screen_rect_to_client(canvas, screen_x, screen_y, width, height)?
    } else {
        canvas_client_bounds(canvas).unwrap_or(RECT {
            left: 0,
            top: 0,
            right: width.max(0),
            bottom: height.max(0),
        })
    };
    let parent_changed = clip_parent != target_parent;
    let should_show = visible && has_area(bounds) && !canvas.0.is_null();

    // A reparent always starts hidden and clipped to zero. This protects the old Canvas while the
    // clip host moves and also covers a controller whose asynchronous creation finished parked.
    if controller_visible && (!should_show || parent_changed) {
        apply_controller_visibility(native, false)?;
    }
    if parent_changed {
        apply_clip_geometry(native, RECT::default())?;
    }
    let reparented = apply_clip_parent(native, target_parent)?;
    apply_clip_geometry(native, bounds)?;

    if reparented {
        if let Some(controller) = controller.as_ref() {
            if let Err(error) = unsafe { controller.NotifyParentWindowPositionChanged() } {
                emit_diagnostic(
                    native,
                    DIAGNOSTIC_WARN,
                    "clip-host.notify-parent-position.failed",
                    format_windows_error(error),
                    String::new(),
                );
            }
        }
    }
    if should_show {
        apply_controller_visibility(native, true)?;
    }

    let extra = format!(
        "bounds={} visible={} reparented={}",
        format_rect(bounds),
        should_show,
        reparented
    );
    trace_placement("reconcile", native, Some(&extra));
    Ok(())
}

fn current_modifier_flags() -> jint {
    let mut flags = 0;
    unsafe {
        if is_key_down(VK_SHIFT) {
            flags |= MODIFIER_SHIFT;
        }
        if is_key_down(VK_CONTROL) {
            flags |= MODIFIER_CONTROL;
        }
        if is_key_down(VK_MENU) {
            flags |= MODIFIER_ALT;
        }
        if is_key_down(VK_LWIN) || is_key_down(VK_RWIN) {
            flags |= MODIFIER_META;
        }
    }
    flags
}

unsafe fn is_key_down(virtual_key: VIRTUAL_KEY) -> bool {
    (GetKeyState(virtual_key.0 as i32) as u16 & 0x8000) != 0
}

fn jstring_to_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> BridgeResult<String> {
    env.get_string(&value)
        .map(|value| value.into())
        .map_err(format_jni_error)
}

fn call_string_getter(
    env: &mut JNIEnv<'_>,
    object: &JObject<'_>,
    method_name: &str,
) -> BridgeResult<String> {
    let value = env
        .call_method(object, method_name, "()Ljava/lang/String;", &[])
        .map_err(format_jni_error)?
        .l()
        .map_err(format_jni_error)?;
    jstring_to_string(env, JString::from(value))
}

fn asset_response_from_java(
    env: &mut JNIEnv<'_>,
    response: JObject<'_>,
) -> BridgeResult<Option<NativeAssetResponse>> {
    if response.is_null() {
        return Ok(None);
    }
    let status_code = env
        .call_method(&response, "getStatusCode", "()I", &[])
        .map_err(format_jni_error)?
        .i()
        .map_err(format_jni_error)?;
    let status_text = call_string_getter(env, &response, "getStatusText")?;
    let headers = call_string_getter(env, &response, "getHeaders")?;
    let bytes = env
        .call_method(&response, "getBytes", "()[B", &[])
        .map_err(format_jni_error)?
        .l()
        .map_err(format_jni_error)?;
    let bytes = env
        .convert_byte_array(JByteArray::from(bytes))
        .map_err(format_jni_error)?;
    Ok(Some(NativeAssetResponse {
        status_code,
        status_text,
        headers,
        bytes,
    }))
}

fn js_string_literal(value: &str) -> String {
    let mut result = String::with_capacity(value.len() + 2);
    result.push('\'');
    for ch in value.chars() {
        match ch {
            '\\' => result.push_str("\\\\"),
            '\'' => result.push_str("\\'"),
            '"' => result.push_str("\\\""),
            '\n' => result.push_str("\\n"),
            '\r' => result.push_str("\\r"),
            '\t' => result.push_str("\\t"),
            '\u{2028}' => result.push_str("\\u2028"),
            '\u{2029}' => result.push_str("\\u2029"),
            _ => result.push(ch),
        }
    }
    result.push('\'');
    result
}

unsafe fn get_pwstr<F>(read: F) -> Option<String>
where
    F: FnOnce(*mut PWSTR) -> windows::core::Result<()>,
{
    let mut value = PWSTR::null();
    read(&mut value).ok()?;
    if value.is_null() {
        return None;
    }
    Some(take_pwstr(value))
}

unsafe fn get_bool<F>(read: F) -> Option<bool>
where
    F: FnOnce(*mut windows::core::BOOL) -> windows::core::Result<()>,
{
    let mut value = windows::core::BOOL(0);
    read(&mut value).ok()?;
    Some(value.as_bool())
}

unsafe fn get_i32<F>(read: F) -> Option<i32>
where
    F: FnOnce(*mut i32) -> windows::core::Result<()>,
{
    let mut value = 0;
    read(&mut value).ok()?;
    Some(value)
}

unsafe fn get_u32<F>(read: F) -> Option<u32>
where
    F: FnOnce(*mut u32) -> windows::core::Result<()>,
{
    let mut value = 0;
    read(&mut value).ok()?;
    Some(value)
}

unsafe fn get_u64<F>(read: F) -> Option<u64>
where
    F: FnOnce(*mut u64) -> windows::core::Result<()>,
{
    let mut value = 0;
    read(&mut value).ok()?;
    Some(value)
}

unsafe fn get_web_error_status<F>(read: F) -> Option<COREWEBVIEW2_WEB_ERROR_STATUS>
where
    F: FnOnce(*mut COREWEBVIEW2_WEB_ERROR_STATUS) -> windows::core::Result<()>,
{
    let mut value = COREWEBVIEW2_WEB_ERROR_STATUS::default();
    read(&mut value).ok()?;
    Some(value)
}

unsafe fn get_process_failed_kind<F>(read: F) -> Option<COREWEBVIEW2_PROCESS_FAILED_KIND>
where
    F: FnOnce(*mut COREWEBVIEW2_PROCESS_FAILED_KIND) -> windows::core::Result<()>,
{
    let mut value = COREWEBVIEW2_PROCESS_FAILED_KIND::default();
    read(&mut value).ok()?;
    Some(value)
}

unsafe fn get_process_failed_reason<F>(read: F) -> Option<COREWEBVIEW2_PROCESS_FAILED_REASON>
where
    F: FnOnce(*mut COREWEBVIEW2_PROCESS_FAILED_REASON) -> windows::core::Result<()>,
{
    let mut value = COREWEBVIEW2_PROCESS_FAILED_REASON::default();
    read(&mut value).ok()?;
    Some(value)
}

unsafe fn get_browser_exit_kind<F>(read: F) -> Option<COREWEBVIEW2_BROWSER_PROCESS_EXIT_KIND>
where
    F: FnOnce(*mut COREWEBVIEW2_BROWSER_PROCESS_EXIT_KIND) -> windows::core::Result<()>,
{
    let mut value = COREWEBVIEW2_BROWSER_PROCESS_EXIT_KIND::default();
    read(&mut value).ok()?;
    Some(value)
}

unsafe fn get_process_kind<F>(read: F) -> Option<COREWEBVIEW2_PROCESS_KIND>
where
    F: FnOnce(*mut COREWEBVIEW2_PROCESS_KIND) -> windows::core::Result<()>,
{
    let mut value = COREWEBVIEW2_PROCESS_KIND::default();
    read(&mut value).ok()?;
    Some(value)
}

unsafe fn get_permission_kind<F>(read: F) -> Option<COREWEBVIEW2_PERMISSION_KIND>
where
    F: FnOnce(*mut COREWEBVIEW2_PERMISSION_KIND) -> windows::core::Result<()>,
{
    let mut value = COREWEBVIEW2_PERMISSION_KIND::default();
    read(&mut value).ok()?;
    Some(value)
}

fn diagnostic_data(pairs: Vec<(&str, String)>) -> String {
    pairs
        .into_iter()
        .filter(|(_, value)| !value.is_empty())
        .map(|(name, value)| format!("{}={}", name, sanitize_diagnostic_value(&value)))
        .collect::<Vec<_>>()
        .join("\n")
}

fn sanitize_diagnostic_value(value: &str) -> String {
    value.replace('\r', " ").replace('\n', " ")
}

fn process_failed_kind_name(kind: COREWEBVIEW2_PROCESS_FAILED_KIND) -> &'static str {
    match kind {
        COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED => "browser-process-exited",
        COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED => "render-process-exited",
        COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_UNRESPONSIVE => {
            "render-process-unresponsive"
        }
        COREWEBVIEW2_PROCESS_FAILED_KIND_FRAME_RENDER_PROCESS_EXITED => {
            "frame-render-process-exited"
        }
        COREWEBVIEW2_PROCESS_FAILED_KIND_UTILITY_PROCESS_EXITED => "utility-process-exited",
        COREWEBVIEW2_PROCESS_FAILED_KIND_SANDBOX_HELPER_PROCESS_EXITED => {
            "sandbox-helper-process-exited"
        }
        COREWEBVIEW2_PROCESS_FAILED_KIND_GPU_PROCESS_EXITED => "gpu-process-exited",
        COREWEBVIEW2_PROCESS_FAILED_KIND_PPAPI_PLUGIN_PROCESS_EXITED => {
            "ppapi-plugin-process-exited"
        }
        COREWEBVIEW2_PROCESS_FAILED_KIND_PPAPI_BROKER_PROCESS_EXITED => {
            "ppapi-broker-process-exited"
        }
        COREWEBVIEW2_PROCESS_FAILED_KIND_UNKNOWN_PROCESS_EXITED => "unknown-process-exited",
        _ => "unknown",
    }
}

fn process_failed_reason_name(reason: COREWEBVIEW2_PROCESS_FAILED_REASON) -> &'static str {
    match reason {
        COREWEBVIEW2_PROCESS_FAILED_REASON_UNEXPECTED => "unexpected",
        COREWEBVIEW2_PROCESS_FAILED_REASON_UNRESPONSIVE => "unresponsive",
        COREWEBVIEW2_PROCESS_FAILED_REASON_TERMINATED => "terminated",
        COREWEBVIEW2_PROCESS_FAILED_REASON_CRASHED => "crashed",
        COREWEBVIEW2_PROCESS_FAILED_REASON_LAUNCH_FAILED => "launch-failed",
        COREWEBVIEW2_PROCESS_FAILED_REASON_OUT_OF_MEMORY => "out-of-memory",
        COREWEBVIEW2_PROCESS_FAILED_REASON_PROFILE_DELETED => "profile-deleted",
        _ => "unknown",
    }
}

fn browser_exit_kind_name(kind: COREWEBVIEW2_BROWSER_PROCESS_EXIT_KIND) -> &'static str {
    match kind {
        COREWEBVIEW2_BROWSER_PROCESS_EXIT_KIND_NORMAL => "normal",
        COREWEBVIEW2_BROWSER_PROCESS_EXIT_KIND_FAILED => "failed",
        _ => "unknown",
    }
}

fn process_kind_name(kind: COREWEBVIEW2_PROCESS_KIND) -> &'static str {
    match kind {
        COREWEBVIEW2_PROCESS_KIND_BROWSER => "browser",
        COREWEBVIEW2_PROCESS_KIND_RENDERER => "renderer",
        COREWEBVIEW2_PROCESS_KIND_UTILITY => "utility",
        COREWEBVIEW2_PROCESS_KIND_SANDBOX_HELPER => "sandbox-helper",
        COREWEBVIEW2_PROCESS_KIND_GPU => "gpu",
        COREWEBVIEW2_PROCESS_KIND_PPAPI_PLUGIN => "ppapi-plugin",
        COREWEBVIEW2_PROCESS_KIND_PPAPI_BROKER => "ppapi-broker",
        _ => "unknown",
    }
}

fn permission_kind_name(kind: COREWEBVIEW2_PERMISSION_KIND) -> &'static str {
    match kind {
        COREWEBVIEW2_PERMISSION_KIND_UNKNOWN_PERMISSION => "unknown",
        COREWEBVIEW2_PERMISSION_KIND_MICROPHONE => "microphone",
        COREWEBVIEW2_PERMISSION_KIND_CAMERA => "camera",
        COREWEBVIEW2_PERMISSION_KIND_GEOLOCATION => "geolocation",
        COREWEBVIEW2_PERMISSION_KIND_NOTIFICATIONS => "notifications",
        COREWEBVIEW2_PERMISSION_KIND_OTHER_SENSORS => "other-sensors",
        COREWEBVIEW2_PERMISSION_KIND_CLIPBOARD_READ => "clipboard-read",
        COREWEBVIEW2_PERMISSION_KIND_MULTIPLE_AUTOMATIC_DOWNLOADS => "multiple-automatic-downloads",
        COREWEBVIEW2_PERMISSION_KIND_FILE_READ_WRITE => "file-read-write",
        COREWEBVIEW2_PERMISSION_KIND_AUTOPLAY => "autoplay",
        COREWEBVIEW2_PERMISSION_KIND_LOCAL_FONTS => "local-fonts",
        COREWEBVIEW2_PERMISSION_KIND_MIDI_SYSTEM_EXCLUSIVE_MESSAGES => {
            "midi-system-exclusive-messages"
        }
        COREWEBVIEW2_PERMISSION_KIND_WINDOW_MANAGEMENT => "window-management",
        _ => "unknown",
    }
}

fn format_windows_error<E: std::fmt::Debug>(error: E) -> String {
    format!("{error:?}")
}

fn format_jni_error(error: jni::errors::Error) -> String {
    format!("{error:?}")
}

#[cfg(test)]
mod tests {
    use super::*;

    struct TestWindow(HWND);

    impl Drop for TestWindow {
        fn drop(&mut self) {
            unsafe {
                let _ = DestroyWindow(self.0);
            }
        }
    }

    #[test]
    fn presentation_selection_defaults_and_invalid_values_to_root_windowed() {
        assert_eq!(
            parse_presentation_selection(None, None, None),
            PresentationSelection {
                mode: PresentationMode::WindowedRootPoc,
                warning: None,
                forced_clip: None,
                stale_controller_bounds: false,
            }
        );

        let invalid = parse_presentation_selection(Some("composition"), None, Some("1"));
        assert_eq!(invalid.mode, PresentationMode::WindowedRootPoc);
        assert!(invalid
            .warning
            .as_deref()
            .is_some_and(|warning| warning.contains(PRESENTATION_ENV)));
        assert!(!invalid.stale_controller_bounds);
    }

    #[test]
    fn explicit_windowed_selection_keeps_the_legacy_canvas_host() {
        let selection = parse_presentation_selection(Some("windowed"), None, None);
        assert_eq!(selection.mode, PresentationMode::Windowed);
        assert!(!selection.mode.is_root_host());
        assert_eq!(selection.warning, None);
    }

    #[test]
    fn windowed_root_poc_selection_is_the_canvas_free_default() {
        let selection =
            parse_presentation_selection(Some("windowed-root-poc"), Some("200x400"), Some("1"));
        assert_eq!(selection.mode, PresentationMode::WindowedRootPoc);
        assert!(selection.mode.is_root_host());
        assert_eq!(selection.forced_clip, None);
        assert!(!selection.stale_controller_bounds);
        assert_eq!(selection.warning, None);
    }

    #[test]
    fn composition_poc_selection_parses_forced_clip_and_stale_bounds() {
        assert_eq!(
            parse_presentation_selection(Some("composition-poc"), Some("200x400"), Some("1")),
            PresentationSelection {
                mode: PresentationMode::CompositionPoc,
                warning: None,
                forced_clip: Some((200, 400)),
                stale_controller_bounds: true,
            }
        );

        let invalid_clip =
            parse_presentation_selection(Some("composition-poc"), Some("200x0"), None);
        assert_eq!(invalid_clip.mode, PresentationMode::CompositionPoc);
        assert_eq!(invalid_clip.forced_clip, None);
        assert!(invalid_clip
            .warning
            .as_deref()
            .is_some_and(|warning| warning.contains(COMPOSITION_POC_CLIP_ENV)));
    }

    #[test]
    fn composition_root_poc_selection_is_canvas_free_mode() {
        let selection =
            parse_presentation_selection(Some("composition-root-poc"), Some("200x400"), Some("1"));
        assert_eq!(selection.mode, PresentationMode::CompositionRootPoc);
        assert_eq!(selection.forced_clip, None);
        assert!(!selection.stale_controller_bounds);
        assert_eq!(selection.warning, None);
    }

    #[test]
    fn clip_host_tracks_canvas_bounds_parks_and_is_destroyed() {
        let root = TestWindow(unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                w!("STATIC"),
                w!("webview-clip-test-root"),
                WS_OVERLAPPEDWINDOW,
                0,
                0,
                480,
                360,
                None,
                None,
                None,
                None,
            )
            .expect("create clip-host test root")
        });
        unsafe {
            let _ = ShowWindow(root.0, SW_SHOW);
        }
        let canvas = TestWindow(unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                w!("STATIC"),
                w!("webview-clip-test-canvas"),
                WS_CHILD | WS_VISIBLE,
                0,
                0,
                320,
                240,
                Some(root.0),
                None,
                None,
                None,
            )
            .expect("create clip-host test Canvas")
        });

        let (clip_hwnd, initial_bounds) =
            create_clip_host_window(canvas.0).expect("create WebView2 clip host");
        let clip_host = TestWindow(clip_hwnd);
        assert_eq!(initial_bounds.right, 320);
        assert_eq!(initial_bounds.bottom, 240);
        assert_eq!(
            unsafe { GetParent(clip_host.0) }.expect("read clip-host parent"),
            canvas.0,
            "the clip host must start as a direct child of the Canvas"
        );
        assert!(
            unsafe { IsWindowVisible(clip_host.0) }.as_bool(),
            "the clip host stays visible; controller visibility is managed separately"
        );

        let resized = RECT {
            left: 0,
            top: 0,
            right: 123,
            bottom: 77,
        };
        set_clip_host_bounds_window(clip_host.0, resized).expect("resize clip host");
        let mut actual = RECT::default();
        unsafe { GetClientRect(clip_host.0, &mut actual) }.expect("read resized clip host");
        assert_eq!((actual.right, actual.bottom), (123, 77));

        set_clip_host_bounds_window(clip_host.0, RECT::default())
            .expect("shrink clip host to zero");
        let holder = resolve_holder_window(canvas.0).expect("resolve clip-host parking holder");
        set_clip_host_parent_window(clip_host.0, holder).expect("park clip host");
        assert_eq!(
            unsafe { GetParent(clip_host.0) }.expect("read parked clip-host parent"),
            holder,
            "parking moves the bridge-owned HWND instead of the WebView2 controller"
        );
        unsafe { GetClientRect(clip_host.0, &mut actual) }.expect("read parked clip host");
        assert_eq!((actual.right, actual.bottom), (0, 0));

        let destroyed = clip_host.0;
        drop(clip_host);
        assert!(
            !unsafe { IsWindow(Some(destroyed)) }.as_bool(),
            "the bridge-owned clip host must be destroyable without touching the Canvas"
        );
        assert!(
            unsafe { IsWindow(Some(canvas.0)) }.as_bool(),
            "destroying the clip host must leave the AWT-owned Canvas alive"
        );
    }

    /// The owned-window case (a floating dialog parking under its owner) cannot be modeled here -
    /// a synthetic window does not get AWT's ownership - and is covered by
    /// `WebViewRuntimeSmokeTest.facade_survives_move_from_disposed_floating_window`.
    #[test]
    fn holder_is_visible_zero_sized_child_of_root() {
        let root = TestWindow(unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                w!("STATIC"),
                w!("webview-test-root"),
                WS_OVERLAPPEDWINDOW,
                0,
                0,
                320,
                240,
                None,
                None,
                None,
                None,
            )
            .expect("create test root")
        });
        unsafe {
            let _ = ShowWindow(root.0, SW_SHOW);
        }
        let canvas = TestWindow(unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                w!("STATIC"),
                w!("webview-test-canvas"),
                WS_CHILD | WS_VISIBLE,
                0,
                0,
                320,
                240,
                Some(root.0),
                None,
                None,
                None,
            )
            .expect("create test Canvas")
        });

        let first = resolve_holder_window(canvas.0).expect("resolve first holder window");
        let second = resolve_holder_window(canvas.0).expect("resolve repeated holder window");

        assert_eq!(
            first, second,
            "every view under one root shares a single holder window"
        );
        assert_ne!(
            first, root.0,
            "parking must target the holder window, not the root itself"
        );
        assert_eq!(
            unsafe { GetAncestor(first, GA_ROOT) },
            root.0,
            "the holder window must stay under the same top-level window"
        );
        assert!(
            unsafe { IsWindowVisible(first) }.as_bool(),
            "a hidden holder would drop IsWindowVisible of the parked controller and freeze the page"
        );

        let mut rect = RECT::default();
        unsafe { GetClientRect(first, &mut rect) }.expect("read holder client rect");
        assert_eq!(
            (rect.right - rect.left, rect.bottom - rect.top),
            (0, 0),
            "the holder window clips the parked controller away by having an empty client area"
        );

        let mut class_buffer = [0u16; 64];
        let length = unsafe { GetClassNameW(first, &mut class_buffer) }.max(0) as usize;
        assert_eq!(
            String::from_utf16_lossy(&class_buffer[..length]),
            "IJWebView2Holder",
            "the holder must use the bridge window class, which is what makes the park barrier work"
        );
    }
}

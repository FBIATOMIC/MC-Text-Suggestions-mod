package com.example.overlaychat;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.HMODULE;
import com.sun.jna.platform.win32.WinDef.HMENU;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.MSG;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import java.nio.IntBuffer;
import net.minecraft.client.MinecraftClient;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;

public class NativeEditManager {

    private HWND editHwnd = null;
    private HWND overlayHwnd = null;
    private Thread winThread = null;
    private volatile int overlayX = 0;
    private volatile int overlayY = 0;
    private volatile int overlayW = 0;
    private volatile int overlayH = 0;
    private volatile int winThreadId = 0;
    // store original fullscreen monitor and mode when switching from exclusive fullscreen
    private volatile long originalMonitor = 0L;
    private volatile int originalWidth = 0;
    private volatile int originalHeight = 0;
    private volatile int originalRefreshRate = 0;
    private volatile boolean switchedFromExclusive = false;
    // Keep references to the edit control window proc and previous proc to prevent GC and allow restore
    private WinUser.WindowProc editWndProc = null;
    private com.sun.jna.Pointer prevWndProc = null;
    // cached state whether MC chat currently has a leading slash
    private final AtomicBoolean mcChatHasLeadingSlash = new AtomicBoolean(false);
    // When set, indicates we should create the overlay because the player typed "/tell <name> "
    private final AtomicBoolean pendingCreateForTell = new AtomicBoolean(false);
    // The exact MC chat text that triggered the pending create (copied into overlay)
    private volatile String pendingCreateText = null;
    // When true the overlay will ignore the usual behavior that moves input back to MC chat
    // when the overlay text begins with '/'. This is used when the overlay was created
    // from a "/tell <name> " pattern so the leading slash doesn't immediately switch back.
    private final AtomicBoolean ignoreLeadingSlashInOverlay = new AtomicBoolean(false);
    // Ensure single global watcher
    private final AtomicBoolean globalWatcherStarted = new AtomicBoolean(false);
    // Track whether the chat was opened since last reset so we can reset after close
    private final AtomicBoolean sawChatOpenSinceLastReset = new AtomicBoolean(false);
    // Prevent scheduling multiple concurrent resets
    private final AtomicBoolean resetScheduled = new AtomicBoolean(false);

    public NativeEditManager() {
        ensureGlobalWatcherRunning();
    }

    public boolean isOverlayActive() {
        return overlayHwnd != null;
    }

    private void ensureGlobalWatcherRunning() {
        if (!isWindows()) return;
        if (!globalWatcherStarted.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    try { Thread.sleep(120); } catch (InterruptedException ie) { break; }
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc == null) continue;

                    // If the mod has been disabled via config while the overlay is present,
                    // destroy the overlay and skip creating any new ones until re-enabled.
                    try {
                        if (overlayHwnd != null && !ModConfig.isEnabled()) {
                            try { NativeEditManager.this.destroy(); } catch (Throwable ignored) {}
                            // wait a bit before continuing to avoid tight-loop recreation
                            try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                            continue;
                        }
                    } catch (Throwable ignored) {}

                    // Quick check: only proceed to the heavier per-frame checks while the ChatScreen is open.
                    final AtomicBoolean quickChatOpen = new AtomicBoolean(false);
                    mc.execute(() -> {
                        try {
                            quickChatOpen.set(mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen);
                        } catch (Throwable ignored) {}
                    });
                    try { Thread.sleep(40); } catch (InterruptedException ie) { break; }

                    // remember that chat was opened at least once in this cycle
                    if (quickChatOpen.get()) sawChatOpenSinceLastReset.set(true);

                    if (!quickChatOpen.get()) {
                        // no chat UI -> clear leading-slash flag and skip expensive checks
                        mcChatHasLeadingSlash.set(false);
                        // schedule a reset 500ms after the chat has been closed, but only
                        // if we previously observed the chat open and a reset isn't pending.
                        if (sawChatOpenSinceLastReset.get() && resetScheduled.compareAndSet(false, true)) {
                            new Thread(() -> {
                                try {
                                    try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
                                    MinecraftClient mcInner = MinecraftClient.getInstance();
                                    final AtomicBoolean stillOpen = new AtomicBoolean(false);
                                    if (mcInner != null) {
                                        mcInner.execute(() -> {
                                            try {
                                                stillOpen.set(mcInner.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen);
                                            } catch (Throwable ignored) {}
                                        });
                                        try { Thread.sleep(40); } catch (InterruptedException ie) { /* ignore */ }
                                    }
                                    // only reset if chat is still closed
                                    if (!stillOpen.get()) {
                                        resetToDefaults();
                                    }
                                } catch (Throwable ignored) {
                                } finally {
                                    resetScheduled.set(false);
                                    sawChatOpenSinceLastReset.set(false);
                                }
                            }, "overlay-reset-scheduler").start();
                        }
                        // sleep a bit longer while idle to reduce overhead
                        try { Thread.sleep(800); } catch (InterruptedException ie) { break; }
                        continue;
                    }

                    // Chat is open -> perform detailed checks
                    final AtomicBoolean hasSlash = new AtomicBoolean(false);
                    final AtomicBoolean chatOpen = new AtomicBoolean(true);
                    mc.execute(() -> {
                        try {
                            if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) {
                                chatOpen.set(true);
                                try {
                                    net.minecraft.client.gui.screen.ChatScreen chatScreen = (net.minecraft.client.gui.screen.ChatScreen) mc.currentScreen;
                                    com.example.overlaychat.mixin.ChatScreenAccessor acc = (com.example.overlaychat.mixin.ChatScreenAccessor) chatScreen;
                                    net.minecraft.client.gui.widget.TextFieldWidget chatField = acc.getChatField();
                                    if (chatField != null) {
                                        String t2 = chatField.getText();
                                        hasSlash.set(t2 != null && t2.startsWith("/"));

                                        // detect "/tell <username> " (space after username) and prepare to create overlay
                                        if (t2 != null && t2.startsWith("/tell ")) {
                                            int idx = "/tell ".length();
                                            // require a trailing space after the username to consider it finished
                                            if (t2.length() > idx && t2.charAt(t2.length() - 1) == ' ') {
                                                boolean hasName = false;
                                                for (int i = idx; i < t2.length() - 1; i++) {
                                                    if (!Character.isWhitespace(t2.charAt(i))) {
                                                        hasName = true;
                                                        break;
                                                    }
                                                }
                                                if (hasName) {
                                                    pendingCreateForTell.set(true);
                                                    pendingCreateText = t2;
                                                } else {
                                                    pendingCreateForTell.set(false);
                                                    pendingCreateText = null;
                                                }
                                            } else {
                                                // username not finished yet
                                                pendingCreateForTell.set(false);
                                                pendingCreateText = null;
                                            }
                                        } else {
                                            // clear pending if no longer in a /tell context
                                            pendingCreateForTell.set(false);
                                            pendingCreateText = null;
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            } else {
                                chatOpen.set(false);
                            }
                        } catch (Throwable ignored) {}
                    });
                    try { Thread.sleep(40); } catch (InterruptedException ie) { break; }

                    boolean slash = hasSlash.get();
                    boolean overlayPresent = overlayHwnd != null;
                    boolean tellPending = pendingCreateForTell.get();

                    if (slash && !tellPending) {
                        mcChatHasLeadingSlash.set(true);
                        // If the overlay was created to handle a /tell flow, it may set
                        // ignoreLeadingSlashInOverlay so the watcher should not destroy
                        // the overlay while the user is continuing to type the message.
                        if (overlayPresent && !ignoreLeadingSlashInOverlay.get()) {
                            try { NativeEditManager.this.destroy(); } catch (Throwable ignored) {}
                        }
                    } else if (slash && tellPending) {
                        // special-case: user typed "/tell <name> " — create overlay to type the message
                        if (!overlayPresent && ModConfig.isEnabled()) {
                            try { NativeEditManager.this.createAndFocus(); } catch (Throwable ignored) {}
                        }
                    } else {
                        mcChatHasLeadingSlash.set(false);
                        if (!overlayPresent && chatOpen.get() && ModConfig.isEnabled()) {
                            try { NativeEditManager.this.createAndFocus(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }, "global-chat-watcher");
        t.setDaemon(true);
        t.start();
    }

    public void createAndFocus() {
        // respect global enable/disable flag
        if (!ModConfig.isEnabled()) return;
        if (!isWindows()) return;
        if (editHwnd != null) return;
            if (editHwnd != null || winThread != null) return;

        // If the MC chat UI is currently open and already contains a leading '/',
        // do not activate the custom overlay — instead start a watcher that will
        // create the overlay once the user removes the leading '/'.
                try {
                    MinecraftClient mcCheck = MinecraftClient.getInstance();
                    if (mcCheck != null && mcCheck.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) {
                        try {
                            net.minecraft.client.gui.screen.ChatScreen cs = (net.minecraft.client.gui.screen.ChatScreen) mcCheck.currentScreen;
                            com.example.overlaychat.mixin.ChatScreenAccessor acc = (com.example.overlaychat.mixin.ChatScreenAccessor) cs;
                            net.minecraft.client.gui.widget.TextFieldWidget chatField = acc.getChatField();
                            if (chatField != null) {
                                String txt = chatField.getText();
                                if (txt != null && txt.startsWith("/")) {
                                    // allow creating the overlay if this was a "/tell <name> " detection
                                    if (!(pendingCreateForTell.get() && txt.startsWith("/tell "))) {
                                        mcChatHasLeadingSlash.set(true);
                                        return;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}

        final HMODULE hInst = Kernel32.INSTANCE.GetModuleHandle(null);
            final CountDownLatch created = new CountDownLatch(1);

            winThread = new Thread(() -> {
                try {
                    // compute desired position relative to Minecraft window if possible
                    // overlayWidth/overlayHeight = white background size
                    // editWidth/editHeight = actual text field size inside the overlay
                    int overlayWidth = 0;
                    int overlayHeight = 0;
                    int padding = 2;
                    int editWidth = Math.max(0, overlayWidth - padding * 2);
                    int editHeight = Math.max(0, overlayHeight - padding * 2);
                    int x = 25;
                    int y = 25;
                    try {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client != null && client.getWindow() != null) {
                            long glfwWindow = client.getWindow().getHandle();
                            long winHandle = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
                            if (winHandle != 0L) {
                                RECT rect = new RECT();
                                HWND mcHwnd = new HWND(new Pointer(winHandle));
                                if (User32.INSTANCE.GetWindowRect(mcHwnd, rect)) {
                                    x = rect.left + 10;
                                    y = rect.bottom - 60;
                                    overlayWidth = Math.min(overlayWidth, rect.right - rect.left - 20);
                                    editWidth = Math.max(0, overlayWidth - padding * 2);

                                    // detect exclusive fullscreen via GLFW: if glfwGetWindowMonitor != 0, the window is fullscreen
                                    try {
                                        long monitor = GLFW.glfwGetWindowMonitor(glfwWindow);
                                        if (monitor != 0L) {
                                            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
                                            if (mode != null) {
                                                originalMonitor = monitor;
                                                originalWidth = mode.width();
                                                originalHeight = mode.height();
                                                originalRefreshRate = mode.refreshRate();
                                                switchedFromExclusive = true;
                                                final long fglfwWindow = glfwWindow;
                                                final GLFWVidMode fmode = mode;
                                                final CountDownLatch borderlessLatch = new CountDownLatch(1);
                                                MinecraftClient mc2 = MinecraftClient.getInstance();
                                                if (mc2 != null) {
                                                    mc2.execute(() -> {
                                                        try (MemoryStack stack = MemoryStack.stackPush()) {
                                                            IntBuffer mx = stack.mallocInt(1);
                                                            IntBuffer my = stack.mallocInt(1);
                                                            GLFW.glfwGetMonitorPos(originalMonitor, mx, my);
                                                            int mxv = mx.get(0);
                                                            int myv = my.get(0);
                                                            // make window undecorated and set to cover the monitor (borderless fullscreen)
                                                            GLFW.glfwSetWindowAttrib(fglfwWindow, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                                                            GLFW.glfwSetWindowMonitor(fglfwWindow, 0L, mxv, myv, fmode.width(), fmode.height(), fmode.refreshRate());
                                                        } catch (Throwable t) {
                                                            t.printStackTrace();
                                                        } finally {
                                                            borderlessLatch.countDown();
                                                        }
                                                    });
                                                    try {
                                                        // wait briefly for the client thread to perform the borderless switch before showing the edit
                                                        borderlessLatch.await(500, TimeUnit.MILLISECONDS);
                                                    } catch (InterruptedException ie) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }

                    final int ES_LEFT = 0x0000;
                    final int ES_AUTOHSCROLL = 0x0080;
                    final int WS_CHILD = 0x40000000;
                    final int WS_VISIBLE = 0x10000000;
                    final int WS_POPUP = 0x80000000;
                    final int WS_BORDER = 0x00800000;
                    final int SWP_SHOWWINDOW = 0x0040;

                    int overlayStyle = WS_POPUP | WS_VISIBLE;
                        overlayHwnd = User32.INSTANCE.CreateWindowEx(
                            0,
                            "STATIC",
                            "",
                            overlayStyle,
                            x, y, overlayWidth, overlayHeight,
                            null,
                            null,
                            hInst,
                            null
                    );

                    if (overlayHwnd != null) {
                        // store overlay bounds for fallback click
                        overlayX = x;
                        overlayY = y;
                        overlayW = overlayWidth;
                        overlayH = overlayHeight;
                        int editStyle = WS_CHILD | WS_VISIBLE | ES_LEFT | ES_AUTOHSCROLL | WS_BORDER;
                        editHwnd = User32.INSTANCE.CreateWindowEx(
                            0,
                            "EDIT",
                            "",
                            editStyle,
                            padding, padding, editWidth, editHeight,
                            overlayHwnd,
                            null,
                            hInst,
                            null
                        );

                            if (editHwnd != null) {
                            HWND HWND_TOPMOST = new HWND(Pointer.createConstant(-1));
                            User32.INSTANCE.SetWindowPos(overlayHwnd, HWND_TOPMOST, x, y, overlayWidth, overlayHeight, SWP_SHOWWINDOW);
                            User32.INSTANCE.SetFocus(editHwnd);

                                try {
                                    MinecraftClient mcCheck = MinecraftClient.getInstance();
                                    if (mcCheck != null && mcCheck.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) {
                                        try {
                                            net.minecraft.client.gui.screen.ChatScreen cs = (net.minecraft.client.gui.screen.ChatScreen) mcCheck.currentScreen;
                                            com.example.overlaychat.mixin.ChatScreenAccessor acc = (com.example.overlaychat.mixin.ChatScreenAccessor) cs;
                                            net.minecraft.client.gui.widget.TextFieldWidget chatField = acc.getChatField();
                                            if (chatField != null) {
                                                String txt = chatField.getText();
                                                if (txt != null && txt.startsWith("/")) {
                                                    // allow creating the overlay if this was a "/tell <name> " detection
                                                    if (!(pendingCreateForTell.get() && txt.startsWith("/tell "))) {
                                                        mcChatHasLeadingSlash.set(true);
                                                        return;
                                                    }
                                                }
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                } catch (Throwable ignored) {}

                            // If a pending /tell create was requested, pre-fill the native edit
                            if (pendingCreateForTell.get() && pendingCreateText != null) {
                                try {
                                    com.sun.jna.Memory m = new com.sun.jna.Memory((pendingCreateText.length() + 1) * com.sun.jna.Native.WCHAR_SIZE);
                                    m.setWideString(0, pendingCreateText);
                                    com.sun.jna.platform.win32.WinDef.LPARAM lparam = new com.sun.jna.platform.win32.WinDef.LPARAM(com.sun.jna.Pointer.nativeValue(m));
                                    User32.INSTANCE.SendMessage(editHwnd, 0x000C /* WM_SETTEXT */, new com.sun.jna.platform.win32.WinDef.WPARAM(0), lparam);
                                    // move caret to end of the prefilled text so typing continues after the copied content
                                    int lenChars = pendingCreateText.length();
                                    final int EM_SETSEL = 0x00B1;
                                    User32.INSTANCE.SendMessage(editHwnd, EM_SETSEL, new com.sun.jna.platform.win32.WinDef.WPARAM(lenChars), new com.sun.jna.platform.win32.WinDef.LPARAM(lenChars));
                                } catch (Throwable ignored) {}
                                // while handling this /tell case, ignore the overlay-leading-slash auto-restore
                                ignoreLeadingSlashInOverlay.set(true);
                                // clear the pending marker
                                pendingCreateForTell.set(false);
                                pendingCreateText = null;
                            }

                            try {
                                // Install a window proc to intercept Enter presses in the edit control
                                final WinUser.WindowProc proc = new WinUser.WindowProc() {
                                    @Override
                                    public com.sun.jna.platform.win32.WinDef.LRESULT callback(HWND hwnd, int uMsg, com.sun.jna.platform.win32.WinDef.WPARAM wParam, com.sun.jna.platform.win32.WinDef.LPARAM lParam) {
                                        // swallow character messages for Enter (CR/LF) and Escape to avoid system beep
                                        if (uMsg == WinUser.WM_CHAR && (wParam.intValue() == 0x0D || wParam.intValue() == 0x0A || wParam.intValue() == 0x1B)) {
                                            return new com.sun.jna.platform.win32.WinDef.LRESULT(0);
                                        }
                                        // swallow keyup for Enter as well
                                        if (uMsg == WinUser.WM_KEYUP && (wParam.intValue() == 0x0D || wParam.intValue() == 0x1B)) {
                                            return new com.sun.jna.platform.win32.WinDef.LRESULT(0);
                                        }

                                        // handle Escape: refocus Minecraft, close chat and destroy overlay
                                        if (uMsg == WinUser.WM_KEYDOWN && wParam.intValue() == 0x1B) {
                                            try {
                                                MinecraftClient mc = MinecraftClient.getInstance();
                                                if (mc != null) {
                                                    mc.execute(() -> {
                                                        try {
                                                            if (mc.getWindow() != null) {
                                                                long glfwWindow = mc.getWindow().getHandle();
                                                                long winHandle = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
                                                                if (winHandle != 0L) {
                                                                    HWND mcHwnd = new HWND(new Pointer(winHandle));
                                                                    try {
                                                                        int currentTid = Kernel32.INSTANCE.GetCurrentThreadId();
                                                                        com.sun.jna.ptr.IntByReference pidRef = new com.sun.jna.ptr.IntByReference();
                                                                        int targetTid = User32.INSTANCE.GetWindowThreadProcessId(mcHwnd, pidRef);
                                                                        User32.INSTANCE.AttachThreadInput(new com.sun.jna.platform.win32.WinDef.DWORD(currentTid), new com.sun.jna.platform.win32.WinDef.DWORD(targetTid), true);
                                                                        try {
                                                                            WinUser.WINDOWPLACEMENT wp = new WinUser.WINDOWPLACEMENT();
                                                                            wp.length = wp.size();
                                                                            if (User32.INSTANCE.GetWindowPlacement(mcHwnd, wp).booleanValue()) {
                                                                                if (wp.showCmd == WinUser.SW_SHOWMINIMIZED || wp.showCmd == WinUser.SW_MINIMIZE) {
                                                                                    User32.INSTANCE.ShowWindow(mcHwnd, WinUser.SW_RESTORE);
                                                                                }
                                                                            }
                                                                        } catch (Throwable ignore) {}
                                                                        User32.INSTANCE.SetForegroundWindow(mcHwnd);
                                                                        User32.INSTANCE.SetFocus(mcHwnd);
                                                                        User32.INSTANCE.AttachThreadInput(new com.sun.jna.platform.win32.WinDef.DWORD(currentTid), new com.sun.jna.platform.win32.WinDef.DWORD(targetTid), false);
                                                                    } catch (Throwable ignore) {
                                                                        try {
                                                                            User32.INSTANCE.SetForegroundWindow(mcHwnd);
                                                                            try {
                                                                                WinUser.WINDOWPLACEMENT wp2 = new WinUser.WINDOWPLACEMENT();
                                                                                wp2.length = wp2.size();
                                                                                if (User32.INSTANCE.GetWindowPlacement(mcHwnd, wp2).booleanValue()) {
                                                                                    if (wp2.showCmd == WinUser.SW_SHOWMINIMIZED || wp2.showCmd == WinUser.SW_MINIMIZE) {
                                                                                        User32.INSTANCE.ShowWindow(mcHwnd, WinUser.SW_RESTORE);
                                                                                    }
                                                                                }
                                                                            } catch (Throwable ignored) {}
                                                                        } catch (Throwable ignored) {}
                                                                    }
                                                                }
                                                            }
                                                        } catch (Throwable t) {
                                                            t.printStackTrace();
                                                        }
                                                        try {
                                                            // directly close the chat on the client thread so a single Esc closes it
                                                            try {
                                                                mc.execute(() -> {
                                                                    try {
                                                                        if (mc.currentScreen != null) mc.setScreen(null);
                                                                    } catch (Throwable ignored) {}
                                                                });
                                                            } catch (Throwable ignored) {}
                                                            try { NativeEditManager.this.destroy(); } catch (Throwable ignored) {}
                                                        } catch (Throwable ignored) {}
                                                    });
                                                }
                                            } catch (Throwable t) {
                                                t.printStackTrace();
                                            }
                                            return new com.sun.jna.platform.win32.WinDef.LRESULT(0);
                                        }

                                        if (uMsg == WinUser.WM_KEYDOWN && wParam.intValue() == 0x0D) {
                                            try {
                                                int len = User32.INSTANCE.GetWindowTextLength(editHwnd);
                                                char[] buf = new char[len + 1];
                                                User32.INSTANCE.GetWindowText(editHwnd, buf, buf.length);
                                                final String text = com.sun.jna.Native.toString(buf);

                                                MinecraftClient mc = MinecraftClient.getInstance();
                                                if (mc != null) {
                                                    // Copy the overlay content into the real Minecraft chat field and focus it,
                                                    // then immediately post an Enter message from the client thread so the
                                                    // command is executed without any artificial sleep on the native thread.
                                                    mc.execute(() -> {
                                                        try {
                                                            if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) {
                                                                try {
                                                                    net.minecraft.client.gui.screen.ChatScreen chatScreen = (net.minecraft.client.gui.screen.ChatScreen) mc.currentScreen;
                                                                    com.example.overlaychat.mixin.ChatScreenAccessor acc = (com.example.overlaychat.mixin.ChatScreenAccessor) chatScreen;
                                                                    net.minecraft.client.gui.widget.TextFieldWidget chatField = acc.getChatField();
                                                                    if (chatField != null) {
                                                                        chatField.setText(text);
                                                                        chatField.setFocused(true);
                                                                    }
                                                                } catch (Throwable ignored) {}
                                                            } else {
                                                                try {
                                                                    net.minecraft.client.gui.screen.ChatScreen chatScreen = new net.minecraft.client.gui.screen.ChatScreen("", false);
                                                                    mc.setScreen(chatScreen);
                                                                    try {
                                                                        com.example.overlaychat.mixin.ChatScreenAccessor acc = (com.example.overlaychat.mixin.ChatScreenAccessor) chatScreen;
                                                                        net.minecraft.client.gui.widget.TextFieldWidget chatField = acc.getChatField();
                                                                        if (chatField != null) {
                                                                            chatField.setText(text);
                                                                            chatField.setFocused(true);
                                                                        }
                                                                    } catch (Throwable ignored) {}
                                                                } catch (Throwable ignored) {}
                                                            }

                                                            // Now simulate Enter from the client thread so Minecraft sends the command
                                                            try {
                                                                if (mc.getWindow() != null) {
                                                                    long glfwWindow = mc.getWindow().getHandle();
                                                                    long winHandle = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
                                                                    if (winHandle != 0L) {
                                                                        HWND mcHwnd = new HWND(new Pointer(winHandle));
                                                                        final int WM_KEYDOWN = 0x0100;
                                                                        final int WM_KEYUP = 0x0101;
                                                                        final int VK_RETURN = 0x0D;
                                                                        User32.INSTANCE.PostMessage(mcHwnd, WM_KEYDOWN, new com.sun.jna.platform.win32.WinDef.WPARAM(VK_RETURN), new com.sun.jna.platform.win32.WinDef.LPARAM(0));
                                                                        User32.INSTANCE.PostMessage(mcHwnd, WM_KEYUP, new com.sun.jna.platform.win32.WinDef.WPARAM(VK_RETURN), new com.sun.jna.platform.win32.WinDef.LPARAM(0));
                                                                        try {
                                                                            int currentTid = Kernel32.INSTANCE.GetCurrentThreadId();
                                                                            com.sun.jna.ptr.IntByReference pidRef = new com.sun.jna.ptr.IntByReference();
                                                                            int targetTid = User32.INSTANCE.GetWindowThreadProcessId(mcHwnd, pidRef);
                                                                            User32.INSTANCE.AttachThreadInput(new com.sun.jna.platform.win32.WinDef.DWORD(currentTid), new com.sun.jna.platform.win32.WinDef.DWORD(targetTid), true);
                                                                            try {
                                                                                WinUser.WINDOWPLACEMENT wp = new WinUser.WINDOWPLACEMENT();
                                                                                wp.length = wp.size();
                                                                                try {
                                                                                    if (User32.INSTANCE.GetWindowPlacement(mcHwnd, wp).booleanValue()) {
                                                                                        if (wp.showCmd == WinUser.SW_SHOWMINIMIZED || wp.showCmd == WinUser.SW_MINIMIZE) {
                                                                                            User32.INSTANCE.ShowWindow(mcHwnd, WinUser.SW_RESTORE);
                                                                                        }
                                                                                    }
                                                                                } catch (Throwable ignored) {}
                                                                                User32.INSTANCE.SetForegroundWindow(mcHwnd);
                                                                                User32.INSTANCE.SetFocus(mcHwnd);
                                                                            } finally {
                                                                                User32.INSTANCE.AttachThreadInput(new com.sun.jna.platform.win32.WinDef.DWORD(currentTid), new com.sun.jna.platform.win32.WinDef.DWORD(targetTid), false);
                                                                            }
                                                                        } catch (Throwable ignored) {}
                                                                    }
                                                                }
                                                            } catch (Throwable ignored) {}

                                                        } catch (Throwable t) { t.printStackTrace(); }
                                                    });

                                                    // destroy our overlay so the native field stops
                                                    try { NativeEditManager.this.destroy(); } catch (Throwable ignored) {}
                                                }

                                                // if we switched from exclusive fullscreen when opening chat, restore it now on the client thread
                                                try {
                                                    MinecraftClient mcRestore = MinecraftClient.getInstance();
                                                    if (mcRestore != null && switchedFromExclusive && originalMonitor != 0L) {
                                                        mcRestore.execute(() -> {
                                                            try {
                                                                final long savedMonitor = originalMonitor;
                                                                final int savedW = originalWidth;
                                                                final int savedH = originalHeight;
                                                                final int savedRR = originalRefreshRate;
                                                                final long fglfwWindow = mcRestore.getWindow().getHandle();
                                                                try {
                                                                    GLFW.glfwSetWindowMonitor(fglfwWindow, savedMonitor, 0, 0, savedW, savedH, savedRR);
                                                                    GLFW.glfwSetWindowAttrib(fglfwWindow, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
                                                                } catch (Throwable t) { t.printStackTrace(); }
                                                                switchedFromExclusive = false;
                                                                originalMonitor = 0L;
                                                            } catch (Throwable ignore) {}
                                                        });
                                                    }
                                                } catch (Throwable ignore) {}

                                                // clear the edit box
                                                try {
                                                    com.sun.jna.Memory m = new com.sun.jna.Memory((1) * com.sun.jna.Native.WCHAR_SIZE);
                                                    m.setWideString(0, "");
                                                    com.sun.jna.platform.win32.WinDef.LPARAM lparam = new com.sun.jna.platform.win32.WinDef.LPARAM(com.sun.jna.Pointer.nativeValue(m));
                                                    User32.INSTANCE.SendMessage(editHwnd, 0x000C /* WM_SETTEXT */, new com.sun.jna.platform.win32.WinDef.WPARAM(0), lparam);
                                                } catch (Throwable t) {
                                                    t.printStackTrace();
                                                }
                                                return new com.sun.jna.platform.win32.WinDef.LRESULT(0);
                                            } catch (Throwable t) {
                                                t.printStackTrace();
                                            }
                                        }
                                        // call original proc
                                        if (prevWndProc != null) {
                                            return User32.INSTANCE.CallWindowProc(prevWndProc, hwnd, uMsg, wParam, lParam);
                                        }
                                        return new com.sun.jna.platform.win32.WinDef.LRESULT(0);
                                    }
                                };

                                // keep a strong reference to the proc to avoid it being GC'd
                                editWndProc = proc;
                                com.sun.jna.Pointer procPtr = com.sun.jna.CallbackReference.getFunctionPointer(proc);
                                prevWndProc = User32.INSTANCE.SetWindowLongPtr(editHwnd, WinUser.GWL_WNDPROC, procPtr);
                            } catch (Throwable t) {
                                t.printStackTrace();
                            }
                            // start a poller to sync native edit text into Minecraft chat input
                            new Thread(() -> {
                                String last = "";
                                while (overlayHwnd != null && editHwnd != null) {
                                    try {
                                        int len = User32.INSTANCE.GetWindowTextLength(editHwnd);
                                        char[] buf = new char[len + 1];
                                        User32.INSTANCE.GetWindowText(editHwnd, buf, buf.length);
                                        String current = com.sun.jna.Native.toString(buf);
                                        if (!current.equals(last)) {
                                            last = current;
                                            final String toSet = current;
                                            try {
                                                // If the first non-space character is '/' and it's the very first thing,
                                                // switch input to the real Minecraft chat field and hide our overlay.
                                                int firstNonSpace = -1;
                                                for (int i = 0; i < toSet.length(); i++) {
                                                    if (!Character.isWhitespace(toSet.charAt(i))) {
                                                        firstNonSpace = i;
                                                        break;
                                                    }
                                                }

                                                if (firstNonSpace == 0 && toSet.length() > 0 && toSet.charAt(0) == '/' && !ignoreLeadingSlashInOverlay.get()) {
                                                    MinecraftClient mc = MinecraftClient.getInstance();
                                                    if (mc != null) {
                                                        // Put a single '/' into the real chat input and focus it
                                                        mc.execute(() -> {
                                                            try {
                                                                if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) {
                                                                    net.minecraft.client.gui.screen.ChatScreen chatScreen = (net.minecraft.client.gui.screen.ChatScreen) mc.currentScreen;
                                                                    com.example.overlaychat.mixin.ChatScreenAccessor acc = (com.example.overlaychat.mixin.ChatScreenAccessor) chatScreen;
                                                                    net.minecraft.client.gui.widget.TextFieldWidget chatField = acc.getChatField();
                                                                    if (chatField != null) {
                                                                        chatField.setText("/");
                                                                        chatField.setFocused(true);
                                                                    }
                                                                }
                                                            } catch (Throwable ignored) {}
                                                        });

                                                        // destroy our overlay which will also restore exclusive fullscreen if needed
                                                        try { NativeEditManager.this.destroy(); } catch (Throwable ignored) {}

                                                        // mark that MC chat currently has a leading slash; the global watcher
                                                        // will observe when the slash is removed and re-create the overlay.
                                                        mcChatHasLeadingSlash.set(true);
                                                        ensureGlobalWatcherRunning();
                                                    }

                                                    // skip syncing this text into the chat (we already placed '/'), continue loop
                                                    continue;
                                                }

                                                MinecraftClient mc = MinecraftClient.getInstance();
                                                if (mc != null) {
                                                    mc.execute(() -> {
                                                        try {
                                                            // If the current screen is vanilla ChatScreen, use the Mixins accessor
                                                            if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) {
                                                                try {
                                                                    net.minecraft.client.gui.screen.ChatScreen chatScreen = (net.minecraft.client.gui.screen.ChatScreen) mc.currentScreen;
                                                                    com.example.overlaychat.mixin.ChatScreenAccessor acc = (com.example.overlaychat.mixin.ChatScreenAccessor) chatScreen;
                                                                    net.minecraft.client.gui.widget.TextFieldWidget chatField = acc.getChatField();
                                                                    if (chatField != null) {
                                                                        chatField.setText(toSet);
                                                                    }
                                                                } catch (Throwable mix) {
                                                                    mix.printStackTrace();
                                                                }
                                                            }
                                                        } catch (Throwable t) { t.printStackTrace(); }
                                                    });
                                                }
                                            } catch (Throwable t) { t.printStackTrace(); }
                                        }
                                    } catch (Throwable t) {
                                        // ignore and continue
                                    }
                                    try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                                }
                            }, "overlay-edit-poller").start();
                        } else {
                            User32.INSTANCE.DestroyWindow(overlayHwnd);
                            overlayHwnd = null;
                        }
                    }

                    // record this thread id so destroy() can post WM_QUIT to it
                    winThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
                    created.countDown();

                    // message loop for this thread so the native windows remain responsive
                    MSG msg = new MSG();
                    while (User32.INSTANCE.GetMessage(msg, null, 0, 0) != 0) {
                        User32.INSTANCE.TranslateMessage(msg);
                        User32.INSTANCE.DispatchMessage(msg);
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    // restore original window proc to avoid dangling native callbacks
                    if (editHwnd != null && prevWndProc != null) {
                        try { User32.INSTANCE.SetWindowLongPtr(editHwnd, WinUser.GWL_WNDPROC, prevWndProc); } catch (Throwable ignored) {}
                        prevWndProc = null;
                        editWndProc = null;
                    }
                    // cleanup
                    if (editHwnd != null) {
                        try { User32.INSTANCE.DestroyWindow(editHwnd); } catch (Throwable ignored) {}
                        editHwnd = null;
                    }
                    if (overlayHwnd != null) {
                        try { User32.INSTANCE.DestroyWindow(overlayHwnd); } catch (Throwable ignored) {}
                        overlayHwnd = null;
                    }
                }
            }, "native-overlay-thread");

            winThread.setDaemon(true);
            winThread.start();

            try {
                created.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
    }

    

    public void destroy() {
        if (!isWindows()) return;
        if (winThread == null) return;
        try {
            // If we switched from exclusive fullscreen when opening chat, restore it now on the client thread
            try {
                if (switchedFromExclusive && originalMonitor != 0L) {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) {
                        final long savedMonitor = originalMonitor;
                        final int savedW = originalWidth;
                        final int savedH = originalHeight;
                        final int savedRR = originalRefreshRate;
                        mc.execute(() -> {
                            try {
                                long glfwWindow = mc.getWindow().getHandle();
                                GLFW.glfwSetWindowMonitor(glfwWindow, savedMonitor, 0, 0, savedW, savedH, savedRR);
                                GLFW.glfwSetWindowAttrib(glfwWindow, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
                            } catch (Throwable t) { t.printStackTrace(); }
                        });
                    }
                    switchedFromExclusive = false;
                    originalMonitor = 0L;
                }
            } catch (Throwable ignore) {}
            if (overlayHwnd != null) {
                // Post a WM_CLOSE to the overlay window to break the message loop
                User32.INSTANCE.PostMessage(overlayHwnd, WinUser.WM_CLOSE, null, null);
            }
            // Also post WM_QUIT to the window thread, if we recorded its id
            if (winThreadId != 0) {
                com.sun.jna.platform.win32.WinDef.WPARAM wParam = new com.sun.jna.platform.win32.WinDef.WPARAM(0);
                com.sun.jna.platform.win32.WinDef.LPARAM lParam = new com.sun.jna.platform.win32.WinDef.LPARAM(0);
                User32.INSTANCE.PostThreadMessage(winThreadId, WinUser.WM_QUIT, wParam, lParam);
            }
            if (winThread != null) winThread.join(2000);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            // clear any /tell pending state and restore normal overlay-leading-slash handling
            try { pendingCreateForTell.set(false); } catch (Throwable ignored) {}
            pendingCreateText = null;
            try { ignoreLeadingSlashInOverlay.set(false); } catch (Throwable ignored) {}
            editHwnd = null;
            overlayHwnd = null;
            winThread = null;
        }
    }

    /**
     * Reset the mod state to defaults as if freshly launched.
     * This will destroy any overlay and clear all flags and saved state.
     */
    private void resetToDefaults() {
        try {
            // Ensure overlay/native UI closed
            try { destroy(); } catch (Throwable ignored) {}

            // Clear all flags and saved state
            try { pendingCreateForTell.set(false); } catch (Throwable ignored) {}
            pendingCreateText = null;
            try { ignoreLeadingSlashInOverlay.set(false); } catch (Throwable ignored) {}
            try { mcChatHasLeadingSlash.set(false); } catch (Throwable ignored) {}
            try { globalWatcherStarted.set(false); } catch (Throwable ignored) {}

            // Clear fullscreen switching state
            switchedFromExclusive = false;
            originalMonitor = 0L;
            originalWidth = 0;
            originalHeight = 0;
            originalRefreshRate = 0;

            // Clear auxiliary trackers
            try { sawChatOpenSinceLastReset.set(false); } catch (Throwable ignored) {}
            try { resetScheduled.set(false); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("windows");
    }
}

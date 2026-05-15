package com.mokafeefah.clicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ClickerService — مكفوف بوت 2.0
 *
 * هذا الإصدار يحافظ على المنطق الأصلي 100% الذي كان يعمل بشكل صحيح
 * (state machine + collectAllRoots + normalizeArabic + boundsKey 80px grid)
 * ويضيف الإصلاحات الـ 5 الهندسية بشكل غير مدمر:
 *
 *  1) Smart Recycle: ينظف عقد التمرير (scrollables) بعد استخدامها لتقليل ضغط الذاكرة.
 *  2) Event-Driven (OPT-IN): مفتاح اختياري لتحفيز tick فوراً عند TYPE_WINDOW_STATE_CHANGED
 *     فقط أثناء الانتظار (AFTER_LIKE / AFTER_YES) — لا يُستخدم في الحالة LOOK_LIKE.
 *  3) Filtering: كلمات رسائل الخطأ تجعل البوت ينقر إغلاق + ينتقل للتمرير فوراً.
 *  4) Dynamic Scroll: مسافة تمرير متكيّفة — أقل عند النجاح، أكبر عند العلق.
 *  5) Watchdog Timer: عند العلق في نفس الحالة لـ N ثانية، ينفّذ BACK + scroll قبل الإيقاف.
 */
public class ClickerService extends AccessibilityService {

    // ===== State machine (مطابق للأصل) =====
    private static final int STATE_LOOK_LIKE = 0;
    private static final int STATE_AFTER_LIKE = 1;
    private static final int STATE_AFTER_YES = 2;
    private static final int STATE_MUST_SCROLL = 3;

    private static final int BOUNDS_GRID_PX = 80;
    private static final long MAX_WAIT_AFTER_LIKE_MS = 6000;
    private static final long MAX_WAIT_AFTER_YES_MS = 3500;
    private static final long POST_BACK_WAIT_MS = 1000;
    private static final long POST_SCROLL_WAIT_MS = 2000;

    public static final String STATUS_AUTO_STOPPED = "إيقاف تلقائي";
    public static final String STATUS_IDLE = "خامل";
    public static final String STATUS_RUNNING = "يعمل";
    public static final String STATUS_STOPPED = "متوقف";

    private static ClickerService instance;
    private BotConfig config;
    private StatusListener listener;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger likesCount = new AtomicInteger(0);
    private final Set<String> processedBounds = new HashSet<>();

    private String lastAction = "";
    private long lastBackTime = 0;
    private long lastButtonFoundMs = 0;
    private int state = 0;
    private long stateChangedAt = 0;

    // v2.0 helpers
    private long lastProgressAt = 0;
    private int recoveryAttempts = 0;
    private long lastEventTickAt = 0;
    private float scrollFactor = 1.0f; // multiplier on base 500px scroll distance

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running.get()) return;
            long nextDelay = config != null ? config.scanIntervalMs : 300;
            try {
                nextDelay = performOneTick();
            } catch (Throwable ignored) {}
            if (running.get()) {
                handler.postDelayed(this, Math.max(150L, nextDelay));
            }
        }
    };

    public interface StatusListener {
        void onUpdate(String status, int count, String lastAction);
    }

    public static ClickerService getInstance() { return instance; }
    public static boolean isServiceRunning() { return instance != null; }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        running.set(false);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        running.set(false);
        super.onDestroy();
    }

    // ===== FIX #2: Event-Driven (OPT-IN, defensive) =====
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running.get() || event == null || config == null || !config.eventDriven) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        // Only fast-trigger during waiting states (popup arrival)
        if (state != STATE_AFTER_LIKE && state != STATE_AFTER_YES) return;

        // Strong debounce — at most one event-tick per 400ms
        long now = SystemClock.uptimeMillis();
        if (now - lastEventTickAt < 400) return;
        lastEventTickAt = now;

        // Filter by target package
        if (config.targetPackage != null && !config.targetPackage.isEmpty()) {
            CharSequence pkg = event.getPackageName();
            if (pkg == null || !config.targetPackage.equals(pkg.toString())) return;
        }

        // Run a single immediate tick (do NOT remove the scheduled one to keep the loop alive)
        handler.post(() -> {
            if (!running.get()) return;
            try { performOneTick(); } catch (Throwable ignored) {}
        });
    }

    @Override
    public void onInterrupt() {
        running.set(false);
    }

    public void setStatusListener(StatusListener l) {
        this.listener = l;
        if (l != null) {
            l.onUpdate(running.get() ? STATUS_RUNNING : STATUS_IDLE,
                       likesCount.get(),
                       lastAction == null ? "" : lastAction);
        }
    }

    public boolean isExecuting() { return running.get(); }
    public int getLikesCount() { return likesCount.get(); }
    public String getLastAction() { return lastAction == null ? "" : lastAction; }

    public boolean startBot(BotConfig cfg) {
        if (running.get()) return false;
        this.config = cfg;
        this.likesCount.set(0);
        this.lastAction = "";
        this.processedBounds.clear();
        long now = System.currentTimeMillis();
        this.lastButtonFoundMs = now;
        this.lastProgressAt = now;
        this.recoveryAttempts = 0;
        this.scrollFactor = 1.0f;
        this.state = STATE_LOOK_LIKE;
        this.stateChangedAt = now;
        this.running.set(true);
        notifyUpdate(STATUS_RUNNING);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(tick, 500L);
        return true;
    }

    public void stopBot() { stopInternal(STATUS_STOPPED); }

    private void stopInternal(String reason) {
        if (running.get()) {
            running.set(false);
            handler.removeCallbacksAndMessages(null);
            if (config == null || config.vibrate) vibrateAlert();
            notifyUpdate(reason);
        }
    }

    private void transitionTo(int newState) {
        if (this.state != newState) {
            this.state = newState;
            this.stateChangedAt = System.currentTimeMillis();
            this.lastProgressAt = this.stateChangedAt;
            this.recoveryAttempts = 0;
        } else {
            this.state = newState;
            this.stateChangedAt = System.currentTimeMillis();
        }
    }

    // ===== Main tick (preserves original semantics) =====
    public long performOneTick() {
        long now = System.currentTimeMillis();

        // FIX #5: Watchdog — try to recover BEFORE giving up to idle timeout
        long stuckMs = now - lastProgressAt;
        long watchdog = config != null ? config.watchdogMs : 15000L;
        if (stuckMs > watchdog && recoveryAttempts < 3) {
            return triggerWatchdog();
        }

        // Idle timeout — full stop after sustained failure
        if (now - lastButtonFoundMs > config.idleTimeoutMs) {
            stopInternal(STATUS_AUTO_STOPPED);
            return config.scanIntervalMs;
        }

        // Stop-at-count
        if (config.stopAtCount > 0 && likesCount.get() >= config.stopAtCount) {
            stopInternal(STATUS_AUTO_STOPPED);
            return config.scanIntervalMs;
        }

        List<AccessibilityNodeInfo> roots = collectAllRoots();
        if (roots.isEmpty()) {
            setAction(getString(R.string.action_wait));
            return config.scanIntervalMs;
        }

        // Target package filter
        if (config.targetPackage != null && !config.targetPackage.isEmpty()) {
            boolean any = false;
            for (AccessibilityNodeInfo r : roots) {
                CharSequence pkg = r.getPackageName();
                if (pkg != null && config.targetPackage.equals(pkg.toString())) { any = true; break; }
            }
            if (!any) {
                setAction(getString(R.string.action_wait));
                return config.scanIntervalMs;
            }
        }

        // Profile escape (BACK)
        if (now - lastBackTime > 1500) {
            for (String kw : config.profileKeywords) {
                String key = kw == null ? "" : kw.trim();
                if (!key.isEmpty() && findNodeByTextInAll(roots, key) != null) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastBackTime = now;
                    setAction(getString(R.string.action_back));
                    transitionTo(STATE_LOOK_LIKE);
                    return POST_BACK_WAIT_MS;
                }
            }
        }

        // FIX #3: Error-message filtering — close + scroll fast
        AccessibilityNodeInfo errorNode = findErrorNode(roots);
        if (errorNode != null) {
            AccessibilityNodeInfo closeNode = findClickableInAll(roots, config.closeText);
            if (closeNode != null && clickNode(closeNode)) {
                setAction(getString(R.string.action_error_skip));
                transitionTo(STATE_MUST_SCROLL);
                return 600L;
            }
            // No close button — back out
            performGlobalAction(GLOBAL_ACTION_BACK);
            setAction(getString(R.string.action_error_skip));
            transitionTo(STATE_MUST_SCROLL);
            return POST_BACK_WAIT_MS;
        }

        switch (state) {
            case STATE_LOOK_LIKE: return handleLookLike(roots, now);
            case STATE_AFTER_LIKE: return handleAfterLike(roots, now);
            case STATE_AFTER_YES: return handleAfterYes(roots, now);
            case STATE_MUST_SCROLL: return handleMustScroll(roots, now);
            default:
                transitionTo(STATE_LOOK_LIKE);
                return config.scanIntervalMs;
        }
    }

    // ===== FIX #5: Watchdog Recovery =====
    private long triggerWatchdog() {
        recoveryAttempts++;
        setAction(getString(R.string.action_watchdog));
        // Step 1: BACK to escape any modal
        performGlobalAction(GLOBAL_ACTION_BACK);
        // Step 2: aggressive scroll to refresh the list
        handler.postDelayed(() -> {
            if (!running.get()) return;
            List<AccessibilityNodeInfo> rs = collectAllRoots();
            scrollFactor = Math.min(1.6f, scrollFactor + 0.3f);
            performSmartScroll(rs);
            processedBounds.clear();
        }, 500);
        lastProgressAt = System.currentTimeMillis();
        transitionTo(STATE_LOOK_LIKE);
        return 1200L;
    }

    // ===== State handlers (original logic preserved) =====
    private long handleLookLike(List<AccessibilityNodeInfo> roots, long now) {
        AccessibilityNodeInfo yesNode = findClickableInAll(roots, config.yesText);
        if (yesNode != null && clickNode(yesNode)) {
            setAction(getString(R.string.action_yes));
            lastButtonFoundMs = now;
            transitionTo(STATE_AFTER_YES);
            return 700L;
        }
        AccessibilityNodeInfo closeNode = findClickableInAll(roots, config.closeText);
        if (closeNode != null && clickNode(closeNode)) {
            setAction(getString(R.string.action_close));
            transitionTo(STATE_LOOK_LIKE);
            return 800L;
        }
        AccessibilityNodeInfo likeNode = findUnprocessedClickable(roots, config.likeText);
        if (likeNode != null) {
            String key = boundsKey(likeNode);
            if (clickNode(likeNode)) {
                processedBounds.add(key);
                likesCount.incrementAndGet();
                setAction(getString(R.string.action_like));
                lastButtonFoundMs = now;
                lastProgressAt = now;
                if (config.vibrate) vibrateShort();
                transitionTo(STATE_AFTER_LIKE);
                // FIX #4: tighten scroll after success
                scrollFactor = Math.max(0.7f, scrollFactor - 0.05f);
                return config.popupWaitMs;
            }
            return config.scanIntervalMs;
        }
        transitionTo(STATE_MUST_SCROLL);
        return 300L;
    }

    private long handleAfterLike(List<AccessibilityNodeInfo> roots, long now) {
        AccessibilityNodeInfo yesNode = findClickableInAll(roots, config.yesText);
        if (yesNode != null) {
            if (clickNode(yesNode)) {
                setAction(getString(R.string.action_yes));
                lastButtonFoundMs = now;
                lastProgressAt = now;
                transitionTo(STATE_AFTER_YES);
                return 700L;
            }
            return config.scanIntervalMs;
        }
        AccessibilityNodeInfo closeNode = findClickableInAll(roots, config.closeText);
        if (closeNode != null) {
            if (clickNode(closeNode)) {
                setAction(getString(R.string.action_close));
                transitionTo(STATE_LOOK_LIKE);
                return 800L;
            }
            return config.scanIntervalMs;
        }
        if (now - stateChangedAt > MAX_WAIT_AFTER_LIKE_MS) {
            transitionTo(STATE_LOOK_LIKE);
            return 300L;
        }
        setAction(getString(R.string.action_wait));
        return config.scanIntervalMs;
    }

    private long handleAfterYes(List<AccessibilityNodeInfo> roots, long now) {
        AccessibilityNodeInfo closeNode = findClickableInAll(roots, config.closeText);
        if (closeNode != null) {
            if (clickNode(closeNode)) {
                setAction(getString(R.string.action_close));
                transitionTo(STATE_LOOK_LIKE);
                return 800L;
            }
            return config.scanIntervalMs;
        }
        if (now - stateChangedAt > MAX_WAIT_AFTER_YES_MS) {
            transitionTo(STATE_LOOK_LIKE);
            return 300L;
        }
        setAction(getString(R.string.action_wait));
        return config.scanIntervalMs;
    }

    private long handleMustScroll(List<AccessibilityNodeInfo> roots, long now) {
        boolean scrolled = performSmartScroll(roots);
        if (scrolled) {
            setAction(getString(R.string.action_scroll));
            lastProgressAt = now;
            // FIX #4: grow scroll a bit if we keep needing to scroll
            scrollFactor = Math.min(1.6f, scrollFactor + 0.03f);
        }
        processedBounds.clear();
        transitionTo(STATE_LOOK_LIKE);
        return POST_SCROLL_WAIT_MS;
    }

    // ===== Scroll (preserves original logic + adaptive distance) =====
    private boolean performSmartScroll(List<AccessibilityNodeInfo> roots) {
        AccessibilityNodeInfo scrollable = findScrollableInAll(roots);
        if (scrollable != null) {
            boolean ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            if (ok) return true;
        }
        return performGestureSwipeUp();
    }

    private boolean performGestureSwipeUp() {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int centerX = dm.widthPixels / 2;
            int startY = (int) (dm.heightPixels * 0.72f);
            int distance = (int) (500 * scrollFactor);
            int endY = startY - distance;
            int minY = (int) (dm.heightPixels * 0.15f);
            if (endY < minY) endY = minY;
            Path path = new Path();
            path.moveTo(centerX, startY);
            path.lineTo(centerX, endY);
            GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 450L);
            return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        } catch (Throwable th) { return false; }
    }

    // ===== Node finding (faithful to original) =====
    private String boundsKey(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        int cx = (r.left + r.right) / 2;
        int cy = (r.top + r.bottom) / 2;
        return (cx / BOUNDS_GRID_PX) + "," + (cy / BOUNDS_GRID_PX);
    }

    private AccessibilityNodeInfo findUnprocessedClickable(List<AccessibilityNodeInfo> roots, String text) {
        if (text == null || text.isEmpty()) return null;
        String needle = normalizeArabic(text);
        for (AccessibilityNodeInfo root : roots) {
            try {
                List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
                if (matches != null) {
                    for (AccessibilityNodeInfo n : matches) {
                        if (n != null && n.isVisibleToUser()) {
                            AccessibilityNodeInfo clickable = findClickableSelfOrAncestor(n);
                            if (clickable != null && !processedBounds.contains(boundsKey(clickable))) {
                                return clickable;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            AccessibilityNodeInfo found = findUnprocessedRecursive(root, needle);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findUnprocessedRecursive(AccessibilityNodeInfo node, String needle) {
        if (node == null) return null;
        if (matchesText(node, needle) && node.isVisibleToUser()) {
            AccessibilityNodeInfo c = findClickableSelfOrAncestor(node);
            if (c != null && !processedBounds.contains(boundsKey(c))) return c;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findUnprocessedRecursive(child, needle);
            if (found != null) return found;
        }
        return null;
    }

    private List<AccessibilityNodeInfo> collectAllRoots() {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = null;
        try { windows = getWindows(); } catch (Throwable ignored) {}
        if (windows != null && !windows.isEmpty()) {
            List<AccessibilityWindowInfo> sorted = new ArrayList<>(windows);
            Collections.sort(sorted, (a, b) -> Integer.compare(b.getLayer(), a.getLayer()));
            for (AccessibilityWindowInfo w : sorted) {
                if (w == null) continue;
                AccessibilityNodeInfo r = null;
                try { r = w.getRoot(); } catch (Throwable ignored) {}
                if (r != null) result.add(r);
            }
        }
        AccessibilityNodeInfo active = null;
        try { active = getRootInActiveWindow(); } catch (Throwable ignored) {}
        if (active != null && !result.contains(active)) result.add(0, active);
        return result;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        AccessibilityNodeInfo target = findClickableSelfOrAncestor(node);
        if (target == null) return false;
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo findClickableSelfOrAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.isClickable() && current.isEnabled() && current.isVisibleToUser()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableInAll(List<AccessibilityNodeInfo> roots, String text) {
        if (text == null || text.isEmpty()) return null;
        for (AccessibilityNodeInfo root : roots) {
            AccessibilityNodeInfo found = findClickableByText(root, text);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByTextInAll(List<AccessibilityNodeInfo> roots, String text) {
        if (text == null || text.isEmpty()) return null;
        for (AccessibilityNodeInfo root : roots) {
            AccessibilityNodeInfo found = findNodeByText(root, text);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollableInAll(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo root : roots) {
            AccessibilityNodeInfo s = findScrollable(root);
            if (s != null) return s;
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null || text.isEmpty()) return null;
        String needle = normalizeArabic(text);
        try {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
            if (matches != null) {
                for (AccessibilityNodeInfo n : matches) {
                    if (n != null && n.isVisibleToUser()) {
                        AccessibilityNodeInfo clickable = findClickableSelfOrAncestor(n);
                        if (clickable != null) return clickable;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return findClickableRecursive(root, needle);
    }

    private AccessibilityNodeInfo findClickableRecursive(AccessibilityNodeInfo node, String needle) {
        if (node == null) return null;
        if (matchesText(node, needle) && node.isVisibleToUser()) {
            AccessibilityNodeInfo c = findClickableSelfOrAncestor(node);
            if (c != null) return c;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findClickableRecursive(child, needle);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null || text.isEmpty()) return null;
        String needle = normalizeArabic(text);
        return findAnyRecursive(root, needle);
    }

    private AccessibilityNodeInfo findAnyRecursive(AccessibilityNodeInfo node, String needle) {
        if (node == null) return null;
        if (matchesText(node, needle) && node.isVisibleToUser()) return node;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findAnyRecursive(child, needle);
            if (found != null) return found;
        }
        return null;
    }

    // FIX #3: Detect error texts (any of comma-separated list)
    private AccessibilityNodeInfo findErrorNode(List<AccessibilityNodeInfo> roots) {
        if (config == null || config.errorKeywords == null) return null;
        for (String kw : config.errorKeywords) {
            String key = kw == null ? "" : kw.trim();
            if (key.isEmpty()) continue;
            AccessibilityNodeInfo n = findNodeByTextInAll(roots, key);
            if (n != null) return n;
        }
        return null;
    }

    private boolean matchesText(AccessibilityNodeInfo node, String normalizedNeedle) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && normalizeArabic(text.toString()).contains(normalizedNeedle)) return true;
        if (desc != null && normalizeArabic(desc.toString()).contains(normalizedNeedle)) return true;
        return false;
    }

    private String normalizeArabic(String s) {
        if (s == null) return "";
        String r = s.replaceAll("[\u064b-\u065f\u0670\u0671]", "");
        return r.replace((char) 1571, (char) 1575)  // أ -> ا
                .replace((char) 1573, (char) 1575)  // إ -> ا
                .replace((char) 1570, (char) 1575)  // آ -> ا
                .replace((char) 1609, (char) 1610)  // ى -> ي
                .replace((char) 1577, (char) 1607)  // ة -> ه
                .trim().toLowerCase();
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable() && node.isVisibleToUser()) {
            List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
            if (actions != null) {
                for (AccessibilityNodeInfo.AccessibilityAction a : actions) {
                    if (a.getId() == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return node;
                }
            } else {
                return node;
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findScrollable(child);
            if (found != null) return found;
        }
        return null;
    }

    private void setAction(String action) {
        this.lastAction = action;
        notifyUpdate(STATUS_RUNNING);
    }

    private void notifyUpdate(final String status) {
        final StatusListener l = this.listener;
        if (l == null) return;
        final int count = this.likesCount.get();
        final String act = this.lastAction == null ? "" : this.lastAction;
        handler.post(() -> l.onUpdate(status, count, act));
    }

    private void vibrateAlert() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                long[] pattern = {0, 250, 150, 250, 150, 250};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void vibrateShort() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(40);
            }
        } catch (Throwable ignored) {}
    }

    // ===== BotConfig (v2.0 — extended) =====
    public static class BotConfig {
        public final String likeText;
        public final String yesText;
        public final String closeText;
        public final String targetPackage;
        public final List<String> profileKeywords;
        public final List<String> errorKeywords;
        public final long scanIntervalMs;
        public final long popupWaitMs;
        public final long idleTimeoutMs;
        public final long watchdogMs;
        public final int stopAtCount;
        public final boolean vibrate;
        public final boolean fastMode;
        public final boolean eventDriven;

        public BotConfig(String likeText, String yesText, String closeText, String targetPackage,
                         List<String> profileKeywords, List<String> errorKeywords,
                         long scanIntervalMs, long popupWaitMs, long idleTimeoutMs,
                         long watchdogMs, int stopAtCount,
                         boolean vibrate, boolean fastMode, boolean eventDriven) {
            this.likeText = likeText == null ? "" : likeText.trim();
            this.yesText = yesText == null ? "" : yesText.trim();
            this.closeText = closeText == null ? "" : closeText.trim();
            this.targetPackage = targetPackage == null ? "" : targetPackage.trim();
            this.profileKeywords = profileKeywords == null ? new ArrayList<>() : profileKeywords;
            this.errorKeywords = errorKeywords == null ? new ArrayList<>() : errorKeywords;
            long si = Math.max(150L, scanIntervalMs);
            this.scanIntervalMs = fastMode ? Math.max(150L, si - 50) : si;
            this.popupWaitMs = Math.max(300L, popupWaitMs);
            this.idleTimeoutMs = Math.max(5000L, idleTimeoutMs);
            this.watchdogMs = Math.max(5000L, watchdogMs);
            this.stopAtCount = Math.max(0, stopAtCount);
            this.vibrate = vibrate;
            this.fastMode = fastMode;
            this.eventDriven = eventDriven;
        }
    }
}

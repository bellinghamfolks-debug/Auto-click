package com.mokafeefah.clicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * ClickerService — مكفوف بوت 2.0 (محرك ذكي مستند للأحداث)
 *
 * التحديثات الهندسية في v2.0:
 *  1) Smart Scan & Recycle: استدعاء recycle() على كل عقدة، عدم الاحتفاظ بمراجع قديمة،
 *     وتنظيف ذاكرة العناصر المعالجة (processedBounds) بنافذة منزلقة.
 *  2) Event-Driven Automation: onAccessibilityEvent يلتقط TYPE_WINDOW_STATE_CHANGED
 *     و TYPE_WINDOW_CONTENT_CHANGED ويستدعي tick فوراً (debounced) بدلاً من انتظار 300ms.
 *  3) Filtering Logic: تجاوز فوري لرسائل "مضاف سابقاً" و "لا يمكن الإضافة" مع إغلاق + scroll.
 *  4) Dynamic Scrolling: ACTION_SCROLL_FORWARD على القائمة، مع fallback لـ gesture
 *     بمسافة سحب متكيّفة (أقل عند النجاح، أكثر عند العلق).
 *  5) Watchdog Timer: مؤقت مراقبة 15 ثانية يكشف العلق في نفس الحالة، فيقوم بـ
 *     GLOBAL_ACTION_BACK ثم scroll لإعادة التنشيط (بدل التوقف).
 */
public class ClickerService extends AccessibilityService {

    private static final String TAG = "MokafeefahBot";

    // ===== Service commands =====
    public static final String CMD_START = "com.mokafeefah.clicker.START";
    public static final String CMD_STOP = "com.mokafeefah.clicker.STOP";

    // ===== Runtime state =====
    private volatile boolean running = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Settings (loaded fresh on start)
    private List<String> likeTexts = new ArrayList<>();
    private List<String> yesTexts = new ArrayList<>();
    private List<String> closeTexts = new ArrayList<>();
    private List<String> errorTexts = new ArrayList<>();
    private List<String> profileKeywords = new ArrayList<>();
    private String targetPackage = "";
    private int scanIntervalMs = MainActivity.DEFAULT_SCAN_INTERVAL;
    private int popupWaitMs = MainActivity.DEFAULT_POPUP_WAIT;
    private int idleTimeoutMs = MainActivity.DEFAULT_IDLE_TIMEOUT * 1000;
    private int watchdogMs = MainActivity.DEFAULT_WATCHDOG_SEC * 1000;
    private int stopAtCount = MainActivity.DEFAULT_STOP_AT_COUNT;
    private boolean vibrateEnabled = MainActivity.DEFAULT_VIBRATE;
    private boolean fastMode = MainActivity.DEFAULT_FAST_MODE;
    private boolean eventDriven = MainActivity.DEFAULT_EVENT_DRIVEN;

    private int counter = 0;
    private long lastSuccessAt = 0;
    private long lastProgressAt = 0;   // any forward progress (for watchdog)
    private String lastStateTag = "INIT";
    private int recoveryAttempts = 0;
    private long lastEventTickAt = 0;

    // Bounds of recently processed members (sliding window to avoid re-clicking same)
    private final LinkedHashSet<String> processedBounds = new LinkedHashSet<>();
    private static final int PROCESSED_WINDOW = 60;

    // Adaptive scroll distance (proportion of screen height)
    private float scrollFactor = 0.55f; // 55% of screen by default

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility service connected — v" + MainActivity.APP_VERSION);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            if (CMD_START.equals(act)) startBot();
            else if (CMD_STOP.equals(act)) stopBot(getString(R.string.exec_status_stopped));
        }
        return START_NOT_STICKY;
    }

    // ===== FIX #2: Event-Driven Automation =====
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!running || !eventDriven || event == null) return;
        int t = event.getEventType();
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        // Filter by target package if set
        if (!TextUtils.isEmpty(targetPackage)) {
            CharSequence pkg = event.getPackageName();
            if (pkg == null || !pkg.toString().equals(targetPackage)) return;
        }

        // Debounce: don't run tick more than once every ~120ms from events
        long now = SystemClock.uptimeMillis();
        if (now - lastEventTickAt < 120) return;
        lastEventTickAt = now;

        // Cancel any scheduled tick, run immediately on the main thread
        handler.removeCallbacks(tickRunnable);
        handler.post(tickRunnable);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted");
        stopBot(getString(R.string.exec_status_stopped));
    }

    // ===== Lifecycle: start/stop =====
    private void startBot() {
        if (running) return;
        loadSettings();
        running = true;
        counter = readCounter();
        lastSuccessAt = SystemClock.uptimeMillis();
        lastProgressAt = lastSuccessAt;
        recoveryAttempts = 0;
        processedBounds.clear();
        broadcastStatus(getString(R.string.exec_status_running), counter, getString(R.string.action_wait));
        handler.removeCallbacks(tickRunnable);
        handler.post(tickRunnable);
    }

    private void stopBot(String reason) {
        running = false;
        handler.removeCallbacks(tickRunnable);
        broadcastStatus(reason, counter, reason);
        if (vibrateEnabled) vibrate(220);
        writeCounter();
    }

    // ===== Main tick loop =====
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                performOneTick();
            } catch (Throwable th) {
                Log.e(TAG, "tick error", th);
            }
            // Schedule next tick (will be preempted by events if event-driven)
            int interval = fastMode ? Math.max(150, scanIntervalMs / 2) : scanIntervalMs;
            handler.postDelayed(this, interval);
        }
    };

    private void performOneTick() {
        long now = SystemClock.uptimeMillis();

        // FIX #5: Watchdog — recover if stuck in the same state for too long
        if (now - lastProgressAt > watchdogMs) {
            triggerWatchdogRecovery();
            return;
        }

        // Idle timeout — full auto-stop if nothing happens for a long time
        if (now - lastSuccessAt > idleTimeoutMs && counter > 0) {
            stopBot(getString(R.string.msg_auto_stopped));
            return;
        }

        // Stop-at-count
        if (stopAtCount > 0 && counter >= stopAtCount) {
            stopBot(getString(R.string.msg_target_reached));
            return;
        }

        // Get root node freshly each tick (FIX #1: don't cache nodes across ticks)
        AccessibilityNodeInfo root = getSafeRoot();
        if (root == null) {
            updateState("NO_ROOT");
            return;
        }

        // Check target package
        if (!TextUtils.isEmpty(targetPackage)) {
            CharSequence pkg = root.getPackageName();
            if (pkg == null || !pkg.toString().equals(targetPackage)) {
                safeRecycle(root);
                updateState("WRONG_PKG");
                return;
            }
        }

        try {
            // Step A — handle profile-screen accident: if we're inside a profile screen,
            // press BACK to return to the list (action_back).
            if (isProfileScreen(root)) {
                updateAction(getString(R.string.action_back));
                performGlobalAction(GLOBAL_ACTION_BACK);
                markProgress("BACK_FROM_PROFILE");
                return;
            }

            // Step B — FIX #3: Filtering — detect "مضاف سابقاً" / "لا يمكن الإضافة"
            //  → close + force a fast skip (small scroll)
            AccessibilityNodeInfo errNode = findErrorTextNode(root);
            if (errNode != null) {
                safeRecycle(errNode);
                // Try to close the error popup quickly
                if (clickAnyText(root, closeTexts)) {
                    updateAction(getString(R.string.action_error_skip));
                    markProgress("ERROR_SKIP");
                    // Trigger an immediate small scroll
                    handler.postDelayed(() -> doScroll(0.35f), 150);
                } else {
                    // Fallback: GLOBAL_ACTION_BACK
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    updateAction(getString(R.string.action_error_skip));
                    markProgress("ERROR_BACK");
                }
                return;
            }

            // Step C — popup with "yes/confirm" — click and count it as a success
            if (clickAnyText(root, yesTexts)) {
                counter++;
                writeCounter();
                lastSuccessAt = SystemClock.uptimeMillis();
                if (vibrateEnabled) vibrate(45);
                updateAction(getString(R.string.action_yes));
                broadcastStatus(getString(R.string.exec_status_running), counter, getString(R.string.action_yes));
                markProgress("YES");
                // Tighten the scroll right after a success
                scrollFactor = Math.max(0.40f, scrollFactor - 0.05f);
                return;
            }

            // Step D — success/info popup with "close" — click to dismiss
            if (clickAnyText(root, closeTexts)) {
                updateAction(getString(R.string.action_close));
                markProgress("CLOSE");
                return;
            }

            // Step E — main "like / interest" button — click
            if (clickAnyText(root, likeTexts)) {
                updateAction(getString(R.string.action_like));
                markProgress("LIKE");
                // After "like" the popup may take popupWaitMs ms in worst case;
                // event-driven path will catch the popup earlier.
                return;
            }

            // Step F — nothing actionable — scroll forward
            updateAction(getString(R.string.action_scroll));
            doScroll(scrollFactor);
            // Grow scroll a bit when stuck without finding likes
            scrollFactor = Math.min(0.80f, scrollFactor + 0.02f);
            markProgress("SCROLL");

        } finally {
            // FIX #1: ALWAYS recycle root after the tick
            safeRecycle(root);
        }
    }

    // ===== FIX #5: Watchdog recovery =====
    private void triggerWatchdogRecovery() {
        recoveryAttempts++;
        Log.w(TAG, "Watchdog fired (attempt " + recoveryAttempts + ") — recovering");
        updateAction(getString(R.string.action_watchdog));
        broadcastStatus(getString(R.string.exec_status_running), counter, getString(R.string.msg_recovered));

        // Step 1: BACK to escape any stuck dialog
        performGlobalAction(GLOBAL_ACTION_BACK);

        // Step 2: a moment later, force a bigger scroll to get fresh content
        handler.postDelayed(() -> {
            if (!running) return;
            doScroll(Math.min(0.85f, scrollFactor + 0.10f));
            lastProgressAt = SystemClock.uptimeMillis();
            lastSuccessAt = SystemClock.uptimeMillis(); // reset idle counter on recovery
        }, 350);

        // If recovery is failing repeatedly, give up
        if (recoveryAttempts >= 5) {
            stopBot(getString(R.string.msg_auto_stopped));
        }
    }

    private void markProgress(String tag) {
        lastProgressAt = SystemClock.uptimeMillis();
        if (!tag.equals(lastStateTag)) {
            lastStateTag = tag;
            recoveryAttempts = 0;
        }
    }

    private void updateState(String tag) {
        lastStateTag = tag;
    }

    // ===== FIX #4: Dynamic scrolling =====
    private void doScroll(float factor) {
        // Try ACTION_SCROLL_FORWARD on the active scrollable container first
        AccessibilityNodeInfo root = getSafeRoot();
        if (root != null) {
            AccessibilityNodeInfo scrollable = findScrollable(root);
            if (scrollable != null) {
                boolean ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                safeRecycle(scrollable);
                safeRecycle(root);
                if (ok) return;
            } else {
                safeRecycle(root);
            }
        }
        // Fallback: gesture swipe with adaptive distance
        performSwipeUp(factor);
    }

    private void performSwipeUp(float factor) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getMetrics(dm);
            int h = dm.heightPixels > 0 ? dm.heightPixels : 1920;
            int w = dm.widthPixels > 0 ? dm.widthPixels : 1080;
            float midX = w / 2f;
            float startY = h * (0.55f + Math.min(0.20f, factor * 0.3f));
            float endY = startY - h * Math.max(0.25f, Math.min(0.85f, factor));
            if (endY < h * 0.10f) endY = h * 0.10f;

            Path p = new Path();
            p.moveTo(midX, startY);
            p.lineTo(midX, endY);
            GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 280);
            dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        } catch (Throwable th) {
            Log.e(TAG, "swipe error", th);
        }
    }

    // ===== Node searching =====
    private AccessibilityNodeInfo getSafeRoot() {
        try {
            return getRootInActiveWindow();
        } catch (Throwable th) { return null; }
    }

    private boolean clickAnyText(AccessibilityNodeInfo root, List<String> texts) {
        if (root == null || texts == null || texts.isEmpty()) return false;
        for (String text : texts) {
            if (TextUtils.isEmpty(text)) continue;
            // Try text-match first
            List<AccessibilityNodeInfo> matches = safeFindByText(root, text);
            for (AccessibilityNodeInfo m : matches) {
                if (m == null) continue;
                if (clickClickableAncestor(m)) {
                    recycleList(matches);
                    return true;
                }
                safeRecycle(m);
            }
        }
        return false;
    }

    private boolean clickClickableAncestor(AccessibilityNodeInfo node) {
        if (node == null) return false;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        String key = r.flattenToString();
        if (processedBounds.contains(key)) {
            // Recently clicked — skip
            safeRecycle(node);
            return false;
        }
        AccessibilityNodeInfo cur = node;
        boolean ownedByUs = false;
        try {
            int depth = 0;
            while (cur != null && depth < 8) {
                if (cur.isClickable() && cur.isEnabled() && cur.isVisibleToUser()) {
                    boolean ok = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (ok) {
                        rememberProcessed(key);
                        return true;
                    }
                }
                AccessibilityNodeInfo parent = cur.getParent();
                if (cur != node || ownedByUs) safeRecycle(cur);
                cur = parent;
                ownedByUs = true;
                depth++;
            }
        } finally {
            // Recycle the trail (the original node is recycled by caller's loop)
            if (cur != null && cur != node) safeRecycle(cur);
        }
        return false;
    }

    private void rememberProcessed(String key) {
        processedBounds.add(key);
        while (processedBounds.size() > PROCESSED_WINDOW) {
            String first = processedBounds.iterator().next();
            processedBounds.remove(first);
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isScrollable()) return AccessibilityNodeInfo.obtain(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findScrollable(child);
            if (child != found) safeRecycle(child);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findErrorTextNode(AccessibilityNodeInfo root) {
        if (errorTexts.isEmpty()) return null;
        for (String text : errorTexts) {
            if (TextUtils.isEmpty(text)) continue;
            List<AccessibilityNodeInfo> matches = safeFindByText(root, text);
            if (!matches.isEmpty()) {
                // Return first, recycle the rest
                AccessibilityNodeInfo first = matches.get(0);
                for (int i = 1; i < matches.size(); i++) safeRecycle(matches.get(i));
                return first;
            }
        }
        return null;
    }

    private boolean isProfileScreen(AccessibilityNodeInfo root) {
        if (profileKeywords.isEmpty()) return false;
        for (String kw : profileKeywords) {
            if (TextUtils.isEmpty(kw)) continue;
            List<AccessibilityNodeInfo> matches = safeFindByText(root, kw);
            if (!matches.isEmpty()) {
                recycleList(matches);
                return true;
            }
        }
        return false;
    }

    private List<AccessibilityNodeInfo> safeFindByText(AccessibilityNodeInfo root, String text) {
        try {
            List<AccessibilityNodeInfo> raw = root.findAccessibilityNodeInfosByText(text);
            return raw != null ? raw : new ArrayList<>();
        } catch (Throwable th) {
            return new ArrayList<>();
        }
    }

    // ===== FIX #1: Memory hygiene =====
    private void safeRecycle(AccessibilityNodeInfo n) {
        if (n == null) return;
        try { n.recycle(); } catch (Throwable ignored) {}
    }

    private void recycleList(List<AccessibilityNodeInfo> list) {
        if (list == null) return;
        for (AccessibilityNodeInfo n : list) safeRecycle(n);
    }

    // ===== Persistence =====
    private int readCounter() {
        return getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("counter", 0);
    }

    private void writeCounter() {
        getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("counter", counter).apply();
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        likeTexts = splitCsv(p.getString(MainActivity.KEY_LIKE_TEXT, MainActivity.DEFAULT_LIKE_TEXT));
        yesTexts = splitCsv(p.getString(MainActivity.KEY_YES_TEXT, MainActivity.DEFAULT_YES_TEXT));
        closeTexts = splitCsv(p.getString(MainActivity.KEY_CLOSE_TEXT, MainActivity.DEFAULT_CLOSE_TEXT));
        errorTexts = splitCsv(p.getString(MainActivity.KEY_ERROR_KEYWORDS, MainActivity.DEFAULT_ERROR_KEYWORDS));
        profileKeywords = splitCsv(p.getString(MainActivity.KEY_PROFILE_KEYWORDS, MainActivity.DEFAULT_PROFILE_KEYWORDS));
        targetPackage = p.getString(MainActivity.KEY_TARGET_PACKAGE, "").trim();
        scanIntervalMs = Math.max(120, p.getInt(MainActivity.KEY_SCAN_INTERVAL, MainActivity.DEFAULT_SCAN_INTERVAL));
        popupWaitMs = Math.max(300, p.getInt(MainActivity.KEY_POPUP_WAIT, MainActivity.DEFAULT_POPUP_WAIT));
        idleTimeoutMs = Math.max(5, p.getInt(MainActivity.KEY_IDLE_TIMEOUT, MainActivity.DEFAULT_IDLE_TIMEOUT)) * 1000;
        watchdogMs = Math.max(5, p.getInt(MainActivity.KEY_WATCHDOG_SEC, MainActivity.DEFAULT_WATCHDOG_SEC)) * 1000;
        stopAtCount = Math.max(0, p.getInt(MainActivity.KEY_STOP_AT_COUNT, MainActivity.DEFAULT_STOP_AT_COUNT));
        vibrateEnabled = p.getBoolean(MainActivity.KEY_VIBRATE, MainActivity.DEFAULT_VIBRATE);
        fastMode = p.getBoolean(MainActivity.KEY_FAST_MODE, MainActivity.DEFAULT_FAST_MODE);
        eventDriven = p.getBoolean(MainActivity.KEY_EVENT_DRIVEN, MainActivity.DEFAULT_EVENT_DRIVEN);
        // Stamp version
        p.edit().putString(MainActivity.KEY_APP_VERSION, MainActivity.APP_VERSION).apply();
    }

    private static List<String> splitCsv(String s) {
        if (s == null) return new ArrayList<>();
        String[] parts = s.split("[,\\u060c]");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ===== UI updates =====
    private void broadcastStatus(String exec, int count, String lastAction) {
        Intent i = new Intent(MainActivity.ACTION_STATUS_UPDATE);
        i.setPackage(getPackageName());
        if (exec != null) i.putExtra(MainActivity.EXTRA_EXEC_STATUS, exec);
        i.putExtra(MainActivity.EXTRA_COUNTER, count);
        if (lastAction != null) i.putExtra(MainActivity.EXTRA_LAST_ACTION, lastAction);
        try { sendBroadcast(i); } catch (Exception ignored) {}
    }

    private void updateAction(String action) {
        broadcastStatus(getString(R.string.exec_status_running), counter, action);
    }

    private void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) {}
    }
}

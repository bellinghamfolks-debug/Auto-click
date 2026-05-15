package com.mokafeefah.clicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * بوت ذكي يعمل عبر تحليل شجرة العناصر لكل النوافذ النشطة، ويستخدم آلة حالات
 * صارمة لضمان دورة منضبطة: إعجاب → نعم → إغلاق → تمرير قوي → إعجاب التالي.
 *
 * مميزات هذه النسخة:
 *  - آلة حالات (State Machine) تمنع تكرار الإعجاب على نفس العضو.
 *  - بعد نقرة "نعم" أو "إغلاق" يدخل البوت حالة "يجب التمرير" ولا يخرج منها حتى يمرر.
 *  - تمرير ذكي: محاولة ACTION_SCROLL_FORWARD أولًا، ثم سحب قوي بـ dispatchGesture (600 بكسل).
 *  - تأخير 1000ms بعد التمرير قبل البحث عن العضو الجديد.
 *  - بحث في جميع النوافذ بترتيب z-order.
 *  - إيقاف تلقائي بعد مهلة بدون أزرار + اهتزاز عند الإيقاف.
 *  - حماية من الدخول الخطأ لملف شخصي.
 */
public class ClickerService extends AccessibilityService {

    // ===== Public status constants (تستخدمها MainActivity) =====
    public static final String STATUS_IDLE = "خامل";
    public static final String STATUS_RUNNING = "يعمل";
    public static final String STATUS_STOPPED = "متوقف";
    public static final String STATUS_AUTO_STOPPED = "إيقاف تلقائي";

    public interface StatusListener {
        void onUpdate(String status, int likesCount, String lastAction);
    }

    // ===== State machine =====
    private static final int STATE_LOOK_LIKE   = 0; // البحث عن زر إعجاب جديد
    private static final int STATE_AFTER_LIKE  = 1; // ينتظر ظهور نافذة (نعم أو إغلاق)
    private static final int STATE_AFTER_YES   = 2; // ينتظر ظهور نافذة النجاح (إغلاق)
    private static final int STATE_MUST_SCROLL = 3; // يجب التمرير قبل أي إجراء آخر

    // أقصى مدة بقاء في كل حالة قبل قرار اضطراري
    private static final long MAX_WAIT_AFTER_LIKE_MS = 6000L; // إذا لم تظهر نافذة في 6 ثوانٍ -> تمرير
    private static final long MAX_WAIT_AFTER_YES_MS = 3500L;  // إذا لم تظهر نافذة نجاح -> تمرير
    private static final long POST_SCROLL_WAIT_MS = 1000L;    // تأخير بعد التمرير (كما طلب المستخدم)
    private static final int SCROLL_DISTANCE_PX = 600;        // مسافة السحب القوي

    private static ClickerService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger likesCount = new AtomicInteger(0);

    private StatusListener listener;
    private BotConfig config;
    private String lastAction = "";
    private long lastBackTime = 0;
    private long lastButtonFoundMs = 0;
    private int state = STATE_LOOK_LIKE;
    private long stateChangedAt = 0;

    public static ClickerService getInstance() { return instance; }
    public static boolean isServiceRunning() { return instance != null; }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { running.set(false); }

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
        long now = System.currentTimeMillis();
        this.lastButtonFoundMs = now;
        this.state = STATE_LOOK_LIKE;
        this.stateChangedAt = now;
        running.set(true);
        notifyUpdate(STATUS_RUNNING);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(tick, 500L);
        return true;
    }

    public void stopBot() { stopInternal(STATUS_STOPPED); }

    private void stopInternal(String reason) {
        if (!running.get()) return;
        running.set(false);
        handler.removeCallbacksAndMessages(null);
        vibrateAlert();
        notifyUpdate(reason);
    }

    private void transitionTo(int newState) {
        this.state = newState;
        this.stateChangedAt = System.currentTimeMillis();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) return;
            long nextDelay = config.scanIntervalMs;
            try {
                nextDelay = performOneTick();
            } catch (Throwable t) {
                // لا تتعطل أبدًا
            }
            if (running.get()) {
                handler.postDelayed(this, Math.max(150L, nextDelay));
            }
        }
    };

    private long performOneTick() {
        long now = System.currentTimeMillis();

        // 1. إيقاف تلقائي عند تجاوز مهلة عدم وجود أزرار
        if (now - lastButtonFoundMs > config.idleTimeoutMs) {
            stopInternal(STATUS_AUTO_STOPPED);
            return config.scanIntervalMs;
        }

        // 2. جمع جذور كل النوافذ (الأعلى z-order أولًا)
        List<AccessibilityNodeInfo> roots = collectAllRoots();
        if (roots.isEmpty()) {
            setAction(getString(R.string.action_wait));
            return config.scanIntervalMs;
        }

        // 3. تصفية بالحزمة الهدف (إن وُجدت)
        if (config.targetPackage != null && !config.targetPackage.isEmpty()) {
            boolean anyMatches = false;
            for (AccessibilityNodeInfo r : roots) {
                CharSequence pkg = r.getPackageName();
                if (pkg != null && config.targetPackage.equals(pkg.toString())) {
                    anyMatches = true;
                    break;
                }
            }
            if (!anyMatches) {
                setAction(getString(R.string.action_wait));
                return config.scanIntervalMs;
            }
        }

        // 4. شرط الأمان: ملف شخصي -> رجوع تلقائي (في أي حالة)
        if (now - lastBackTime > 1500L) {
            for (String kw : config.profileKeywords) {
                String key = kw == null ? "" : kw.trim();
                if (key.isEmpty()) continue;
                if (findNodeByTextInAll(roots, key) != null) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastBackTime = now;
                    setAction(getString(R.string.action_back));
                    // أي عملية سابقة ملغاة، ابدأ من جديد بحثًا عن إعجاب
                    transitionTo(STATE_LOOK_LIKE);
                    return 700L;
                }
            }
        }

        // 5. آلة الحالات - كل حالة لها سلوكها الخاص
        switch (state) {
            case STATE_LOOK_LIKE:
                return handleLookLike(roots, now);
            case STATE_AFTER_LIKE:
                return handleAfterLike(roots, now);
            case STATE_AFTER_YES:
                return handleAfterYes(roots, now);
            case STATE_MUST_SCROLL:
                return handleMustScroll(roots, now);
            default:
                transitionTo(STATE_LOOK_LIKE);
                return config.scanIntervalMs;
        }
    }

    /** الحالة 0: ابحث عن زر إعجاب جديد. (مع التعامل مع نوافذ ضالة من دورة سابقة) */
    private long handleLookLike(List<AccessibilityNodeInfo> roots, long now) {
        // إن وُجدت نافذة قديمة من دورة سابقة - أغلقها أولًا
        AccessibilityNodeInfo yesNode = findClickableInAll(roots, config.yesText);
        if (yesNode != null) {
            if (clickNode(yesNode)) {
                setAction(getString(R.string.action_yes));
                lastButtonFoundMs = now;
                transitionTo(STATE_AFTER_YES);
                return 700L;
            }
        }
        AccessibilityNodeInfo closeNode = findClickableInAll(roots, config.closeText);
        if (closeNode != null) {
            if (clickNode(closeNode)) {
                setAction(getString(R.string.action_close));
                transitionTo(STATE_MUST_SCROLL);
                return 800L;
            }
        }

        // ابحث عن زر إعجاب
        AccessibilityNodeInfo likeNode = findClickableInAll(roots, config.likeText);
        if (likeNode != null) {
            if (clickNode(likeNode)) {
                likesCount.incrementAndGet();
                setAction(getString(R.string.action_like));
                lastButtonFoundMs = now;
                transitionTo(STATE_AFTER_LIKE);
                return config.popupWaitMs; // 2000ms افتراضي
            }
            return config.scanIntervalMs;
        }

        // لا توجد أزرار - مرر القائمة برمجيًا للوصول لأعضاء جدد
        if (performSmartScroll(roots)) {
            setAction(getString(R.string.action_scroll));
            return POST_SCROLL_WAIT_MS;
        }
        setAction(getString(R.string.action_wait));
        return config.scanIntervalMs;
    }

    /** الحالة 1: نقرنا إعجاب - ننتظر ظهور نافذة (نعم أو إغلاق). */
    private long handleAfterLike(List<AccessibilityNodeInfo> roots, long now) {
        AccessibilityNodeInfo yesNode = findClickableInAll(roots, config.yesText);
        if (yesNode != null) {
            if (clickNode(yesNode)) {
                setAction(getString(R.string.action_yes));
                lastButtonFoundMs = now;
                transitionTo(STATE_AFTER_YES);
                return 700L;
            }
            return config.scanIntervalMs;
        }

        AccessibilityNodeInfo closeNode = findClickableInAll(roots, config.closeText);
        if (closeNode != null) {
            if (clickNode(closeNode)) {
                setAction(getString(R.string.action_close));
                transitionTo(STATE_MUST_SCROLL);
                return 800L;
            }
            return config.scanIntervalMs;
        }

        // إذا تجاوزنا المهلة بدون ظهور نافذة - تمرير اضطراري
        if (now - stateChangedAt > MAX_WAIT_AFTER_LIKE_MS) {
            transitionTo(STATE_MUST_SCROLL);
            return 200L;
        }
        setAction(getString(R.string.action_wait));
        return config.scanIntervalMs;
    }

    /** الحالة 2: نقرنا نعم - ننتظر نافذة النجاح (إغلاق). */
    private long handleAfterYes(List<AccessibilityNodeInfo> roots, long now) {
        AccessibilityNodeInfo closeNode = findClickableInAll(roots, config.closeText);
        if (closeNode != null) {
            if (clickNode(closeNode)) {
                setAction(getString(R.string.action_close));
                transitionTo(STATE_MUST_SCROLL);
                return 800L;
            }
            return config.scanIntervalMs;
        }
        // إن لم تظهر نافذة النجاح خلال المهلة - تمرير اضطراري
        if (now - stateChangedAt > MAX_WAIT_AFTER_YES_MS) {
            transitionTo(STATE_MUST_SCROLL);
            return 200L;
        }
        setAction(getString(R.string.action_wait));
        return config.scanIntervalMs;
    }

    /** الحالة 3: يجب التمرير قبل أي بحث جديد عن إعجاب. */
    private long handleMustScroll(List<AccessibilityNodeInfo> roots, long now) {
        boolean scrolled = performSmartScroll(roots);
        if (scrolled) {
            setAction(getString(R.string.action_scroll));
        }
        transitionTo(STATE_LOOK_LIKE);
        return POST_SCROLL_WAIT_MS; // 1000ms قبل البحث عن إعجاب جديد
    }

    /**
     * تمرير ذكي: يحاول ACTION_SCROLL_FORWARD أولًا، ثم سحب قوي بـ dispatchGesture كاحتياط.
     */
    private boolean performSmartScroll(List<AccessibilityNodeInfo> roots) {
        // المحاولة 1: ACTION_SCROLL_FORWARD على القائمة
        AccessibilityNodeInfo scrollable = findScrollableInAll(roots);
        if (scrollable != null) {
            boolean ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            if (ok) return true;
        }
        // المحاولة 2: سحب قوي على إحداثيات الشاشة (600 بكسل)
        return performGestureSwipeUp();
    }

    private boolean performGestureSwipeUp() {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int centerX = dm.widthPixels / 2;
            int startY = (int) (dm.heightPixels * 0.75f);
            int endY = startY - SCROLL_DISTANCE_PX;
            if (endY < (int) (dm.heightPixels * 0.10f)) {
                endY = (int) (dm.heightPixels * 0.10f);
            }
            Path path = new Path();
            path.moveTo(centerX, startY);
            path.lineTo(centerX, endY);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 400);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    // ===================== Window & node helpers =====================

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
        int depth = 0;
        while (current != null && depth < 8) {
            if (current.isClickable() && current.isEnabled() && current.isVisibleToUser()) {
                return current;
            }
            current = current.getParent();
            depth++;
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
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
        if (matches != null) {
            for (AccessibilityNodeInfo n : matches) {
                if (n == null || !n.isVisibleToUser()) continue;
                AccessibilityNodeInfo clickable = findClickableSelfOrAncestor(n);
                if (clickable != null) return clickable;
            }
        }
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
        String r = s.replaceAll("[\u064B-\u0652\u0670\u0671]", "");
        r = r.replace('\u0623', '\u0627')
             .replace('\u0625', '\u0627')
             .replace('\u0622', '\u0627')
             .replace('\u0649', '\u064A')
             .replace('\u0629', '\u0647');
        return r.trim().toLowerCase();
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
        final int count = likesCount.get();
        final String act = lastAction == null ? "" : lastAction;
        handler.post(() -> l.onUpdate(status, count, act));
    }

    private void vibrateAlert() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = {0, 250, 150, 250, 150, 250};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (Throwable ignored) {}
    }

    public static class BotConfig {
        public final String likeText;
        public final String yesText;
        public final String closeText;
        public final String targetPackage;
        public final List<String> profileKeywords;
        public final long scanIntervalMs;
        public final long popupWaitMs;
        public final long idleTimeoutMs;

        public BotConfig(String likeText, String yesText, String closeText,
                         String targetPackage, List<String> profileKeywords,
                         long scanIntervalMs, long popupWaitMs, long idleTimeoutMs) {
            this.likeText = likeText == null ? "" : likeText.trim();
            this.yesText = yesText == null ? "" : yesText.trim();
            this.closeText = closeText == null ? "" : closeText.trim();
            this.targetPackage = targetPackage == null ? "" : targetPackage.trim();
            this.profileKeywords = profileKeywords == null ? new ArrayList<>() : profileKeywords;
            this.scanIntervalMs = Math.max(150L, scanIntervalMs);
            this.popupWaitMs = Math.max(300L, popupWaitMs);
            this.idleTimeoutMs = Math.max(5000L, idleTimeoutMs);
        }
    }
}

package com.mokafeefah.clicker;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * بوت ذكي يعمل عبر تحليل شجرة العناصر (UI Hierarchy) لكل النوافذ النشطة.
 *
 * مميزات هذه النسخة:
 *  - البحث في جميع النوافذ النشطة بترتيب z-order (النوافذ المنبثقة أولًا).
 *  - تأخير قابل للضبط (افتراضي 2000 مللي ثانية) بعد نقرة الإعجاب لانتظار ظهور النافذة المنبثقة.
 *  - إيقاف تلقائي إذا لم يجد البوت أي إعجاب أو نعم خلال مدة محددة (افتراضي 30 ثانية).
 *  - اهتزاز عند الإيقاف (يدوي أو تلقائي) لتنبيه المستخدم الكفيف.
 *  - حماية من الدخول الخطأ لملف شخصي عبر GLOBAL_ACTION_BACK.
 */
public class ClickerService extends AccessibilityService {

    public static final String STATUS_IDLE = "خامل";
    public static final String STATUS_RUNNING = "يعمل";
    public static final String STATUS_STOPPED = "متوقف";
    public static final String STATUS_AUTO_STOPPED = "إيقاف تلقائي";

    public interface StatusListener {
        void onUpdate(String status, int likesCount, String lastAction);
    }

    private static ClickerService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger likesCount = new AtomicInteger(0);

    private StatusListener listener;
    private BotConfig config;
    private String lastAction = "";
    private long lastBackTime = 0;
    private long lastButtonFoundMs = 0;

    public static ClickerService getInstance() {
        return instance;
    }

    public static boolean isServiceRunning() {
        return instance != null;
    }

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
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // لا نعتمد على الأحداث، نستخدم فحصًا دوريًا بدلًا منها.
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
        this.lastButtonFoundMs = System.currentTimeMillis();
        running.set(true);
        notifyUpdate(STATUS_RUNNING);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(tick, 500L);
        return true;
    }

    public void stopBot() {
        stopInternal(STATUS_STOPPED);
    }

    private void stopInternal(String reason) {
        if (!running.get()) return;
        running.set(false);
        handler.removeCallbacksAndMessages(null);
        vibrateAlert();
        notifyUpdate(reason);
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

    /**
     * ينفذ تكة واحدة من الحلقة. يعيد المدة المقترحة قبل التكة التالية.
     */
    private long performOneTick() {
        // فحص الإيقاف التلقائي عند تجاوز المهلة
        long now = System.currentTimeMillis();
        if (now - lastButtonFoundMs > config.idleTimeoutMs) {
            stopInternal(STATUS_AUTO_STOPPED);
            return config.scanIntervalMs;
        }

        // اجمع كل النوافذ النشطة مرتبة من الأعلى (النوافذ المنبثقة) إلى الأسفل
        List<AccessibilityNodeInfo> roots = collectAllRoots();
        if (roots.isEmpty()) {
            setAction(getString(R.string.action_wait));
            return config.scanIntervalMs;
        }

        // تصفية بالحزمة إن وُجدت
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

        // شرط الأمان: ملف شخصي
        if (now - lastBackTime > 1500L) {
            for (String kw : config.profileKeywords) {
                String key = kw == null ? "" : kw.trim();
                if (key.isEmpty()) continue;
                if (findNodeByTextInAll(roots, key) != null) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastBackTime = now;
                    setAction(getString(R.string.action_back));
                    return 700L;
                }
            }
        }

        // الأولوية: نعم -> إغلاق -> إعجاب (نبحث في كل النوافذ بدءًا من الأعلى)
        AccessibilityNodeInfo yesNode = findClickableInAll(roots, config.yesText);
        if (yesNode != null) {
            if (clickNode(yesNode)) {
                setAction(getString(R.string.action_yes));
                lastButtonFoundMs = now;
                return 700L; // مهلة قصيرة لانتظار ظهور نافذة النجاح
            }
            return config.scanIntervalMs;
        }

        AccessibilityNodeInfo closeNode = findClickableInAll(roots, config.closeText);
        if (closeNode != null) {
            if (clickNode(closeNode)) {
                setAction(getString(R.string.action_close));
                return 800L; // مهلة لانغلاق النافذة قبل البحث عن إعجاب جديد
            }
            return config.scanIntervalMs;
        }

        AccessibilityNodeInfo likeNode = findClickableInAll(roots, config.likeText);
        if (likeNode != null) {
            if (clickNode(likeNode)) {
                likesCount.incrementAndGet();
                setAction(getString(R.string.action_like));
                lastButtonFoundMs = now;
                // التأخير الأهم: انتظر ظهور النافذة المنبثقة الفعلي قبل البحث عن "نعم"
                return config.popupWaitMs;
            }
            return config.scanIntervalMs;
        }

        // لا توجد أزرار - مرر القائمة برمجيًا
        AccessibilityNodeInfo scrollable = findScrollableInAll(roots);
        if (scrollable != null) {
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            setAction(getString(R.string.action_scroll));
            return 800L;
        }
        setAction(getString(R.string.action_wait));
        return config.scanIntervalMs;
    }

    /**
     * يجمع جذور جميع النوافذ النشطة مرتبة بـ z-order (النوافذ الفوقية أولًا).
     * يدمج النافذة النشطة (active) كاحتياط أيضًا.
     */
    private List<AccessibilityNodeInfo> collectAllRoots() {
        List<AccessibilityNodeInfo> result = new ArrayList<>();

        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
        } catch (Throwable t) {
            windows = null;
        }

        if (windows != null && !windows.isEmpty()) {
            List<AccessibilityWindowInfo> sorted = new ArrayList<>(windows);
            // أعلى layer أولًا
            Collections.sort(sorted, (a, b) -> Integer.compare(b.getLayer(), a.getLayer()));
            for (AccessibilityWindowInfo w : sorted) {
                if (w == null) continue;
                AccessibilityNodeInfo r = null;
                try { r = w.getRoot(); } catch (Throwable ignored) {}
                if (r != null) result.add(r);
            }
        }

        // احتياط: النافذة النشطة
        AccessibilityNodeInfo active = null;
        try { active = getRootInActiveWindow(); } catch (Throwable ignored) {}
        if (active != null && !result.contains(active)) {
            result.add(0, active); // ضعها في المقدمة كأولوية
        }
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

    /** يبحث عن أول عنصر قابل للنقر مطابق للنص في أي من النوافذ المعطاة (بترتيب الأولوية). */
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

        // 1) المحاولة الرسمية أولًا
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
        if (matches != null) {
            for (AccessibilityNodeInfo n : matches) {
                if (n == null || !n.isVisibleToUser()) continue;
                AccessibilityNodeInfo clickable = findClickableSelfOrAncestor(n);
                if (clickable != null) return clickable;
            }
        }
        // 2) بحث تكراري يدوي مع تطبيع
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
        if (matchesText(node, needle) && node.isVisibleToUser()) {
            return node;
        }
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

    /** تطبيع النص العربي لمطابقة مرنة (تجاهل التشكيل وأشكال الألف/الياء/التاء المربوطة). */
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
                    if (a.getId() == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                        return node;
                    }
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

    /** اهتزاز تنبيهي: ثلاث نبضات لإعلام المستخدم الكفيف بالإيقاف. */
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

    /** إعدادات البوت كاملة. */
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

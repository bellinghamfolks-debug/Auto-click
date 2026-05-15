package com.mokafeefah.clicker;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * بوت ذكي يعمل عبر تحليل شجرة العناصر (UI Hierarchy) للشاشة الحالية.
 *
 * آلية العمل:
 *   كل تكة (افتراضي 300 مللي ثانية):
 *     1. اقرأ شجرة الشاشة عبر getRootInActiveWindow().
 *     2. تحقق من حزمة التطبيق الهدف (إن وُجد قيد).
 *     3. شرط الأمان: إن وُجدت كلمات تدل على ملف شخصي -> رجوع تلقائي.
 *     4. ابحث عن زر "نعم" - إن وُجد انقره.
 *     5. وإلا ابحث عن زر "إغلاق" - إن وُجد انقره.
 *     6. وإلا ابحث عن زر "إعجاب/اهتمام" - إن وُجد انقره وزد العداد.
 *     7. وإلا مرر القائمة برمجيًا.
 *
 * كل النقرات تتم عبر AccessibilityNodeInfo.performAction(ACTION_CLICK)
 * مما يضمن دقة 100% ولا يلمس أي منطقة غير مقصودة.
 */
public class ClickerService extends AccessibilityService {

    public static final String STATUS_IDLE = "خامل";
    public static final String STATUS_RUNNING = "يعمل";
    public static final String STATUS_STOPPED = "متوقف";

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
        // إرسال آخر حالة معروفة فور الاشتراك
        if (l != null) {
            l.onUpdate(running.get() ? STATUS_RUNNING : STATUS_IDLE,
                    likesCount.get(),
                    lastAction == null ? "" : lastAction);
        }
    }

    public boolean isExecuting() {
        return running.get();
    }

    public int getLikesCount() {
        return likesCount.get();
    }

    public String getLastAction() {
        return lastAction == null ? "" : lastAction;
    }

    /** بدء البوت. يعيد false إذا كان يعمل بالفعل. */
    public boolean startBot(BotConfig cfg) {
        if (running.get()) return false;
        this.config = cfg;
        this.likesCount.set(0);
        this.lastAction = "";
        running.set(true);
        notifyUpdate(STATUS_RUNNING);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(tick, 500L); // مهلة قصيرة ليفتح المستخدم التطبيق الهدف
        return true;
    }

    public void stopBot() {
        if (!running.get()) return;
        running.set(false);
        handler.removeCallbacksAndMessages(null);
        notifyUpdate(STATUS_STOPPED);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) return;
            try {
                performOneTick();
            } catch (Throwable t) {
                // لا تتعطل أبدًا، استمر بالعمل
            }
            if (running.get()) {
                handler.postDelayed(this, Math.max(150L, config.scanIntervalMs));
            }
        }
    };

    private void performOneTick() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            setAction(getString(R.string.action_wait));
            return;
        }

        // تصفية حسب حزمة التطبيق الهدف (إن وضع المستخدم قيدًا)
        if (config.targetPackage != null && !config.targetPackage.isEmpty()) {
            CharSequence pkg = root.getPackageName();
            if (pkg == null || !config.targetPackage.equals(pkg.toString())) {
                setAction(getString(R.string.action_wait));
                return;
            }
        }

        // شرط الأمان: إن دخلنا ملفًا شخصيًا بالخطأ -> رجوع تلقائي
        // (نضع فاصلًا زمنيًا لمنع تكرار Back بسرعة)
        if (System.currentTimeMillis() - lastBackTime > 1500L) {
            for (String kw : config.profileKeywords) {
                String key = kw == null ? "" : kw.trim();
                if (key.isEmpty()) continue;
                if (findNodeByText(root, key) != null) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    lastBackTime = System.currentTimeMillis();
                    setAction(getString(R.string.action_back));
                    return;
                }
            }
        }

        // الأولوية: نعم -> إغلاق -> إعجاب -> تمرير
        AccessibilityNodeInfo yesNode = findClickableByText(root, config.yesText);
        if (yesNode != null) {
            if (clickNode(yesNode)) {
                setAction(getString(R.string.action_yes));
            }
            return;
        }

        AccessibilityNodeInfo closeNode = findClickableByText(root, config.closeText);
        if (closeNode != null) {
            if (clickNode(closeNode)) {
                setAction(getString(R.string.action_close));
            }
            return;
        }

        AccessibilityNodeInfo likeNode = findClickableByText(root, config.likeText);
        if (likeNode != null) {
            if (clickNode(likeNode)) {
                likesCount.incrementAndGet();
                setAction(getString(R.string.action_like));
            }
            return;
        }

        // لا توجد أزرار - مرر القائمة برمجيًا
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null) {
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            setAction(getString(R.string.action_scroll));
        } else {
            setAction(getString(R.string.action_wait));
        }
    }

    /** ينقر على عقدة قابلة للنقر، يصعد للأب إن لم تكن العقدة نفسها قابلة. */
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

    /**
     * يبحث عن عقدة قابلة للنقر تطابق نصًا معينًا (في text أو contentDescription).
     */
    private AccessibilityNodeInfo findClickableByText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null || text.isEmpty()) return null;
        String needle = normalizeArabic(text);

        // المحاولة 1: استخدام findAccessibilityNodeInfosByText الرسمية
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
        if (matches != null) {
            for (AccessibilityNodeInfo n : matches) {
                if (n == null || !n.isVisibleToUser()) continue;
                AccessibilityNodeInfo clickable = findClickableSelfOrAncestor(n);
                if (clickable != null) return clickable;
            }
        }

        // المحاولة 2: بحث تكراري يدوي مع تطبيع النص العربي
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

    /** يبحث عن أي عقدة مرئية تحتوي النص (دون اشتراط أن تكون قابلة للنقر). */
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

    /**
     * يطبّع النص العربي لمطابقة أكثر مرونة:
     *  - إزالة التشكيل
     *  - توحيد أشكال الألف (إ، أ، آ -> ا)
     *  - توحيد ى -> ي
     *  - توحيد ة -> ه
     *  - إزالة المسافات الزائدة وتحويل للأحرف الصغيرة (للإنجليزية)
     */
    private String normalizeArabic(String s) {
        if (s == null) return "";
        // إزالة التشكيل
        String r = s.replaceAll("[\u064B-\u0652\u0670\u0671]", "");
        r = r.replace('\u0623', '\u0627') // أ -> ا
             .replace('\u0625', '\u0627') // إ -> ا
             .replace('\u0622', '\u0627') // آ -> ا
             .replace('\u0649', '\u064A') // ى -> ي
             .replace('\u0629', '\u0647'); // ة -> ه
        return r.trim().toLowerCase();
    }

    /** يبحث عن عنصر قابل للتمرير في الشجرة. */
    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable() && node.isVisibleToUser()) {
            // تأكد أن التمرير للأمام ممكن
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

    /** إعدادات البوت كاملة. */
    public static class BotConfig {
        public final String likeText;
        public final String yesText;
        public final String closeText;
        public final String targetPackage;
        public final List<String> profileKeywords;
        public final long scanIntervalMs;

        public BotConfig(String likeText, String yesText, String closeText,
                         String targetPackage, List<String> profileKeywords, long scanIntervalMs) {
            this.likeText = likeText == null ? "" : likeText.trim();
            this.yesText = yesText == null ? "" : yesText.trim();
            this.closeText = closeText == null ? "" : closeText.trim();
            this.targetPackage = targetPackage == null ? "" : targetPackage.trim();
            this.profileKeywords = profileKeywords == null ? new ArrayList<>() : profileKeywords;
            this.scanIntervalMs = Math.max(150L, scanIntervalMs);
        }
    }
}

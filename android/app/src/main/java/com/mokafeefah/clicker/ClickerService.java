package com.mokafeefah.clicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * خدمة إمكانية الوصول الخاصة بـ "مكفوف كليكر".
 *
 * تنفذ تسلسلًا قابلًا للضبط بالكامل:
 *   1) نقر زر الإعجاب
 *   2) انتظار قابل للضبط
 *   3) نقر زر نعم
 *   4) انتظار قابل للضبط
 *   5) نقر زر الإغلاق (يغلق نافذة النجاح أو نافذة قيود السن/الجنس)
 *   6) انتظار قابل للضبط
 *   7) تمرير اختياري بمسافة قابلة للضبط
 *   8) تكرار للعضو التالي
 *
 * تستخدم فقط dispatchGesture المرخّص رسميًا عبر AccessibilityService،
 * بدون روت ودون تجاوز حماية أي تطبيق.
 */
public class ClickerService extends AccessibilityService {

    public static final String STATUS_IDLE = "خامل";
    public static final String STATUS_RUNNING = "يعمل";
    public static final String STATUS_STOPPED = "متوقف";
    public static final String STATUS_COMPLETED = "مكتمل";

    public interface StatusListener {
        void onStatus(String status, int currentIteration, int totalIterations);
    }

    private static ClickerService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private StatusListener listener;
    private SequenceConfig config;
    private int currentIteration;

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
        // لا نراقب الأحداث - فقط ننفذ النقرات عند الطلب.
    }

    @Override
    public void onInterrupt() {
        running.set(false);
    }

    public void setStatusListener(StatusListener l) {
        this.listener = l;
    }

    public boolean isExecuting() {
        return running.get();
    }

    /**
     * تنفيذ نقرة واحدة تجريبية على إحداثيات محددة.
     */
    public boolean performSingleClick(int x, int y) {
        return dispatchClickAt(x, y, null);
    }

    /**
     * بدء تسلسل النقرات التلقائية. يعيد false إذا كانت هناك عملية تعمل.
     */
    public boolean startSequence(SequenceConfig cfg) {
        if (running.get()) return false;
        this.config = cfg;
        this.currentIteration = 0;
        running.set(true);
        notifyStatus(STATUS_RUNNING);
        runNextIteration();
        return true;
    }

    public void stopSequence() {
        if (!running.get()) return;
        running.set(false);
        mainHandler.removeCallbacksAndMessages(null);
        notifyStatus(STATUS_STOPPED);
    }

    private void runNextIteration() {
        if (!running.get()) return;
        if (currentIteration >= config.repeat) {
            running.set(false);
            notifyStatus(STATUS_COMPLETED);
            return;
        }
        currentIteration++;
        notifyStatus(STATUS_RUNNING);

        // الخطوة 1: نقر زر الإعجاب
        dispatchClickAt(config.likeX, config.likeY, () -> {
            if (!running.get()) return;
            mainHandler.postDelayed(() -> {
                if (!running.get()) return;
                // الخطوة 2: نقر زر نعم (يعمل عند نافذة التأكيد، يتجاهل عند نافذة القيود)
                dispatchClickAt(config.yesX, config.yesY, () -> {
                    if (!running.get()) return;
                    mainHandler.postDelayed(() -> {
                        if (!running.get()) return;
                        // الخطوة 3: نقر زر الإغلاق
                        // يغلق نافذة النجاح بعد التأكيد، أو يغلق نافذة قيود السن/الجنس مباشرة
                        dispatchClickAt(config.closeX, config.closeY, () -> {
                            if (!running.get()) return;
                            mainHandler.postDelayed(() -> {
                                if (!running.get()) return;
                                if (config.scroll) {
                                    // الخطوة 4 (اختيارية): تمرير لأسفل بالمسافة المضبوطة
                                    dispatchScrollDown(config.scrollDistance, () -> {
                                        if (!running.get()) return;
                                        // فاصل صغير قبل البدء بالعضو التالي
                                        mainHandler.postDelayed(this::runNextIteration, 300L);
                                    });
                                } else {
                                    runNextIteration();
                                }
                            }, config.delayAfterClose);
                        });
                    }, config.delayAfterYes);
                });
            }, config.delayAfterLike);
        });
    }

    private boolean dispatchClickAt(int x, int y, Runnable onComplete) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 60);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (onComplete != null) mainHandler.post(onComplete);
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (onComplete != null) mainHandler.post(onComplete);
            }
        }, null);
    }

    /**
     * تمرير بمسافة محددة بالبكسل بدءًا من الجزء السفلي من الشاشة للأعلى.
     */
    private void dispatchScrollDown(int distancePx, Runnable onComplete) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int centerX = dm.widthPixels / 2;
        // نبدأ التمرير من نقطة في الثلث السفلي وننتهي للأعلى بمسافة المسحة المطلوبة
        int startY = (int) (dm.heightPixels * 0.72f);
        int endY = startY - Math.max(50, distancePx);
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
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (onComplete != null) mainHandler.post(onComplete);
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (onComplete != null) mainHandler.post(onComplete);
            }
        }, null);
    }

    private void notifyStatus(String status) {
        if (listener != null) {
            int total = config != null ? config.repeat : 0;
            listener.onStatus(status, currentIteration, total);
        }
    }

    /**
     * إعدادات التسلسل الكاملة بكل القيم القابلة للضبط من الواجهة.
     */
    public static class SequenceConfig {
        public final int likeX, likeY;
        public final int yesX, yesY;
        public final int closeX, closeY;
        public final long delayAfterLike;
        public final long delayAfterYes;
        public final long delayAfterClose;
        public final int repeat;
        public final boolean scroll;
        public final int scrollDistance;

        public SequenceConfig(int likeX, int likeY,
                              int yesX, int yesY,
                              int closeX, int closeY,
                              long delayAfterLike,
                              long delayAfterYes,
                              long delayAfterClose,
                              int repeat,
                              boolean scroll,
                              int scrollDistance) {
            this.likeX = likeX; this.likeY = likeY;
            this.yesX = yesX; this.yesY = yesY;
            this.closeX = closeX; this.closeY = closeY;
            this.delayAfterLike = delayAfterLike;
            this.delayAfterYes = delayAfterYes;
            this.delayAfterClose = delayAfterClose;
            this.repeat = repeat;
            this.scroll = scroll;
            this.scrollDistance = scrollDistance;
        }
    }
}

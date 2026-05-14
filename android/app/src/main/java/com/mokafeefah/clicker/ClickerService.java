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
 * تقوم هذه الخدمة بتنفيذ نقرات تلقائية عبر dispatchGesture على إحداثيات
 * يحددها المستخدم بنفسه. لا تستخدم أي امتيازات خاصة، ولا تتجاوز حماية أي
 * تطبيق - فقط تستخدم الإذن الرسمي BIND_ACCESSIBILITY_SERVICE.
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
     * بدء تسلسل النقرات التلقائية.
     * يعيد false إذا كانت هناك عملية تعمل حاليًا.
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
                // الخطوة 2: نقر زر نعم
                dispatchClickAt(config.yesX, config.yesY, () -> {
                    if (!running.get()) return;
                    mainHandler.postDelayed(() -> {
                        if (!running.get()) return;
                        if (config.scroll) {
                            // الخطوة 3 (اختيارية): تمرير لأسفل
                            dispatchScrollDown(() -> {
                                if (!running.get()) return;
                                mainHandler.postDelayed(this::runNextIteration, config.delayMs);
                            });
                        } else {
                            runNextIteration();
                        }
                    }, config.delayMs);
                });
            }, config.delayMs);
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

    private void dispatchScrollDown(Runnable onComplete) {
        // تمرير من منتصف الشاشة لأعلى (يكافئ التمرير لأسفل في القائمة)
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int centerX = dm.widthPixels / 2;
        int startY = (int) (dm.heightPixels * 0.75f);
        int endY = (int) (dm.heightPixels * 0.30f);

        Path path = new Path();
        path.moveTo(centerX, startY);
        path.lineTo(centerX, endY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 350);
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
     * بنية الإعدادات الممررة لبدء التسلسل.
     */
    public static class SequenceConfig {
        public final int likeX;
        public final int likeY;
        public final int yesX;
        public final int yesY;
        public final long delayMs;
        public final int repeat;
        public final boolean scroll;

        public SequenceConfig(int likeX, int likeY, int yesX, int yesY,
                              long delayMs, int repeat, boolean scroll) {
            this.likeX = likeX;
            this.likeY = likeY;
            this.yesX = yesX;
            this.yesY = yesY;
            this.delayMs = delayMs;
            this.repeat = repeat;
            this.scroll = scroll;
        }
    }
}

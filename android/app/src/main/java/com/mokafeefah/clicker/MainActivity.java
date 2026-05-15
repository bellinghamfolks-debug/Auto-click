package com.mokafeefah.clicker;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity — مكفوف بوت 2.0
 * تواصل مع الـ Service عبر ClickerService.getInstance() + StatusListener.
 */
public class MainActivity extends AppCompatActivity {

    // ===== SharedPreferences =====
    private static final String PREFS = "mokafeefah_prefs_v2";
    public static final String APP_VERSION = "2.0";
    public static final int APP_VERSION_CODE = 2;

    // v1 keys (preserved for compatibility)
    private static final String K_LIKE_TEXT = "like_text";
    private static final String K_YES_TEXT = "yes_text";
    private static final String K_CLOSE_TEXT = "close_text";
    private static final String K_TARGET_PKG = "target_pkg";
    private static final String K_PROFILE_KW = "profile_keywords";
    private static final String K_SCAN_INTERVAL = "scan_interval";
    private static final String K_POPUP_WAIT = "popup_wait";
    private static final String K_IDLE_TIMEOUT = "idle_timeout";

    // v2 keys (new)
    private static final String K_APP_VERSION = "app_version";
    private static final String K_ERROR_KEYWORDS = "error_keywords";
    private static final String K_WATCHDOG = "watchdog_sec";
    private static final String K_STOP_AT = "stop_at_count";
    private static final String K_VIBRATE = "vibrate_enabled";
    private static final String K_FAST_MODE = "fast_mode";
    private static final String K_EVENT_DRIVEN = "event_driven";

    // Defaults
    private static final String DEF_LIKE = "إهتمام";
    private static final String DEF_YES = "نعم";
    private static final String DEF_CLOSE = "إغلاق";
    private static final String DEF_TARGET_PKG = "";
    private static final String DEF_PROFILE_KW = "المؤهل التعليمي,الوزن,الطول,تاريخ الميلاد,تاريخ التسجيل,مواصفات زوجي,إبلاغ";
    private static final long DEF_SCAN_INTERVAL = 300;
    private static final long DEF_POPUP_WAIT = 2000;
    private static final long DEF_IDLE_TIMEOUT = 30; // seconds
    private static final String DEF_ERROR_KEYWORDS = "مضاف سابقاً,مضاف سابقا,لا يمكن الإضافة,لا يمكن الاضافة,تم الإرسال مسبقاً,تم الارسال مسبقا";
    private static final long DEF_WATCHDOG = 15; // seconds
    private static final int DEF_STOP_AT = 0; // 0 = unlimited
    private static final boolean DEF_VIBRATE = true;
    private static final boolean DEF_FAST_MODE = false;
    private static final boolean DEF_EVENT_DRIVEN = false; // OFF by default — safer

    // Views
    private TextView serviceStatusText, execStatusText, counterText, lastActionText;
    private EditText inputLikeText, inputYesText, inputCloseText, inputTargetPackage;
    private EditText inputProfileKeywords, inputScanInterval, inputPopupWait, inputIdleTimeout;
    private EditText inputErrorKeywords, inputWatchdog, inputStopAt;
    private Switch switchEventDriven, switchFastMode, switchVibrate;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // Stamp version
        prefs.edit().putString(K_APP_VERSION, APP_VERSION).apply();
        bindViews();
        loadSavedValues();
        wireButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServiceStatus();
        ClickerService svc = ClickerService.getInstance();
        if (svc != null) {
            svc.setStatusListener((status, count, lastAction) ->
                runOnUiThread(() -> updateLiveStatus(status, count, lastAction)));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ClickerService svc = ClickerService.getInstance();
        if (svc != null) svc.setStatusListener(null);
        saveCurrentValues();
    }

    private void bindViews() {
        serviceStatusText = findViewById(R.id.serviceStatusText);
        execStatusText = findViewById(R.id.execStatusText);
        counterText = findViewById(R.id.counterText);
        lastActionText = findViewById(R.id.lastActionText);

        inputLikeText = findViewById(R.id.inputLikeText);
        inputYesText = findViewById(R.id.inputYesText);
        inputCloseText = findViewById(R.id.inputCloseText);
        inputTargetPackage = findViewById(R.id.inputTargetPackage);
        inputProfileKeywords = findViewById(R.id.inputProfileKeywords);
        inputScanInterval = findViewById(R.id.inputScanInterval);
        inputPopupWait = findViewById(R.id.inputPopupWait);
        inputIdleTimeout = findViewById(R.id.inputIdleTimeout);

        inputErrorKeywords = findViewById(R.id.inputErrorKeywords);
        inputWatchdog = findViewById(R.id.inputWatchdog);
        inputStopAt = findViewById(R.id.inputStopAt);
        switchEventDriven = findViewById(R.id.switchEventDriven);
        switchFastMode = findViewById(R.id.switchFastMode);
        switchVibrate = findViewById(R.id.switchVibrate);
    }

    private void wireButtons() {
        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnOpenAcc = findViewById(R.id.btnOpenAccessibility);
        Button btnReset = findViewById(R.id.btnReset);
        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v -> onStopClicked());
        btnOpenAcc.setOnClickListener(v -> openAccessibilitySettings());
        btnReset.setOnClickListener(v -> resetDefaults());
    }

    private void refreshServiceStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        String text = getString(enabled ? R.string.service_status_enabled : R.string.service_status_disabled);
        serviceStatusText.setText(text);
        serviceStatusText.setContentDescription(text);
        serviceStatusText.setTextColor(getResources().getColor(enabled ? R.color.success : R.color.error));
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String me = getPackageName().toLowerCase();
        if (list != null) {
            for (AccessibilityServiceInfo info : list) {
                String id = info.getId();
                if (id != null && id.toLowerCase().contains(me)) return true;
            }
        }
        return ClickerService.isServiceRunning();
    }

    private void updateLiveStatus(String status, int count, String lastAction) {
        String prev = execStatusText.getText() == null ? "" : execStatusText.getText().toString();
        execStatusText.setText(status);
        execStatusText.setContentDescription(status);
        counterText.setText(String.valueOf(count));
        counterText.setContentDescription(getString(R.string.counter_label) + ": " + count);
        if (lastAction != null && !lastAction.isEmpty()) {
            lastActionText.setText(lastAction);
            lastActionText.setContentDescription(lastAction);
        }
        if (ClickerService.STATUS_AUTO_STOPPED.equals(status)
            && !ClickerService.STATUS_AUTO_STOPPED.equals(prev)) {
            toast(getString(R.string.msg_auto_stopped));
        }
    }

    private void onStartClicked() {
        ClickerService svc = ClickerService.getInstance();
        if (svc == null) { toast(getString(R.string.msg_service_off)); return; }
        if (svc.isExecuting()) { toast(getString(R.string.msg_already_running)); return; }

        String like = textOf(inputLikeText);
        String yes = textOf(inputYesText);
        String close = textOf(inputCloseText);
        String pkg = textOf(inputTargetPackage);
        String kwRaw = textOf(inputProfileKeywords);
        String errRaw = textOf(inputErrorKeywords);
        Long interval = parseLong(inputScanInterval);
        Long popupWait = parseLong(inputPopupWait);
        Long idleSecs = parseLong(inputIdleTimeout);
        Long watchdogSecs = parseLong(inputWatchdog);
        Long stopAt = parseLong(inputStopAt);

        if (like.isEmpty() || yes.isEmpty() || close.isEmpty()
            || interval == null || interval < 150
            || popupWait == null || popupWait < 300
            || idleSecs == null || idleSecs < 5) {
            toast(getString(R.string.msg_invalid_input));
            return;
        }
        long watchdog = (watchdogSecs == null || watchdogSecs < 5) ? DEF_WATCHDOG : watchdogSecs;
        int stopAtCount = (stopAt == null) ? 0 : Math.max(0, stopAt.intValue());

        List<String> profileKw = splitCsv(kwRaw);
        List<String> errorKw = splitCsv(errRaw);

        saveCurrentValues();

        ClickerService.BotConfig cfg = new ClickerService.BotConfig(
            like, yes, close, pkg,
            profileKw, errorKw,
            interval, popupWait, idleSecs * 1000L,
            watchdog * 1000L, stopAtCount,
            switchVibrate.isChecked(),
            switchFastMode.isChecked(),
            switchEventDriven.isChecked()
        );
        boolean ok = svc.startBot(cfg);
        toast(getString(ok ? R.string.msg_started : R.string.msg_already_running));
    }

    private void onStopClicked() {
        ClickerService svc = ClickerService.getInstance();
        if (svc == null) { toast(getString(R.string.msg_service_off)); return; }
        svc.stopBot();
        toast(getString(R.string.msg_stopped));
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(intent); } catch (Exception ignored) {}
    }

    private void resetDefaults() {
        inputLikeText.setText(DEF_LIKE);
        inputYesText.setText(DEF_YES);
        inputCloseText.setText(DEF_CLOSE);
        inputTargetPackage.setText(DEF_TARGET_PKG);
        inputProfileKeywords.setText(DEF_PROFILE_KW);
        inputScanInterval.setText(String.valueOf(DEF_SCAN_INTERVAL));
        inputPopupWait.setText(String.valueOf(DEF_POPUP_WAIT));
        inputIdleTimeout.setText(String.valueOf(DEF_IDLE_TIMEOUT));
        inputErrorKeywords.setText(DEF_ERROR_KEYWORDS);
        inputWatchdog.setText(String.valueOf(DEF_WATCHDOG));
        inputStopAt.setText(String.valueOf(DEF_STOP_AT));
        switchEventDriven.setChecked(DEF_EVENT_DRIVEN);
        switchFastMode.setChecked(DEF_FAST_MODE);
        switchVibrate.setChecked(DEF_VIBRATE);
        saveCurrentValues();
        toast(getString(R.string.msg_reset_done));
    }

    private void loadSavedValues() {
        inputLikeText.setText(prefs.getString(K_LIKE_TEXT, DEF_LIKE));
        inputYesText.setText(prefs.getString(K_YES_TEXT, DEF_YES));
        inputCloseText.setText(prefs.getString(K_CLOSE_TEXT, DEF_CLOSE));
        inputTargetPackage.setText(prefs.getString(K_TARGET_PKG, DEF_TARGET_PKG));
        inputProfileKeywords.setText(prefs.getString(K_PROFILE_KW, DEF_PROFILE_KW));
        inputScanInterval.setText(String.valueOf(prefs.getLong(K_SCAN_INTERVAL, DEF_SCAN_INTERVAL)));
        inputPopupWait.setText(String.valueOf(prefs.getLong(K_POPUP_WAIT, DEF_POPUP_WAIT)));
        inputIdleTimeout.setText(String.valueOf(prefs.getLong(K_IDLE_TIMEOUT, DEF_IDLE_TIMEOUT)));
        inputErrorKeywords.setText(prefs.getString(K_ERROR_KEYWORDS, DEF_ERROR_KEYWORDS));
        inputWatchdog.setText(String.valueOf(prefs.getLong(K_WATCHDOG, DEF_WATCHDOG)));
        inputStopAt.setText(String.valueOf(prefs.getInt(K_STOP_AT, DEF_STOP_AT)));
        switchEventDriven.setChecked(prefs.getBoolean(K_EVENT_DRIVEN, DEF_EVENT_DRIVEN));
        switchFastMode.setChecked(prefs.getBoolean(K_FAST_MODE, DEF_FAST_MODE));
        switchVibrate.setChecked(prefs.getBoolean(K_VIBRATE, DEF_VIBRATE));
    }

    private void saveCurrentValues() {
        SharedPreferences.Editor e = prefs.edit();
        e.putString(K_LIKE_TEXT, textOf(inputLikeText));
        e.putString(K_YES_TEXT, textOf(inputYesText));
        e.putString(K_CLOSE_TEXT, textOf(inputCloseText));
        e.putString(K_TARGET_PKG, textOf(inputTargetPackage));
        e.putString(K_PROFILE_KW, textOf(inputProfileKeywords));
        e.putString(K_ERROR_KEYWORDS, textOf(inputErrorKeywords));
        Long iv = parseLong(inputScanInterval); if (iv != null) e.putLong(K_SCAN_INTERVAL, iv);
        Long pw = parseLong(inputPopupWait); if (pw != null) e.putLong(K_POPUP_WAIT, pw);
        Long it = parseLong(inputIdleTimeout); if (it != null) e.putLong(K_IDLE_TIMEOUT, it);
        Long wd = parseLong(inputWatchdog); if (wd != null) e.putLong(K_WATCHDOG, wd);
        Long sa = parseLong(inputStopAt); if (sa != null) e.putInt(K_STOP_AT, sa.intValue());
        e.putBoolean(K_EVENT_DRIVEN, switchEventDriven.isChecked());
        e.putBoolean(K_FAST_MODE, switchFastMode.isChecked());
        e.putBoolean(K_VIBRATE, switchVibrate.isChecked());
        e.putString(K_APP_VERSION, APP_VERSION);
        e.apply();
    }

    private String textOf(EditText et) {
        return et == null ? "" : et.getText().toString().trim();
    }

    private Long parseLong(EditText et) {
        String s = textOf(et);
        if (TextUtils.isEmpty(s)) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split("[\u060c,]")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        View root = findViewById(android.R.id.content);
        if (root != null) root.announceForAccessibility(text);
    }
}

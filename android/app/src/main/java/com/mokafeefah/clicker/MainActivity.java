package com.mokafeefah.clicker;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * MainActivity — مكفوف بوت 2.0
 * شاشة الإعدادات والتحكم. يحفظ كل القيم في SharedPreferences
 * ويستقبل تحديثات الحالة من ClickerService عبر LocalBroadcast.
 */
public class MainActivity extends Activity {

    // ===== SharedPreferences =====
    public static final String PREFS_NAME = "mokafeefah_clicker_prefs";
    public static final String APP_VERSION = "2.0";
    public static final int APP_VERSION_CODE = 2;

    // Basic keys (kept from v1 for backward compatibility)
    public static final String KEY_LIKE_TEXT = "like_text";
    public static final String KEY_YES_TEXT = "yes_text";
    public static final String KEY_CLOSE_TEXT = "close_text";
    public static final String KEY_TARGET_PACKAGE = "target_package";
    public static final String KEY_PROFILE_KEYWORDS = "profile_keywords";
    public static final String KEY_SCAN_INTERVAL = "scan_interval_ms";
    public static final String KEY_POPUP_WAIT = "popup_wait_ms";
    public static final String KEY_IDLE_TIMEOUT = "idle_timeout_sec";

    // NEW v2.0 keys
    public static final String KEY_APP_VERSION = "app_version";
    public static final String KEY_ERROR_KEYWORDS = "error_keywords";
    public static final String KEY_WATCHDOG_SEC = "watchdog_sec";
    public static final String KEY_STOP_AT_COUNT = "stop_at_count";
    public static final String KEY_VIBRATE = "vibrate_enabled";
    public static final String KEY_FAST_MODE = "fast_mode";
    public static final String KEY_EVENT_DRIVEN = "event_driven";

    // Defaults
    public static final String DEFAULT_LIKE_TEXT = "إهتمام,اهتمام,إعجاب,اعجاب";
    public static final String DEFAULT_YES_TEXT = "نعم,موافق,تأكيد,حسناً,حسنا";
    public static final String DEFAULT_CLOSE_TEXT = "إغلاق,اغلاق,حسناً,حسنا,موافق,تم";
    public static final String DEFAULT_TARGET_PACKAGE = "";
    public static final String DEFAULT_PROFILE_KEYWORDS = "المؤهل التعليمي,الوزن,الطول,العمر,تاريخ الميلاد";
    public static final int DEFAULT_SCAN_INTERVAL = 300;
    public static final int DEFAULT_POPUP_WAIT = 1500;
    public static final int DEFAULT_IDLE_TIMEOUT = 30;

    public static final String DEFAULT_ERROR_KEYWORDS = "مضاف سابقاً,مضاف سابقا,لا يمكن الإضافة,لا يمكن الاضافة,تم الإرسال مسبقاً,تم الارسال مسبقا,تم الإعجاب مسبقاً,أعجبك مسبقاً";
    public static final int DEFAULT_WATCHDOG_SEC = 15;
    public static final int DEFAULT_STOP_AT_COUNT = 0; // 0 = unlimited
    public static final boolean DEFAULT_VIBRATE = true;
    public static final boolean DEFAULT_FAST_MODE = false;
    public static final boolean DEFAULT_EVENT_DRIVEN = true;

    // ===== Broadcast actions for UI updates =====
    public static final String ACTION_STATUS_UPDATE = "com.mokafeefah.clicker.STATUS_UPDATE";
    public static final String EXTRA_EXEC_STATUS = "exec_status";
    public static final String EXTRA_COUNTER = "counter";
    public static final String EXTRA_LAST_ACTION = "last_action";

    // ===== UI references =====
    private TextView serviceStatusText, execStatusText, counterText, lastActionText;
    private EditText inputLikeText, inputYesText, inputCloseText, inputTargetPackage;
    private EditText inputProfileKeywords, inputScanInterval, inputPopupWait, inputIdleTimeout;
    private EditText inputErrorKeywords, inputWatchdog, inputStopAt;
    private Switch switchEventDriven, switchFastMode, switchVibrate;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String exec = intent.getStringExtra(EXTRA_EXEC_STATUS);
            int counter = intent.getIntExtra(EXTRA_COUNTER, -1);
            String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
            if (exec != null && execStatusText != null) execStatusText.setText(exec);
            if (counter >= 0 && counterText != null) counterText.setText(String.valueOf(counter));
            if (lastAction != null && lastActionText != null) lastActionText.setText(lastAction);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Stamp version in SharedPreferences (so external tools/data show "2.0")
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putString(KEY_APP_VERSION, APP_VERSION).apply();

        bindViews();
        loadSettings();
        setupButtons();
        setupAutoSave();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServiceStatus();
        IntentFilter filter = new IntentFilter(ACTION_STATUS_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {}
        saveSettings();
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

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        inputLikeText.setText(prefs.getString(KEY_LIKE_TEXT, DEFAULT_LIKE_TEXT));
        inputYesText.setText(prefs.getString(KEY_YES_TEXT, DEFAULT_YES_TEXT));
        inputCloseText.setText(prefs.getString(KEY_CLOSE_TEXT, DEFAULT_CLOSE_TEXT));
        inputTargetPackage.setText(prefs.getString(KEY_TARGET_PACKAGE, DEFAULT_TARGET_PACKAGE));
        inputProfileKeywords.setText(prefs.getString(KEY_PROFILE_KEYWORDS, DEFAULT_PROFILE_KEYWORDS));
        inputScanInterval.setText(String.valueOf(prefs.getInt(KEY_SCAN_INTERVAL, DEFAULT_SCAN_INTERVAL)));
        inputPopupWait.setText(String.valueOf(prefs.getInt(KEY_POPUP_WAIT, DEFAULT_POPUP_WAIT)));
        inputIdleTimeout.setText(String.valueOf(prefs.getInt(KEY_IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT)));

        inputErrorKeywords.setText(prefs.getString(KEY_ERROR_KEYWORDS, DEFAULT_ERROR_KEYWORDS));
        inputWatchdog.setText(String.valueOf(prefs.getInt(KEY_WATCHDOG_SEC, DEFAULT_WATCHDOG_SEC)));
        inputStopAt.setText(String.valueOf(prefs.getInt(KEY_STOP_AT_COUNT, DEFAULT_STOP_AT_COUNT)));
        switchEventDriven.setChecked(prefs.getBoolean(KEY_EVENT_DRIVEN, DEFAULT_EVENT_DRIVEN));
        switchFastMode.setChecked(prefs.getBoolean(KEY_FAST_MODE, DEFAULT_FAST_MODE));
        switchVibrate.setChecked(prefs.getBoolean(KEY_VIBRATE, DEFAULT_VIBRATE));

        counterText.setText(String.valueOf(prefs.getInt("counter", 0)));
    }

    private void saveSettings() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        e.putString(KEY_LIKE_TEXT, inputLikeText.getText().toString().trim());
        e.putString(KEY_YES_TEXT, inputYesText.getText().toString().trim());
        e.putString(KEY_CLOSE_TEXT, inputCloseText.getText().toString().trim());
        e.putString(KEY_TARGET_PACKAGE, inputTargetPackage.getText().toString().trim());
        e.putString(KEY_PROFILE_KEYWORDS, inputProfileKeywords.getText().toString().trim());
        e.putInt(KEY_SCAN_INTERVAL, parseIntOr(inputScanInterval.getText().toString(), DEFAULT_SCAN_INTERVAL));
        e.putInt(KEY_POPUP_WAIT, parseIntOr(inputPopupWait.getText().toString(), DEFAULT_POPUP_WAIT));
        e.putInt(KEY_IDLE_TIMEOUT, parseIntOr(inputIdleTimeout.getText().toString(), DEFAULT_IDLE_TIMEOUT));

        e.putString(KEY_ERROR_KEYWORDS, inputErrorKeywords.getText().toString().trim());
        e.putInt(KEY_WATCHDOG_SEC, parseIntOr(inputWatchdog.getText().toString(), DEFAULT_WATCHDOG_SEC));
        e.putInt(KEY_STOP_AT_COUNT, parseIntOr(inputStopAt.getText().toString(), DEFAULT_STOP_AT_COUNT));
        e.putBoolean(KEY_EVENT_DRIVEN, switchEventDriven.isChecked());
        e.putBoolean(KEY_FAST_MODE, switchFastMode.isChecked());
        e.putBoolean(KEY_VIBRATE, switchVibrate.isChecked());

        e.putString(KEY_APP_VERSION, APP_VERSION);
        e.apply();
    }

    private void setupAutoSave() {
        // Save on every switch toggle so service reads fresh values
        switchEventDriven.setOnCheckedChangeListener((v, c) -> saveSettings());
        switchFastMode.setOnCheckedChangeListener((v, c) -> saveSettings());
        switchVibrate.setOnCheckedChangeListener((v, c) -> saveSettings());
    }

    private int parseIntOr(String s, int def) {
        try {
            int v = Integer.parseInt(s.trim());
            return Math.max(0, v);
        } catch (Exception e) {
            return def;
        }
    }

    private void setupButtons() {
        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnOpenAcc = findViewById(R.id.btnOpenAccessibility);
        Button btnReset = findViewById(R.id.btnReset);

        btnStart.setOnClickListener(v -> {
            if (!isServiceEnabled()) {
                Toast.makeText(this, R.string.msg_service_off, Toast.LENGTH_LONG).show();
                return;
            }
            if (!validateInputs()) {
                Toast.makeText(this, R.string.msg_invalid_input, Toast.LENGTH_LONG).show();
                return;
            }
            saveSettings();
            sendCommand(ClickerService.CMD_START);
            Toast.makeText(this, R.string.msg_started, Toast.LENGTH_LONG).show();
        });

        btnStop.setOnClickListener(v -> {
            sendCommand(ClickerService.CMD_STOP);
            Toast.makeText(this, R.string.msg_stopped, Toast.LENGTH_SHORT).show();
            mainHandler.postDelayed(() -> execStatusText.setText(R.string.exec_status_stopped), 200);
        });

        btnOpenAcc.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {}
        });

        btnReset.setOnClickListener(v -> {
            resetDefaults();
            loadSettings();
            saveSettings();
            Toast.makeText(this, R.string.msg_reset_done, Toast.LENGTH_SHORT).show();
        });
    }

    private void resetDefaults() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear()
            .putString(KEY_APP_VERSION, APP_VERSION)
            .apply();
    }

    private boolean validateInputs() {
        return !TextUtils.isEmpty(inputLikeText.getText().toString().trim())
            && !TextUtils.isEmpty(inputYesText.getText().toString().trim())
            && !TextUtils.isEmpty(inputCloseText.getText().toString().trim());
    }

    private void sendCommand(String cmd) {
        Intent i = new Intent(this, ClickerService.class);
        i.setAction(cmd);
        try { startService(i); } catch (Exception ignored) {}
    }

    private void refreshServiceStatus() {
        boolean enabled = isServiceEnabled();
        serviceStatusText.setText(enabled ? R.string.service_status_enabled : R.string.service_status_disabled);
        serviceStatusText.setTextColor(getResources().getColor(enabled ? R.color.success : R.color.error));
    }

    private boolean isServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String me = getPackageName();
        for (AccessibilityServiceInfo info : list) {
            String id = info.getId();
            if (id != null && id.toLowerCase().contains(me.toLowerCase())) return true;
        }
        return false;
    }
}

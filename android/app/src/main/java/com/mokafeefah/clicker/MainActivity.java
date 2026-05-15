package com.mokafeefah.clicker;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * الشاشة الرئيسية - تتيح ضبط نصوص الأزرار التي يبحث عنها البوت.
 * لا توجد إحداثيات X/Y بعد الآن - الذكاء كله مبني على تحليل شجرة الشاشة.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "mokafeefah_prefs_v2";
    private static final String K_LIKE_TEXT = "like_text";
    private static final String K_YES_TEXT = "yes_text";
    private static final String K_CLOSE_TEXT = "close_text";
    private static final String K_TARGET_PKG = "target_pkg";
    private static final String K_PROFILE_KW = "profile_keywords";
    private static final String K_SCAN_INTERVAL = "scan_interval";
    private static final String K_POPUP_WAIT = "popup_wait";
    private static final String K_IDLE_TIMEOUT = "idle_timeout";

    private static final String DEF_LIKE = "إهتمام";
    private static final String DEF_YES = "نعم";
    private static final String DEF_CLOSE = "إغلاق";
    private static final String DEF_TARGET_PKG = "";
    private static final String DEF_PROFILE_KW = "المؤهل التعليمي,الوزن,الطول,تاريخ الميلاد";
    private static final long DEF_SCAN_INTERVAL = 300L;
    private static final long DEF_POPUP_WAIT = 2000L;
    private static final long DEF_IDLE_TIMEOUT = 30L; // بالثواني في الواجهة

    private EditText inputLikeText, inputYesText, inputCloseText,
            inputTargetPackage, inputProfileKeywords, inputScanInterval,
            inputPopupWait, inputIdleTimeout;
    private TextView serviceStatusText, execStatusText, counterText, lastActionText;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        bindViews();
        loadSavedValues();
        wireButtons();
    }

    private void bindViews() {
        inputLikeText = findViewById(R.id.inputLikeText);
        inputYesText = findViewById(R.id.inputYesText);
        inputCloseText = findViewById(R.id.inputCloseText);
        inputTargetPackage = findViewById(R.id.inputTargetPackage);
        inputProfileKeywords = findViewById(R.id.inputProfileKeywords);
        inputScanInterval = findViewById(R.id.inputScanInterval);
        inputPopupWait = findViewById(R.id.inputPopupWait);
        inputIdleTimeout = findViewById(R.id.inputIdleTimeout);
        serviceStatusText = findViewById(R.id.serviceStatusText);
        execStatusText = findViewById(R.id.execStatusText);
        counterText = findViewById(R.id.counterText);
        lastActionText = findViewById(R.id.lastActionText);
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

    private void refreshServiceStatus() {
        boolean enabled = ClickerService.isServiceRunning();
        String text = enabled
                ? getString(R.string.service_status_enabled)
                : getString(R.string.service_status_disabled);
        serviceStatusText.setText(text);
        serviceStatusText.setContentDescription(text);
    }

    private void updateLiveStatus(String status, int count, String lastAction) {
        String prevStatus = execStatusText.getText() == null ? "" : execStatusText.getText().toString();
        execStatusText.setText(status);
        execStatusText.setContentDescription(status);
        counterText.setText(String.valueOf(count));
        counterText.setContentDescription(getString(R.string.counter_label) + ": " + count);
        if (lastAction != null && !lastAction.isEmpty()) {
            lastActionText.setText(lastAction);
            lastActionText.setContentDescription(lastAction);
        }
        // إن انتقلنا للتو إلى حالة "إيقاف تلقائي" أعرض رسالة وأطلق إعلانًا صوتيًا للقارئ
        if (ClickerService.STATUS_AUTO_STOPPED.equals(status)
                && !ClickerService.STATUS_AUTO_STOPPED.equals(prevStatus)) {
            toast(getString(R.string.msg_auto_stopped));
        }
    }

    private void onStartClicked() {
        ClickerService svc = ClickerService.getInstance();
        if (svc == null) {
            toast(getString(R.string.msg_service_off));
            return;
        }
        if (svc.isExecuting()) {
            toast(getString(R.string.msg_already_running));
            return;
        }

        String like = textOf(inputLikeText);
        String yes = textOf(inputYesText);
        String close = textOf(inputCloseText);
        String pkg = textOf(inputTargetPackage);
        String kwRaw = textOf(inputProfileKeywords);
        Long interval = parseLong(inputScanInterval);
        Long popupWait = parseLong(inputPopupWait);
        Long idleSecs = parseLong(inputIdleTimeout);

        if (like.isEmpty() || yes.isEmpty() || close.isEmpty()
                || interval == null || interval < 150L
                || popupWait == null || popupWait < 300L
                || idleSecs == null || idleSecs < 5L) {
            toast(getString(R.string.msg_invalid_input));
            return;
        }

        List<String> keywords = new ArrayList<>();
        if (!kwRaw.isEmpty()) {
            for (String part : kwRaw.split("[،,]")) {
                String t = part.trim();
                if (!t.isEmpty()) keywords.add(t);
            }
        }

        saveCurrentValues();
        ClickerService.BotConfig cfg = new ClickerService.BotConfig(
                like, yes, close, pkg, keywords,
                interval, popupWait, idleSecs * 1000L);
        boolean ok = svc.startBot(cfg);
        toast(ok ? getString(R.string.msg_started) : getString(R.string.msg_already_running));
    }

    private void onStopClicked() {
        ClickerService svc = ClickerService.getInstance();
        if (svc == null) {
            toast(getString(R.string.msg_service_off));
            return;
        }
        svc.stopBot();
        toast(getString(R.string.msg_stopped));
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
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
    }

    private void saveCurrentValues() {
        SharedPreferences.Editor e = prefs.edit();
        e.putString(K_LIKE_TEXT, textOf(inputLikeText));
        e.putString(K_YES_TEXT, textOf(inputYesText));
        e.putString(K_CLOSE_TEXT, textOf(inputCloseText));
        e.putString(K_TARGET_PKG, textOf(inputTargetPackage));
        e.putString(K_PROFILE_KW, textOf(inputProfileKeywords));
        Long iv = parseLong(inputScanInterval);
        if (iv != null) e.putLong(K_SCAN_INTERVAL, iv);
        Long pw = parseLong(inputPopupWait);
        if (pw != null) e.putLong(K_POPUP_WAIT, pw);
        Long it = parseLong(inputIdleTimeout);
        if (it != null) e.putLong(K_IDLE_TIMEOUT, it);
        e.apply();
    }

    private String textOf(EditText et) {
        return et == null ? "" : et.getText().toString().trim();
    }

    private Long parseLong(EditText et) {
        String s = textOf(et);
        if (TextUtils.isEmpty(s)) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException ex) { return null; }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        View root = findViewById(android.R.id.content);
        if (root != null) root.announceForAccessibility(text);
    }
}

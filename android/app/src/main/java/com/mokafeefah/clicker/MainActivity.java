package com.mokafeefah.clicker;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * الشاشة الرئيسية لتطبيق "مكفوف كليكر".
 * تتيح للمستخدم إدخال إحداثيات الأزرار، التوقيت، عدد التكرار، وإصدار الأوامر
 * (بدء، إيقاف، تجربة، فتح الإعدادات، استعادة الافتراضي).
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "mokafeefah_prefs";
    private static final String K_LIKE_X = "like_x";
    private static final String K_LIKE_Y = "like_y";
    private static final String K_YES_X = "yes_x";
    private static final String K_YES_Y = "yes_y";
    private static final String K_DELAY = "delay";
    private static final String K_REPEAT = "repeat";
    private static final String K_SCROLL = "scroll";

    // قيم افتراضية معقولة (وسط شاشة عادية)
    private static final int DEF_LIKE_X = 540;
    private static final int DEF_LIKE_Y = 1500;
    private static final int DEF_YES_X = 540;
    private static final int DEF_YES_Y = 1200;
    private static final long DEF_DELAY = 1500L;
    private static final int DEF_REPEAT = 5;
    private static final boolean DEF_SCROLL = false;

    private EditText inputLikeX, inputLikeY, inputYesX, inputYesY, inputDelay, inputRepeat;
    private CheckBox checkScroll;
    private TextView serviceStatusText, execStatusText;
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
        inputLikeX = findViewById(R.id.inputLikeX);
        inputLikeY = findViewById(R.id.inputLikeY);
        inputYesX = findViewById(R.id.inputYesX);
        inputYesY = findViewById(R.id.inputYesY);
        inputDelay = findViewById(R.id.inputDelay);
        inputRepeat = findViewById(R.id.inputRepeat);
        checkScroll = findViewById(R.id.checkScroll);
        serviceStatusText = findViewById(R.id.serviceStatusText);
        execStatusText = findViewById(R.id.execStatusText);
    }

    private void wireButtons() {
        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnTestLike = findViewById(R.id.btnTestLike);
        Button btnTestYes = findViewById(R.id.btnTestYes);
        Button btnOpenAcc = findViewById(R.id.btnOpenAccessibility);
        Button btnReset = findViewById(R.id.btnReset);

        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v -> onStopClicked());
        btnTestLike.setOnClickListener(v -> onTestClicked(true));
        btnTestYes.setOnClickListener(v -> onTestClicked(false));
        btnOpenAcc.setOnClickListener(v -> openAccessibilitySettings());
        btnReset.setOnClickListener(v -> resetDefaults());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServiceStatus();
        ClickerService svc = ClickerService.getInstance();
        if (svc != null) {
            svc.setStatusListener((status, current, total) ->
                    runOnUiThread(() -> updateExecStatus(status, current, total)));
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
        serviceStatusText.setText(enabled
                ? getString(R.string.service_status_enabled)
                : getString(R.string.service_status_disabled));
        serviceStatusText.setContentDescription(serviceStatusText.getText());
        serviceStatusText.announceForAccessibility(serviceStatusText.getText());
    }

    private void updateExecStatus(String status, int current, int total) {
        String text;
        if (ClickerService.STATUS_RUNNING.equals(status) && total > 0) {
            text = getString(R.string.exec_status_running_progress, current, total);
        } else {
            text = status;
        }
        execStatusText.setText(text);
        execStatusText.setContentDescription(text);
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
        Integer lx = parseInt(inputLikeX);
        Integer ly = parseInt(inputLikeY);
        Integer yx = parseInt(inputYesX);
        Integer yy = parseInt(inputYesY);
        Long delay = parseLong(inputDelay);
        Integer repeat = parseInt(inputRepeat);

        if (lx == null || ly == null || yx == null || yy == null
                || delay == null || repeat == null
                || lx < 0 || ly < 0 || yx < 0 || yy < 0
                || delay < 100L || repeat < 1) {
            toast(getString(R.string.msg_invalid_input));
            return;
        }

        saveCurrentValues();
        ClickerService.SequenceConfig cfg = new ClickerService.SequenceConfig(
                lx, ly, yx, yy, delay, repeat, checkScroll.isChecked());
        boolean ok = svc.startSequence(cfg);
        if (ok) toast(getString(R.string.msg_started));
        else toast(getString(R.string.msg_already_running));
    }

    private void onStopClicked() {
        ClickerService svc = ClickerService.getInstance();
        if (svc == null) {
            toast(getString(R.string.msg_service_off));
            return;
        }
        svc.stopSequence();
        toast(getString(R.string.msg_stopped));
    }

    private void onTestClicked(boolean like) {
        ClickerService svc = ClickerService.getInstance();
        if (svc == null) {
            toast(getString(R.string.msg_service_off));
            return;
        }
        Integer x = parseInt(like ? inputLikeX : inputYesX);
        Integer y = parseInt(like ? inputLikeY : inputYesY);
        if (x == null || y == null || x < 0 || y < 0) {
            toast(getString(R.string.msg_invalid_input));
            return;
        }
        boolean ok = svc.performSingleClick(x, y);
        if (ok) {
            toast(getString(like ? R.string.msg_test_like_done : R.string.msg_test_yes_done));
        } else {
            toast(getString(R.string.msg_test_failed));
        }
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void resetDefaults() {
        inputLikeX.setText(String.valueOf(DEF_LIKE_X));
        inputLikeY.setText(String.valueOf(DEF_LIKE_Y));
        inputYesX.setText(String.valueOf(DEF_YES_X));
        inputYesY.setText(String.valueOf(DEF_YES_Y));
        inputDelay.setText(String.valueOf(DEF_DELAY));
        inputRepeat.setText(String.valueOf(DEF_REPEAT));
        checkScroll.setChecked(DEF_SCROLL);
        saveCurrentValues();
        toast(getString(R.string.msg_reset_done));
    }

    private void loadSavedValues() {
        inputLikeX.setText(String.valueOf(prefs.getInt(K_LIKE_X, DEF_LIKE_X)));
        inputLikeY.setText(String.valueOf(prefs.getInt(K_LIKE_Y, DEF_LIKE_Y)));
        inputYesX.setText(String.valueOf(prefs.getInt(K_YES_X, DEF_YES_X)));
        inputYesY.setText(String.valueOf(prefs.getInt(K_YES_Y, DEF_YES_Y)));
        inputDelay.setText(String.valueOf(prefs.getLong(K_DELAY, DEF_DELAY)));
        inputRepeat.setText(String.valueOf(prefs.getInt(K_REPEAT, DEF_REPEAT)));
        checkScroll.setChecked(prefs.getBoolean(K_SCROLL, DEF_SCROLL));
    }

    private void saveCurrentValues() {
        SharedPreferences.Editor e = prefs.edit();
        Integer v;
        if ((v = parseInt(inputLikeX)) != null) e.putInt(K_LIKE_X, v);
        if ((v = parseInt(inputLikeY)) != null) e.putInt(K_LIKE_Y, v);
        if ((v = parseInt(inputYesX)) != null) e.putInt(K_YES_X, v);
        if ((v = parseInt(inputYesY)) != null) e.putInt(K_YES_Y, v);
        Long d = parseLong(inputDelay);
        if (d != null) e.putLong(K_DELAY, d);
        if ((v = parseInt(inputRepeat)) != null) e.putInt(K_REPEAT, v);
        e.putBoolean(K_SCROLL, checkScroll.isChecked());
        e.apply();
    }

    private Integer parseInt(EditText et) {
        String s = et.getText().toString().trim();
        if (TextUtils.isEmpty(s)) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return null; }
    }

    private Long parseLong(EditText et) {
        String s = et.getText().toString().trim();
        if (TextUtils.isEmpty(s)) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException ex) { return null; }
    }

    private void toast(String text) {
        Toast t = Toast.makeText(this, text, Toast.LENGTH_LONG);
        t.show();
        // إعلان مباشر لـ TalkBack حتى لو لم تظهر رسالة Toast على الشاشة
        View root = findViewById(android.R.id.content);
        if (root != null) root.announceForAccessibility(text);
    }
}

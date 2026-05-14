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
 * تتيح للمستخدم إدخال إحداثيات الأزرار (إعجاب، نعم، إغلاق)،
 * 3 مدد انتظار منفصلة، مسافة التمرير، عدد التكرار، وإصدار الأوامر.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "mokafeefah_prefs";
    // مفاتيح التخزين
    private static final String K_LIKE_X = "like_x";
    private static final String K_LIKE_Y = "like_y";
    private static final String K_YES_X = "yes_x";
    private static final String K_YES_Y = "yes_y";
    private static final String K_CLOSE_X = "close_x";
    private static final String K_CLOSE_Y = "close_y";
    private static final String K_DELAY_LIKE = "delay_like";
    private static final String K_DELAY_YES = "delay_yes";
    private static final String K_DELAY_CLOSE = "delay_close";
    private static final String K_REPEAT = "repeat";
    private static final String K_SCROLL = "scroll";
    private static final String K_SCROLL_DIST = "scroll_distance";

    // قيم افتراضية - مناسبة لشاشة 1080x2400 تقريبًا
    private static final int DEF_LIKE_X = 540;
    private static final int DEF_LIKE_Y = 1500;
    private static final int DEF_YES_X = 540;
    private static final int DEF_YES_Y = 1200;
    private static final int DEF_CLOSE_X = 540;
    private static final int DEF_CLOSE_Y = 1800;
    private static final long DEF_DELAY_LIKE = 1000L;   // 1 ثانية بعد الإعجاب
    private static final long DEF_DELAY_YES = 2000L;    // 2 ثانية بعد نعم
    private static final long DEF_DELAY_CLOSE = 1000L;  // 1 ثانية بعد الإغلاق
    private static final int DEF_REPEAT = 5;
    private static final boolean DEF_SCROLL = false;
    private static final int DEF_SCROLL_DIST = 600;

    private EditText inputLikeX, inputLikeY, inputYesX, inputYesY,
            inputCloseX, inputCloseY,
            inputDelayLike, inputDelayYes, inputDelayClose,
            inputRepeat, inputScrollDistance;
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
        inputCloseX = findViewById(R.id.inputCloseX);
        inputCloseY = findViewById(R.id.inputCloseY);
        inputDelayLike = findViewById(R.id.inputDelayLike);
        inputDelayYes = findViewById(R.id.inputDelayYes);
        inputDelayClose = findViewById(R.id.inputDelayClose);
        inputRepeat = findViewById(R.id.inputRepeat);
        inputScrollDistance = findViewById(R.id.inputScrollDistance);
        checkScroll = findViewById(R.id.checkScroll);
        serviceStatusText = findViewById(R.id.serviceStatusText);
        execStatusText = findViewById(R.id.execStatusText);
    }

    private void wireButtons() {
        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnTestLike = findViewById(R.id.btnTestLike);
        Button btnTestYes = findViewById(R.id.btnTestYes);
        Button btnTestClose = findViewById(R.id.btnTestClose);
        Button btnOpenAcc = findViewById(R.id.btnOpenAccessibility);
        Button btnReset = findViewById(R.id.btnReset);

        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v -> onStopClicked());
        btnTestLike.setOnClickListener(v -> onTestClicked(TestTarget.LIKE));
        btnTestYes.setOnClickListener(v -> onTestClicked(TestTarget.YES));
        btnTestClose.setOnClickListener(v -> onTestClicked(TestTarget.CLOSE));
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
        String text = enabled
                ? getString(R.string.service_status_enabled)
                : getString(R.string.service_status_disabled);
        serviceStatusText.setText(text);
        serviceStatusText.setContentDescription(text);
        serviceStatusText.announceForAccessibility(text);
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
        Integer cx = parseInt(inputCloseX);
        Integer cy = parseInt(inputCloseY);
        Long dLike = parseLong(inputDelayLike);
        Long dYes = parseLong(inputDelayYes);
        Long dClose = parseLong(inputDelayClose);
        Integer repeat = parseInt(inputRepeat);
        Integer scrollDist = parseInt(inputScrollDistance);

        if (lx == null || ly == null || yx == null || yy == null
                || cx == null || cy == null
                || dLike == null || dYes == null || dClose == null
                || repeat == null || scrollDist == null
                || lx < 0 || ly < 0 || yx < 0 || yy < 0 || cx < 0 || cy < 0
                || dLike < 100L || dYes < 100L || dClose < 100L
                || repeat < 1 || scrollDist < 50) {
            toast(getString(R.string.msg_invalid_input));
            return;
        }

        saveCurrentValues();
        ClickerService.SequenceConfig cfg = new ClickerService.SequenceConfig(
                lx, ly, yx, yy, cx, cy,
                dLike, dYes, dClose,
                repeat, checkScroll.isChecked(), scrollDist);
        boolean ok = svc.startSequence(cfg);
        toast(ok ? getString(R.string.msg_started) : getString(R.string.msg_already_running));
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

    private enum TestTarget { LIKE, YES, CLOSE }

    private void onTestClicked(TestTarget target) {
        ClickerService svc = ClickerService.getInstance();
        if (svc == null) {
            toast(getString(R.string.msg_service_off));
            return;
        }
        Integer x, y;
        int doneMsg;
        switch (target) {
            case LIKE:
                x = parseInt(inputLikeX); y = parseInt(inputLikeY);
                doneMsg = R.string.msg_test_like_done; break;
            case YES:
                x = parseInt(inputYesX); y = parseInt(inputYesY);
                doneMsg = R.string.msg_test_yes_done; break;
            case CLOSE:
            default:
                x = parseInt(inputCloseX); y = parseInt(inputCloseY);
                doneMsg = R.string.msg_test_close_done; break;
        }
        if (x == null || y == null || x < 0 || y < 0) {
            toast(getString(R.string.msg_invalid_input));
            return;
        }
        boolean ok = svc.performSingleClick(x, y);
        toast(getString(ok ? doneMsg : R.string.msg_test_failed));
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
        inputCloseX.setText(String.valueOf(DEF_CLOSE_X));
        inputCloseY.setText(String.valueOf(DEF_CLOSE_Y));
        inputDelayLike.setText(String.valueOf(DEF_DELAY_LIKE));
        inputDelayYes.setText(String.valueOf(DEF_DELAY_YES));
        inputDelayClose.setText(String.valueOf(DEF_DELAY_CLOSE));
        inputRepeat.setText(String.valueOf(DEF_REPEAT));
        inputScrollDistance.setText(String.valueOf(DEF_SCROLL_DIST));
        checkScroll.setChecked(DEF_SCROLL);
        saveCurrentValues();
        toast(getString(R.string.msg_reset_done));
    }

    private void loadSavedValues() {
        inputLikeX.setText(String.valueOf(prefs.getInt(K_LIKE_X, DEF_LIKE_X)));
        inputLikeY.setText(String.valueOf(prefs.getInt(K_LIKE_Y, DEF_LIKE_Y)));
        inputYesX.setText(String.valueOf(prefs.getInt(K_YES_X, DEF_YES_X)));
        inputYesY.setText(String.valueOf(prefs.getInt(K_YES_Y, DEF_YES_Y)));
        inputCloseX.setText(String.valueOf(prefs.getInt(K_CLOSE_X, DEF_CLOSE_X)));
        inputCloseY.setText(String.valueOf(prefs.getInt(K_CLOSE_Y, DEF_CLOSE_Y)));
        inputDelayLike.setText(String.valueOf(prefs.getLong(K_DELAY_LIKE, DEF_DELAY_LIKE)));
        inputDelayYes.setText(String.valueOf(prefs.getLong(K_DELAY_YES, DEF_DELAY_YES)));
        inputDelayClose.setText(String.valueOf(prefs.getLong(K_DELAY_CLOSE, DEF_DELAY_CLOSE)));
        inputRepeat.setText(String.valueOf(prefs.getInt(K_REPEAT, DEF_REPEAT)));
        inputScrollDistance.setText(String.valueOf(prefs.getInt(K_SCROLL_DIST, DEF_SCROLL_DIST)));
        checkScroll.setChecked(prefs.getBoolean(K_SCROLL, DEF_SCROLL));
    }

    private void saveCurrentValues() {
        SharedPreferences.Editor e = prefs.edit();
        Integer v; Long d;
        if ((v = parseInt(inputLikeX)) != null) e.putInt(K_LIKE_X, v);
        if ((v = parseInt(inputLikeY)) != null) e.putInt(K_LIKE_Y, v);
        if ((v = parseInt(inputYesX)) != null) e.putInt(K_YES_X, v);
        if ((v = parseInt(inputYesY)) != null) e.putInt(K_YES_Y, v);
        if ((v = parseInt(inputCloseX)) != null) e.putInt(K_CLOSE_X, v);
        if ((v = parseInt(inputCloseY)) != null) e.putInt(K_CLOSE_Y, v);
        if ((d = parseLong(inputDelayLike)) != null) e.putLong(K_DELAY_LIKE, d);
        if ((d = parseLong(inputDelayYes)) != null) e.putLong(K_DELAY_YES, d);
        if ((d = parseLong(inputDelayClose)) != null) e.putLong(K_DELAY_CLOSE, d);
        if ((v = parseInt(inputRepeat)) != null) e.putInt(K_REPEAT, v);
        if ((v = parseInt(inputScrollDistance)) != null) e.putInt(K_SCROLL_DIST, v);
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
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        View root = findViewById(android.R.id.content);
        if (root != null) root.announceForAccessibility(text);
    }
}

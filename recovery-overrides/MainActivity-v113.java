package com.mokafeefah.clicker;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal recovery UI. The engine does not act until it recognizes the
 * target screen, so Start is safe even while the user is switching apps.
 */
public class MainActivity extends AppCompatActivity {
    private static final String[] PROFILE_KEYWORDS = {
            "المؤهل التعليمي", "الوزن", "الطول", "تاريخ الميلاد",
            "تاريخ التسجيل", "مواصفات زوجي", "إبلاغ"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            SavedCoordinatesDb coords = new SavedCoordinatesDb(getApplicationContext());
            coords.seedIfEmpty();
            coords.seedMissing();
            coords.migrateThreeDotsCoord();
        } catch (Throwable ignored) {}

        Button start = findViewById(R.id.btnStart);
        Button accessibility = findViewById(R.id.btnOpenAccessibility);
        Button diagnostic = findViewById(R.id.btnShareDiagnostic);

        start.setOnClickListener(v -> startAutomation());
        accessibility.setOnClickListener(v -> openAccessibilitySettings());
        diagnostic.setOnClickListener(v -> shareDiagnostic());
    }

    private void startAutomation() {
        ClickerService svc = ClickerService.getInstance();
        if (svc == null) {
            toast("فعّل خدمة إمكانية الوصول أولاً");
            openAccessibilitySettings();
            return;
        }
        if (svc.isExecuting()) {
            toast("التشغيل مستمر بالفعل");
            return;
        }

        List<String> profileKeywords = new ArrayList<>(Arrays.asList(PROFILE_KEYWORDS));
        ClickerService.BotConfig cfg = new ClickerService.BotConfig(
                "إهتمام", "نعم", "إغلاق", "", profileKeywords,
                250L, 1500L, 180_000L,
                true, false, true, "three_dots");

        if (svc.startBot(cfg)) {
            toast("بدأ التشغيل. افتح شاشة الأعضاء؛ لن يتحرك التطبيق قبل التعرف عليها");
        } else {
            toast("تعذر بدء التشغيل");
        }
    }

    private void openAccessibilitySettings() {
        Intent i = new Intent("android.settings.ACCESSIBILITY_SETTINGS");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Throwable t) {
            toast("تعذر فتح إعدادات إمكانية الوصول");
        }
    }

    private void shareDiagnostic() {
        ClickerService svc = ClickerService.getInstance();
        String path = "";
        if (svc != null) path = svc.captureDiagnosticNow();

        File file = null;
        if (path != null && !path.isEmpty()) file = new File(path);
        if (file == null || !file.exists()) file = newestDiagnosticFile();
        if (file == null || !file.exists()) {
            toast("لا يوجد ملف تشخيص بعد");
            return;
        }

        try {
            android.net.Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, file.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "مشاركة ملف التشخيص"));
        } catch (Throwable t) {
            toast("تعذر مشاركة ملف التشخيص: " + String.valueOf(t.getMessage()));
        }
    }

    private File newestDiagnosticFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        File newest = null;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.startsWith("dump_") && !name.startsWith("mokafeefah_")) continue;
            if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
        }
        return newest;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        android.view.View root = findViewById(android.R.id.content);
        if (root != null) root.announceForAccessibility(text);
    }
}

package com.baseer.ai;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * الشاشة الرئيسية لتطبيق بصير AI.
 * 4 ميزات أساسية: وصف المشهد، قراءة المستندات، اسأل بصير، المترجم.
 * كل ميزة تعمل عبر التقاط صورة (أو نص) -> إرسال للخادم -> قراءة النتيجة بالـ TTS.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "baseer_prefs";
    private static final String K_BACKEND_URL = "backend_url";
    private static final String K_RATE = "speech_rate";
    private static final String DEFAULT_BACKEND = "https://mobile-dev-184.preview.emergentagent.com";

    private static final int FLOW_DESCRIBE = 1;
    private static final int FLOW_READ = 2;
    private static final int FLOW_ASK = 3;

    private TextView statusText, resultText;
    private SharedPreferences prefs;
    private TtsHelper tts;
    private SpeechHelper stt;
    private ApiClient api;
    private String lastResult = "";
    private int currentFlow = 0;
    private String pendingQuestion = "";

    private ActivityResultLauncher<Intent> cameraLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        statusText = findViewById(R.id.statusText);
        resultText = findViewById(R.id.resultText);

        api = new ApiClient(prefs.getString(K_BACKEND_URL, DEFAULT_BACKEND));
        stt = new SpeechHelper(this);

        tts = new TtsHelper(this, arOk -> {
            tts.setRate(prefs.getFloat(K_RATE, 1.0f));
            announce(getString(R.string.welcome_announcement));
        });

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        setStatus(getString(R.string.err_capture), true);
                        return;
                    }
                    Bundle extras = result.getData().getExtras();
                    if (extras == null) {
                        setStatus(getString(R.string.err_capture), true);
                        return;
                    }
                    Bitmap bmp = (Bitmap) extras.get("data");
                    if (bmp == null) {
                        setStatus(getString(R.string.err_capture), true);
                        return;
                    }
                    handleCapturedImage(bmp);
                });

        wireButtons();
        requestRuntimePermissions();
    }

    private void wireButtons() {
        findViewById(R.id.btnDescribe).setOnClickListener(v -> startFlow(FLOW_DESCRIBE));
        findViewById(R.id.btnRead).setOnClickListener(v -> startFlow(FLOW_READ));
        findViewById(R.id.btnAsk).setOnClickListener(v -> startFlow(FLOW_ASK));
        findViewById(R.id.btnTranslate).setOnClickListener(v -> showTranslateDialog());
        findViewById(R.id.btnRepeat).setOnClickListener(v -> {
            if (!lastResult.isEmpty()) tts.speak(lastResult);
        });
        findViewById(R.id.btnStopSpeech).setOnClickListener(v -> tts.stop());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsDialog());
    }

    private void requestRuntimePermissions() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        ActivityCompat.requestPermissions(this, perms, 100);
    }

    private void startFlow(int flow) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.err_no_camera));
            requestRuntimePermissions();
            return;
        }
        currentFlow = flow;
        if (flow == FLOW_ASK) {
            // اطلب السؤال صوتيًا أولًا ثم افتح الكاميرا
            askVoiceQuestionThenCapture();
        } else {
            openCamera();
        }
    }

    private void openCamera() {
        setStatus(getString(R.string.status_capturing), false);
        try {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cameraLauncher.launch(intent);
        } catch (Exception e) {
            setStatus(getString(R.string.err_capture), true);
        }
    }

    private void askVoiceQuestionThenCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.err_no_mic));
            requestRuntimePermissions();
            return;
        }
        if (!SpeechHelper.isAvailable(this)) {
            toast(getString(R.string.err_no_stt));
            return;
        }
        setStatus(getString(R.string.status_listening), false);
        announce(getString(R.string.dlg_ask_prompt));
        stt.start(new SpeechHelper.Listener() {
            @Override
            public void onResult(String text) {
                pendingQuestion = text;
                openCamera();
            }
            @Override
            public void onError(String message) {
                setStatus("لم نسمع سؤالك - حاول مرة أخرى", true);
            }
        });
    }

    private void handleCapturedImage(Bitmap bmp) {
        setStatus(getString(R.string.status_processing), false);
        String b64 = bitmapToBase64(bmp);

        switch (currentFlow) {
            case FLOW_DESCRIBE:
                api.describeScene(b64, "detailed", apiCallback("description"));
                break;
            case FLOW_READ:
                api.ocrAnalyze(b64, "summary", apiCallback("result"));
                break;
            case FLOW_ASK:
                if (pendingQuestion == null || pendingQuestion.isEmpty()) {
                    pendingQuestion = "ما الذي تراه في هذه الصورة؟";
                }
                api.ask(b64, pendingQuestion, apiCallback("answer"));
                pendingQuestion = "";
                break;
        }
    }

    private ApiClient.Cb apiCallback(String resultField) {
        return new ApiClient.Cb() {
            @Override
            public void onSuccess(JSONObject data) {
                final String text = data.optString(resultField,
                        data.optString("translation", "")).trim();
                runOnUiThread(() -> {
                    setStatus(getString(R.string.status_done), false);
                    if (text.isEmpty()) {
                        resultText.setText("لم نحصل على نتيجة - حاول مرة أخرى");
                        return;
                    }
                    lastResult = text;
                    resultText.setText(text);
                    tts.speak(text);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setStatus(getString(R.string.err_server), true);
                    resultText.setText("خطأ: " + message);
                    tts.speak(getString(R.string.err_server));
                });
            }
        };
    }

    private void showTranslateDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        final EditText input = new EditText(this);
        input.setHint(R.string.dlg_translate_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setContentDescription(getString(R.string.dlg_translate_hint));
        container.addView(input);

        final String[] dirs = {"ar_to_en", "en_to_ar"};
        final String[] dirLabels = {getString(R.string.dir_ar_en), getString(R.string.dir_en_ar)};
        final int[] dirIndex = {0};

        Button dirBtn = new Button(this);
        dirBtn.setText(dirLabels[0]);
        dirBtn.setContentDescription(dirLabels[0]);
        dirBtn.setOnClickListener(v -> {
            dirIndex[0] = (dirIndex[0] + 1) % 2;
            dirBtn.setText(dirLabels[dirIndex[0]]);
            dirBtn.setContentDescription(dirLabels[dirIndex[0]]);
        });
        container.addView(dirBtn);

        final String[] tones = {"natural", "formal", "casual", "dating", "polite", "brief"};
        final String[] toneLabels = {
                getString(R.string.tone_natural), getString(R.string.tone_formal),
                getString(R.string.tone_casual), getString(R.string.tone_dating),
                getString(R.string.tone_polite), getString(R.string.tone_brief)
        };
        final int[] toneIndex = {0};

        Button toneBtn = new Button(this);
        toneBtn.setText(toneLabels[0]);
        toneBtn.setContentDescription(toneLabels[0]);
        toneBtn.setOnClickListener(v -> {
            toneIndex[0] = (toneIndex[0] + 1) % tones.length;
            toneBtn.setText(toneLabels[toneIndex[0]]);
            toneBtn.setContentDescription(toneLabels[toneIndex[0]]);
        });
        container.addView(toneBtn);

        Button micBtn = new Button(this);
        micBtn.setText(R.string.btn_mic);
        micBtn.setContentDescription(getString(R.string.btn_mic));
        micBtn.setOnClickListener(v -> {
            if (!SpeechHelper.isAvailable(this)) {
                toast(getString(R.string.err_no_stt));
                return;
            }
            announce(getString(R.string.dlg_ask_prompt));
            stt.start(new SpeechHelper.Listener() {
                @Override public void onResult(String text) {
                    runOnUiThread(() -> input.setText(text));
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> toast(message));
                }
            });
        });
        container.addView(micBtn);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_translate_title)
                .setView(container)
                .setPositiveButton(R.string.btn_do_translate, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) return;
                    setStatus(getString(R.string.status_processing), false);
                    api.translate(text, dirs[dirIndex[0]], tones[toneIndex[0]],
                            new ApiClient.Cb() {
                        @Override public void onSuccess(JSONObject data) {
                            final String translation = data.optString("translation", "").trim();
                            final String explanation = data.optString("explanation", "").trim();
                            runOnUiThread(() -> {
                                setStatus(getString(R.string.status_done), false);
                                StringBuilder sb = new StringBuilder(translation);
                                if (!explanation.isEmpty()) {
                                    sb.append("\n\nشرح النبرة: ").append(explanation);
                                }
                                lastResult = sb.toString();
                                resultText.setText(lastResult);
                                tts.speak(translation);
                            });
                        }
                        @Override public void onError(String message) {
                            runOnUiThread(() -> {
                                setStatus(getString(R.string.err_server), true);
                                resultText.setText("خطأ: " + message);
                            });
                        }
                    });
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showSettingsDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        TextView label1 = new TextView(this);
        label1.setText(R.string.label_backend_url);
        container.addView(label1);

        final EditText urlInput = new EditText(this);
        urlInput.setText(prefs.getString(K_BACKEND_URL, DEFAULT_BACKEND));
        urlInput.setHint(R.string.hint_backend_url);
        urlInput.setContentDescription(getString(R.string.label_backend_url));
        container.addView(urlInput);

        TextView label2 = new TextView(this);
        label2.setText(R.string.label_speech_rate);
        container.addView(label2);

        final EditText rateInput = new EditText(this);
        rateInput.setText(String.valueOf(prefs.getFloat(K_RATE, 1.0f)));
        rateInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        rateInput.setContentDescription(getString(R.string.label_speech_rate));
        container.addView(rateInput);

        Button testBtn = new Button(this);
        testBtn.setText(R.string.btn_test_connection);
        testBtn.setContentDescription(getString(R.string.btn_test_connection));
        testBtn.setOnClickListener(v -> {
            api = new ApiClient(urlInput.getText().toString().trim());
            api.health(new ApiClient.Cb() {
                @Override public void onSuccess(JSONObject data) {
                    runOnUiThread(() -> toast(getString(R.string.msg_connected)));
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> toast(getString(R.string.err_server) + ": " + message));
                }
            });
        });
        container.addView(testBtn);

        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_settings)
                .setView(container)
                .setPositiveButton(R.string.btn_save, (d, w) -> {
                    String url = urlInput.getText().toString().trim();
                    if (!url.isEmpty()) {
                        prefs.edit().putString(K_BACKEND_URL, url).apply();
                        api = new ApiClient(url);
                    }
                    try {
                        float rate = Float.parseFloat(rateInput.getText().toString().trim());
                        prefs.edit().putFloat(K_RATE, rate).apply();
                        tts.setRate(rate);
                    } catch (NumberFormatException ignored) {}
                    toast(getString(R.string.msg_saved));
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ===== Helpers =====

    private String bitmapToBase64(Bitmap bmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // ضغط الصورة لتقليل حجم الـ payload
        Bitmap toEncode = scaleDown(bmp, 1600);
        toEncode.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return ApiClient.bitmapBytesToBase64(out.toByteArray());
    }

    private Bitmap scaleDown(Bitmap src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        int maxSide = Math.max(w, h);
        if (maxSide <= maxDim) return src;
        float scale = (float) maxDim / (float) maxSide;
        int nw = Math.round(w * scale);
        int nh = Math.round(h * scale);
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private void setStatus(String text, boolean isError) {
        statusText.setText(text);
        statusText.setContentDescription(text);
    }

    private void announce(String text) {
        statusText.announceForAccessibility(text);
        if (tts != null && tts.isInitialized()) tts.speak(text);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        View root = findViewById(android.R.id.content);
        if (root != null) root.announceForAccessibility(text);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (stt != null) stt.destroy();
        super.onDestroy();
    }
}

package com.baseer.ai;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * مغلف بسيط لـ Android TextToSpeech بصوت عربي.
 * يقرأ النصوص الناتجة من بصير بشكل تلقائي ومريح للمكفوف.
 */
public class TtsHelper {

    public interface Ready { void onReady(boolean arabicSupported); }

    private final TextToSpeech tts;
    private boolean initialized = false;
    private float speechRate = 1.0f;

    public TtsHelper(Context ctx, final Ready ready) {
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("ar"));
                boolean arOk = !(result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED);
                if (!arOk) {
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(speechRate);
                initialized = true;
                if (ready != null) ready.onReady(arOk);
            } else {
                initialized = false;
                if (ready != null) ready.onReady(false);
            }
        });
    }

    public void speak(String text) {
        if (!initialized || text == null || text.isEmpty()) return;
        tts.stop();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "baseer-utterance");
    }

    public void stop() {
        if (initialized) tts.stop();
    }

    public void setRate(float rate) {
        this.speechRate = Math.max(0.5f, Math.min(2.0f, rate));
        if (initialized) tts.setSpeechRate(speechRate);
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    public boolean isInitialized() { return initialized; }
}

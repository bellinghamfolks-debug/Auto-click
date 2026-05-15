package com.baseer.ai;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * مغلف بسيط لـ SpeechRecognizer لإلتقاط أسئلة المستخدم الصوتية بالعربية.
 */
public class SpeechHelper {

    public interface Listener {
        void onResult(String text);
        void onError(String message);
    }

    private final Context context;
    private SpeechRecognizer recognizer;
    private Listener listener;

    public SpeechHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    public static boolean isAvailable(Context ctx) {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    public void start(Listener l) {
        this.listener = l;
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                if (listener != null) listener.onError("STT_ERR_" + error);
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty() && listener != null) {
                    listener.onResult(matches.get(0));
                } else if (listener != null) {
                    listener.onError("لم يتم التعرف على أي كلام");
                }
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        recognizer.startListening(intent);
    }

    public void stop() {
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Throwable ignored) {}
        }
    }

    public void destroy() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
    }
}

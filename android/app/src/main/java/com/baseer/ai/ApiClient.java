package com.baseer.ai;

import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * عميل HTTP يستدعي خادم بصير. كل العمليات على Thread خلفي
 * والاستدعاء يعود للـ Main thread عبر Runnable.
 */
public class ApiClient {

    public interface Cb {
        void onSuccess(JSONObject data);
        void onError(String message);
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final String baseUrl;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public void describeScene(String imageBase64, String mode, Cb cb) {
        try {
            JSONObject body = new JSONObject();
            body.put("image_base64", imageBase64);
            body.put("mode", mode == null ? "quick" : mode);
            post("/api/baseer/describe-scene", body, cb);
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    public void ocrAnalyze(String imageBase64, String task, Cb cb) {
        try {
            JSONObject body = new JSONObject();
            body.put("image_base64", imageBase64);
            body.put("task", task == null ? "summary" : task);
            post("/api/baseer/ocr-analyze", body, cb);
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    public void ask(String imageBase64, String question, Cb cb) {
        try {
            JSONObject body = new JSONObject();
            body.put("image_base64", imageBase64);
            body.put("question", question == null ? "" : question);
            post("/api/baseer/ask", body, cb);
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    public void translate(String text, String direction, String tone, Cb cb) {
        try {
            JSONObject body = new JSONObject();
            body.put("text", text == null ? "" : text);
            body.put("direction", direction == null ? "ar_to_en" : direction);
            body.put("tone", tone == null ? "natural" : tone);
            post("/api/baseer/translate", body, cb);
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    public void shopping(String imageBase64, String allergens, Cb cb) {
        try {
            JSONObject body = new JSONObject();
            body.put("image_base64", imageBase64);
            body.put("allergens", allergens == null ? "" : allergens);
            post("/api/baseer/shopping", body, cb);
        } catch (Exception e) {
            cb.onError(e.getMessage());
        }
    }

    public void health(Cb cb) {
        Request request = new Request.Builder().url(baseUrl + "/api/health").get().build();
        client.newCall(request).enqueue(wrap(cb));
    }

    private void post(String path, JSONObject body, Cb cb) {
        Request request = new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        client.newCall(request).enqueue(wrap(cb));
    }

    private Callback wrap(Cb cb) {
        return new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                cb.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response r = response) {
                    String body = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        cb.onError("HTTP " + r.code() + " - " + body);
                        return;
                    }
                    JSONObject json = new JSONObject(body);
                    cb.onSuccess(json);
                } catch (Exception e) {
                    cb.onError(e.getMessage());
                }
            }
        };
    }

    public static String bitmapBytesToBase64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}

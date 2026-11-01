package com.voiceswipe.assistant.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Continuous offline Chinese command recognition using the bundled Vosk model. */
public final class OfflineSpeechEngine {
    public interface Callback {
        void onStatus(String status);

        void onReady();

        void onPartialResult(String text);

        void onResult(String text);

        void onError(String message);
    }

    private static final String TAG = "OfflineSpeechEngine";
    private static final String MODEL_ASSET = "vosk-model-small-cn-0.22";
    private static final String MODEL_DIRECTORY = "vosk-model-cn";
    private static final float SAMPLE_RATE = 16000.0f;

    private final Context context;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private ExecutorService executor;
    private volatile boolean running;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;

    public OfflineSpeechEngine(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            executor = Executors.newSingleThreadExecutor();
        }
        postStatus("正在准备离线识别");
        executor.execute(this::initializeAndStart);
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
        releaseResources();
    }

    private void initializeAndStart() {
        try {
            if (!running) {
                return;
            }
            String modelPath = StorageService.sync(context, MODEL_ASSET, MODEL_DIRECTORY);
            if (!running) {
                return;
            }
            Model loadedModel = new Model(modelPath);
            Recognizer loadedRecognizer = new Recognizer(loadedModel, SAMPLE_RATE);
            synchronized (lock) {
                if (!running) {
                    loadedRecognizer.close();
                    loadedModel.close();
                    return;
                }
                model = loadedModel;
                recognizer = loadedRecognizer;
            }
            if (!running) {
                return;
            }
            mainHandler.post(this::startSpeechService);
        } catch (IOException | RuntimeException exception) {
            Log.e(TAG, "Failed to initialize offline model", exception);
            fail("离线识别初始化失败");
        }
    }

    private void startSpeechService() {
        if (!running) {
            return;
        }
        Recognizer activeRecognizer;
        synchronized (lock) {
            activeRecognizer = recognizer;
        }
        if (activeRecognizer == null) {
            fail("离线识别器未就绪");
            return;
        }
        try {
            SpeechService service = new SpeechService(activeRecognizer, SAMPLE_RATE);
            if (!service.startListening(engineListener)) {
                service.shutdown();
                fail("离线录音启动失败");
                return;
            }
            synchronized (lock) {
                if (!running) {
                    service.stop();
                    service.shutdown();
                    return;
                }
                speechService = service;
            }
            Log.i(TAG, "Offline speech engine ready");
            postStatus("离线识别已就绪");
            mainHandler.post(callback::onReady);
        } catch (IOException | RuntimeException exception) {
            Log.e(TAG, "Failed to start offline SpeechService", exception);
            fail("离线识别启动失败");
        }
    }

    private void fail(String message) {
        synchronized (lock) {
            running = false;
        }
        releaseResources();
        mainHandler.post(() -> callback.onError(message));
    }

    private void releaseResources() {
        SpeechService oldService;
        Recognizer oldRecognizer;
        Model oldModel;
        synchronized (lock) {
            oldService = speechService;
            oldRecognizer = recognizer;
            oldModel = model;
            speechService = null;
            recognizer = null;
            model = null;
        }
        if (oldService != null) {
            try {
                oldService.stop();
                oldService.shutdown();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Failed to stop Vosk SpeechService", exception);
            }
        }
        if (oldRecognizer != null) {
            try {
                oldRecognizer.close();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Failed to close Vosk Recognizer", exception);
            }
        }
        if (oldModel != null) {
            try {
                oldModel.close();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Failed to close Vosk Model", exception);
            }
        }
    }

    private void postStatus(String status) {
        mainHandler.post(() -> {
            if (running) {
                callback.onStatus(status);
            }
        });
    }

    private void handleJson(String json, boolean partial) {
        if (!running || json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject object = new JSONObject(json);
            String text = object.optString(partial ? "partial" : "text", "").trim();
            if (text.isEmpty()) {
                return;
            }
            mainHandler.post(() -> {
                if (!running) {
                    return;
                }
                if (partial) {
                    callback.onPartialResult(text);
                } else {
                    callback.onResult(text);
                }
            });
        } catch (Exception exception) {
            Log.w(TAG, "Invalid Vosk result", exception);
        }
    }

    private final RecognitionListener engineListener = new RecognitionListener() {
        @Override
        public void onPartialResult(String hypothesis) {
            // Do not parse partials; only completed utterances are needed for commands.
        }

        @Override
        public void onResult(String hypothesis) {
            handleJson(hypothesis, false);
        }

        @Override
        public void onFinalResult(String hypothesis) {
            handleJson(hypothesis, false);
        }

        @Override
        public void onError(Exception exception) {
            Log.e(TAG, "Offline recognition error", exception);
            if (running) {
                fail("离线识别异常，正在重试");
            }
        }

        @Override
        public void onTimeout() {
            if (running) {
                postStatus("离线识别等待语音");
            }
        }
    };
}

package com.jon.vcinteraction;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.RecognitionListener;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// Add these imports at the top of the file along with other imports
import java.util.ArrayList;
import android.content.Context;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final int SPEECH_REQUEST_CODE = 100;
    private static final int VOICE_COMMAND_REQUEST_CODE = 101;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };
    private static final int VIDEO_RECORD_TIME = 5000; // 5 seconds in milliseconds
    private static final String SERVER_BASE_URL = "http://192.168.31.150:8000/";
    private static final int BUFFER_SIZE = 8192; // Increased buffer size for faster file operations

    // Camera components
    private PreviewView previewView;
    private Button captureButton;
    private Button micButton;
    private Button recordButton;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private ProcessCameraProvider cameraProvider;
    private Recording recording = null;

    // File handling
    private File photoFile;
    private Uri savedVideoUri = null;

    // Network client
    private OkHttpClient client;

    // Thread management
    private ExecutorService cameraExecutor;
    private ExecutorService fileProcessExecutor;

    // Speech recognition
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;

    // Text-to-speech
    private TextToSpeech textToSpeech;
    private boolean ttsInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Initialize UI elements
        previewView = findViewById(R.id.previewView);
        captureButton = findViewById(R.id.captureButton);
        recordButton = findViewById(R.id.recordButton);
        micButton = findViewById(R.id.micButton);

        // Initialize executors for background operations
        cameraExecutor = Executors.newSingleThreadExecutor();
        fileProcessExecutor = Executors.newFixedThreadPool(2);

        // Initialize optimized OkHttpClient
        initializeNetworkClient();
        
        // Initialize speech recognition
        initializeSpeechRecognizer();
        
        // Initialize text-to-speech
        initializeTextToSpeech();

        // Set up button listeners
        setupButtonListeners();

        // Request permissions and start camera if granted
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private void initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                    Toast.makeText(MainActivity.this, "Listening...", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onBeginningOfSpeech() {
                    // Not needed for this implementation
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                    // Not needed for this implementation
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                    // Not needed for this implementation
                }

                @Override
                public void onEndOfSpeech() {
                    isListening = false;
                }

                @Override
                public void onError(int error) {
                    isListening = false;
                    String errorMessage = "Speech recognition error: ";
                    switch (error) {
                        case SpeechRecognizer.ERROR_AUDIO:
                            errorMessage += "Audio recording error";
                            break;
                        case SpeechRecognizer.ERROR_CLIENT:
                            errorMessage += "Client side error";
                            break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            errorMessage += "Insufficient permissions";
                            break;
                        case SpeechRecognizer.ERROR_NETWORK:
                            errorMessage += "Network error";
                            break;
                        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            errorMessage += "Network timeout";
                            break;
                        case SpeechRecognizer.ERROR_NO_MATCH:
                            errorMessage += "No recognition match";
                            break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                            errorMessage += "Recognition service busy";
                            break;
                        case SpeechRecognizer.ERROR_SERVER:
                            errorMessage += "Server error";
                            break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            errorMessage += "No speech input";
                            break;
                        default:
                            errorMessage += "Unknown error";
                            break;
                    }
                    Log.e(TAG, errorMessage);
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                    recordButton.setEnabled(true);
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> speechResults = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (speechResults != null && !speechResults.isEmpty()) {
                        String spokenText = speechResults.get(0);
                        Log.d(TAG, "Speech recognition result: " + spokenText);
                        
                        // Process based on available media
                        fileProcessExecutor.execute(() -> {
                            if (savedVideoUri != null) {
                                processVideoWithText(spokenText);
                            } else if (photoFile != null && photoFile.exists()) {
                                sendImageAndTextToServer(photoFile, spokenText);
                            } else {
                                sendTextToServer(spokenText);
                            }
                        });
                    } else {
                        Toast.makeText(MainActivity.this, "No speech detected", Toast.LENGTH_SHORT).show();
                        recordButton.setEnabled(true);
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    // Not needed for this implementation
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                    // Not needed for this implementation
                }
            });
        } else {
            Log.e(TAG, "Speech recognition not available on this device");
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void initializeTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = textToSpeech.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Text-to-speech language not supported");
                } else {
                    textToSpeech.setSpeechRate(1.0f);
                    textToSpeech.setPitch(1.0f);
                    ttsInitialized = true;
                    
                    // Set up progress listener
                    textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            Log.d(TAG, "TTS started: " + utteranceId);
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            Log.d(TAG, "TTS completed: " + utteranceId);
                        }

                        @Override
                        public void onError(String utteranceId) {
                            Log.e(TAG, "TTS error: " + utteranceId);
                        }
                    });
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: " + status);
            }
        });
    }

    private void initializeNetworkClient() {
        // Optimized OkHttpClient with connection pooling and cache
        client = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(5, 30, TimeUnit.SECONDS))
                .callTimeout(45, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private void setupButtonListeners() {
        captureButton.setOnClickListener(v -> takePhoto());
        recordButton.setOnClickListener(v -> captureVideo());
        micButton.setOnClickListener(v -> startAdvancedSpeechRecognition());
    }

    private void startAdvancedSpeechRecognition() {
        // Check for RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Record audio permission not granted for speech recognition");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS);
            return;
        }
        
        if (speechRecognizer != null && !isListening) {
            Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 7000);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 7000);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 7000);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
            
            try {
                speechRecognizer.startListening(recognizerIntent);
            } catch (Exception e) {
                Log.e(TAG, "Error starting advanced speech recognition", e);
                Toast.makeText(this, "Could not start speech recognition", Toast.LENGTH_SHORT).show();
                startVoiceCommandRecognition(); // Fall back to standard recognition
            }
        } else {
            startVoiceCommandRecognition(); // Fall back to standard recognition
        }
    }

    private void startVoiceCommandRecognition() {
        // Check for RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Record audio permission not granted for voice command recognition");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS);
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command or speak to send to server...");
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000); // Allow 10 seconds
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 7000); // 2 seconds silence to complete
        
        try {
            startActivityForResult(intent, VOICE_COMMAND_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Error starting voice command recognition", e);
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void captureVideo() {
        if (videoCapture == null) {
            Log.e(TAG, "Video capture not initialized");
            Toast.makeText(this, "Video capture not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check for RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Record audio permission not granted");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS);
            return;
        }

        // Cancel any ongoing recording
        if (recording != null) {
            recording.stop();
            recording = null;
            return;
        }

        // Disable the record button while recording
        recordButton.setEnabled(false);

        try {
            // Create pre-populated content values for video - reuse pattern
            ContentValues contentValues = createVideoContentValues();

            // Configure video output options
            MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                    getContentResolver(),
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(contentValues)
                    .build();

            // Start recording with optimized event handling
            recording = videoCapture.getOutput()
                    .prepareRecording(this, outputOptions)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this), this::handleVideoRecordEvent);

            // Show immediate feedback to user
            Toast.makeText(this, "Recording starting...", Toast.LENGTH_SHORT).show();

            // Stop recording after preset time
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (recording != null) {
                    Log.d(TAG, "Auto-stopping recording after timer");
                    recording.stop();
                }
            }, VIDEO_RECORD_TIME);

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when starting recording", e);
            recordButton.setEnabled(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            recordButton.setEnabled(true);
        }
    }

    private ContentValues createVideoContentValues() {
        ContentValues values = new ContentValues();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "video_" + timestamp);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        return values;
    }

    private void handleVideoRecordEvent(VideoRecordEvent videoRecordEvent) {
        if (videoRecordEvent instanceof VideoRecordEvent.Start) {
            Log.d(TAG, "Recording started");
        } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;

            if (!finalizeEvent.hasError()) {
                savedVideoUri = finalizeEvent.getOutputResults().getOutputUri();
                Log.d(TAG, "Recording completed: " + savedVideoUri);
                Toast.makeText(this, "Recording complete", Toast.LENGTH_SHORT).show();
                startAdvancedSpeechRecognition();
            } else {
                int errorCode = finalizeEvent.getError();
                Log.e(TAG, "Video recording error: Code " + errorCode);
                recordButton.setEnabled(true);
            }
            recording = null;
        }
    }

    private void takePhoto() {
        if (imageCapture == null) {
            Log.e(TAG, "Image capture not initialized");
            return;
        }

        // Check for CAMERA permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CODE_PERMISSIONS);
            return;
        }

        try {
            // Prepare output options
            ContentValues contentValues = createImageContentValues();
            ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(
                    getContentResolver(),
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues).build();

            // Take picture
            imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(this),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                            Uri savedUri = outputFileResults.getSavedUri();
                            if (savedUri != null) {
                                Log.d(TAG, "Photo saved: " + savedUri);
                                Toast.makeText(MainActivity.this, "Photo saved", Toast.LENGTH_SHORT).show();
                                savedVideoUri = null; // Reset video URI

                                // Process file in background
                                fileProcessExecutor.execute(() -> {
                                    try {
                                        photoFile = createTempFileFromUri(savedUri, "jpg");
                                        if (photoFile != null) {
                                            runOnUiThread(() -> startAdvancedSpeechRecognition());
                                        }
                                    } catch (IOException e) {
                                        Log.e(TAG, "Error creating temp file", e);
                                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                                "Error processing image", Toast.LENGTH_SHORT).show());
                                    }
                                });
                            }
                        }

                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            Log.e(TAG, "Photo capture failed", exception);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Failed to take photo", e);
        }
    }

    private ContentValues createImageContentValues() {
        ContentValues values = new ContentValues();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "image_" + timestamp);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        return values;
    }

    private File createTempFileFromUri(Uri uri, String extension) throws IOException {
        // Create a temporary file with buffered I/O for better performance
        File tempFile = File.createTempFile("temp_", "." + extension, getCacheDir());

        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(tempFile)) {

            if (inputStream == null) {
                throw new IOException("Could not open input stream from URI");
            }

            // Use larger buffer for faster file operations
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            return tempFile;
        } catch (Exception e) {
            Log.e(TAG, "Error creating temp file from URI", e);
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        }
    }

    private void startSpeechRecognition() {
        // Check for RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Record audio permission not granted for speech recognition");
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...");
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000); // Allow 10 seconds
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000); // 2 seconds silence to complete
        
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Error starting speech recognition", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            if (requestCode == SPEECH_REQUEST_CODE) {
                handleSpeechRecognitionResult(data);
            } else if (requestCode == VOICE_COMMAND_REQUEST_CODE) {
                handleVoiceCommandResult(data);
            }
        } else {
            recordButton.setEnabled(true);
        }
    }

    private void handleVoiceCommandResult(Intent data) {
        List<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results != null && !results.isEmpty()) {
            String spokenText = results.get(0).toLowerCase();
            Log.d(TAG, "Voice command: " + spokenText);

            if (spokenText.contains("capture")) {
                // Execute capture photo command
                Toast.makeText(this, "Voice command: Capture photo", Toast.LENGTH_SHORT).show();
                takePhoto();
            } else if (spokenText.contains("record")) {
                // Execute record video command
                Toast.makeText(this, "Voice command: Record video", Toast.LENGTH_SHORT).show();
                captureVideo();
            } else {
                // Process as regular text input
                fileProcessExecutor.execute(() -> {
                    if (savedVideoUri != null) {
                        processVideoWithText(spokenText);
                    } else if (photoFile != null && photoFile.exists()) {
                        sendImageAndTextToServer(photoFile, spokenText);
                    } else {
                        sendTextToServer(spokenText);
                    }
                });
            }
        } else {
            Toast.makeText(this, "No voice command detected", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleSpeechRecognitionResult(Intent data) {
        List<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results != null && !results.isEmpty()) {
            String spokenText = results.get(0);
            Log.d(TAG, "Speech recognition result: " + spokenText);

            // Process based on available media
            fileProcessExecutor.execute(() -> {
                if (savedVideoUri != null) {
                    processVideoWithText(spokenText);
                } else if (photoFile != null && photoFile.exists()) {
                    sendImageAndTextToServer(photoFile, spokenText);
                } else {
                    sendTextToServer(spokenText);
                }
            });
        } else {
            recordButton.setEnabled(true);
        }
    }

    private void processVideoWithText(String text) {
        try {
            File videoFile = createTempFileFromUri(savedVideoUri, "mp4");
            if (videoFile != null && videoFile.exists()) {
                sendVideoAndTextToServer(videoFile, text);
            } else {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Could not process video file", Toast.LENGTH_SHORT).show();
                    recordButton.setEnabled(true);
                });
            }
        } catch (IOException e) {
            Log.e(TAG, "Error processing video", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "Error processing video", Toast.LENGTH_SHORT).show();
                recordButton.setEnabled(true);
            });
        }
        savedVideoUri = null; // Reset URI
    }

    private void sendTextToServer(String text) {
        Log.d(TAG, "Sending text to server: " + text);

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("text", text)
                .build();

        Request request = new Request.Builder()
                .url(SERVER_BASE_URL + "text/")
                .post(requestBody)
                .build();

        sendRequest(request, null);
    }

    private void sendImageAndTextToServer(File imageFile, String text) {
        Log.d(TAG, "Sending image and text to server");

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", imageFile.getName(),
                        RequestBody.create(imageFile, MediaType.parse("image/jpeg")))
                .addFormDataPart("text", text)
                .build();

        Request request = new Request.Builder()
                .url(SERVER_BASE_URL + "image-text/")
                .post(requestBody)
                .build();

        sendRequest(request, imageFile);
    }

    private void sendVideoAndTextToServer(File videoFile, String text) {
        Log.d(TAG, "Sending video and text to server");

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("video", videoFile.getName(),
                        RequestBody.create(videoFile, MediaType.parse("video/mp4")))
                .addFormDataPart("text", text)
                .build();

        Request request = new Request.Builder()
                .url(SERVER_BASE_URL + "video-text/")
                .post(requestBody)
                .build();

        sendRequest(request, videoFile);
    }

    private void sendRequest(Request request, File tempFile) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Request failed", e);
                cleanup(tempFile, "Request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "Server response: " + responseBody);
                    processSuccessResponse(responseBody, tempFile);
                } else {
                    String errorBody = "";
                    try {
                        if (response.body() != null) {
                            errorBody = response.body().string();
                        }
                    } catch (Exception ignored) { }

                    cleanup(tempFile, "Server error: " + response.code() +
                            (errorBody.isEmpty() ? "" : " - " + errorBody));
                }
            }
        });
    }

    private void processSuccessResponse(String responseBody, File tempFile) {
        runOnUiThread(() -> {
            // Show toast with response
            Toast.makeText(MainActivity.this, "Server: " + responseBody, Toast.LENGTH_LONG).show();

            // Read response aloud using TextToSpeech
            speakResponseWithTTS(responseBody);

            // Enable record button
            recordButton.setEnabled(true);

            // Delete temporary file if it exists
            if (tempFile != null && tempFile.exists()) {
                fileProcessExecutor.execute(() -> tempFile.delete());
            }
        });
    }

    private void speakResponseWithTTS(String text) {
        if (!ttsInitialized) {
            Log.e(TAG, "TTS not initialized");
            return;
        }

        if (text == null || text.isEmpty()) {
            Log.w(TAG, "Empty text provided for TTS");
            return;
        }

        // Split text into manageable chunks
        List<String> chunks = new ArrayList<>();
        int chunkSize = 3900; // Leave buffer below 4000 char limit
        
        // Split by sentences to maintain natural speech breaks
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder currentChunk = new StringBuilder();
        
        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > chunkSize) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
            }
            currentChunk.append(sentence).append(" ");
        }
        
        // Add the last chunk if not empty
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        // Speak all chunks in sequence
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String utteranceId = "chunk_" + i;
            
            // Use QUEUE_ADD to speak chunks in sequence
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            
            int result = textToSpeech.speak(chunk, 
                                          TextToSpeech.QUEUE_ADD, 
                                          params, 
                                          utteranceId);
            
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "Error speaking chunk " + i);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }

    private void cleanup(File tempFile, String errorMessage) {
        runOnUiThread(() -> {
            Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            recordButton.setEnabled(true);
        });

        if (tempFile != null && tempFile.exists()) {
            fileProcessExecutor.execute(() -> tempFile.delete());
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder().build();

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture);
        } catch (Exception e) {
            Log.e(TAG, "Error binding camera use cases", e);
        }
    }
}
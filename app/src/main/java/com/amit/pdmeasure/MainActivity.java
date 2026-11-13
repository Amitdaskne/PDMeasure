package com.amit.pdmeasure;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.media.Image;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private PreviewView viewFinder;
    private OverlayView overlay;
    private TextView tvPDResult, tvStatus;
    private Button btnCapture;
    private ProgressBar progressBar;
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture; // 🔥 Real Photo Capture Ke Liye
    
    private final String GEMINI_API_KEY = "AIzaSyBcftwdgANQzcO7M4KNfgDVdmBX9Wrsijc";
    private double currentPdMm = 0.0;
    private boolean isProcessingAI = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        overlay = findViewById(R.id.overlay);
        tvPDResult = findViewById(R.id.tvPDResult);
        tvStatus = findViewById(R.id.tvStatus);
        btnCapture = findViewById(R.id.btnCapture);
        progressBar = findViewById(R.id.progressBar);

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build();
        faceDetector = FaceDetection.getClient(options);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }

        btnCapture.setOnClickListener(v -> captureRealPhoto());
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                // 🔥 ImageCapture Setup 🔥
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalGetImage
    private void processImageProxy(ImageProxy imageProxy) {
        if (isProcessingAI) { imageProxy.close(); return; }
        
        Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            faceDetector.process(image)
                .addOnSuccessListener(faces -> {
                    if (!faces.isEmpty()) {
                        Face face = faces.get(0);
                        calculateLivePD(face, imageProxy.getWidth(), viewFinder.getWidth());
                    } else {
                        runOnUiThread(() -> {
                            tvStatus.setText("No face detected.");
                            overlay.updateEyes(null, null, 0, 0);
                        });
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
        } else {
            imageProxy.close();
        }
    }

    private void calculateLivePD(Face face, int imgWidth, int viewWidth) {
        PointF leftEye = face.getLandmark(FaceLandmark.LEFT_EYE) != null ? face.getLandmark(FaceLandmark.LEFT_EYE).getPosition() : null;
        PointF rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE) != null ? face.getLandmark(FaceLandmark.RIGHT_EYE).getPosition() : null;

        if (leftEye != null && rightEye != null) {
            runOnUiThread(() -> overlay.updateEyes(leftEye, rightEye, imgWidth, viewWidth));
            double pixelDistance = Math.sqrt(Math.pow(leftEye.x - rightEye.x, 2) + Math.pow(leftEye.y - rightEye.y, 2));
            float faceWidthPixels = face.getBoundingBox().width();
            double mmPerPixel = 140.0 / faceWidthPixels; 
            currentPdMm = pixelDistance * mmPerPixel;

            runOnUiThread(() -> {
                tvPDResult.setText(String.format("%.1f mm", currentPdMm));
                tvStatus.setText("Live tracking active.");
            });
        }
    }

    // 🔥 1. REAL PHOTO CAPTURE LOGIC 🔥
    private void captureRealPhoto() {
        if (imageCapture == null || currentPdMm == 0.0) {
            Toast.makeText(this, "Align face first!", Toast.LENGTH_SHORT).show();
            return;
        }

        isProcessingAI = true;
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Capturing photo...");
        btnCapture.setEnabled(false);

        File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "pd_capture.jpg");
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), 
            new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    tvStatus.setText("AI verifying image...");
                    // Convert File to Bitmap
                    try {
                        FileInputStream fis = new FileInputStream(photoFile);
                        Bitmap bitmap = BitmapFactory.decodeStream(fis);
                        callGeminiVisionAPI(bitmap);
                    } catch (Exception e) {
                        handleError("File read error: " + e.getMessage());
                    }
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    handleError("Capture failed: " + exception.getMessage());
                }
            });
    }

    // 🔥 2. GEMINI API CALL WITH DETAILED ERROR LOGGING 🔥
    private void callGeminiVisionAPI(Bitmap bitmap) {
        new Thread(() -> {
            try {
                // Scale down image to save bandwidth (Max 800px)
                int maxSize = 800;
                if (bitmap.getWidth() > maxSize || bitmap.getHeight() > maxSize) {
                    float ratio = Math.min((float) maxSize / bitmap.getWidth(), (float) maxSize / bitmap.getHeight());
                    bitmap = Bitmap.createScaledBitmap(bitmap, (int)(bitmap.getWidth()*ratio), (int)(bitmap.getHeight()*ratio), true);
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                JSONObject payload = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject contentObj = new JSONObject();
                JSONArray parts = new JSONArray();
                
                JSONObject textPart = new JSONObject();
                textPart.put("text", "Analyze this image. Is the face straight? Are there any reflections on glasses? Reply with short confirmation.");
                
                JSONObject imagePart = new JSONObject();
                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", "image/jpeg");
                inlineData.put("data", base64Image);
                imagePart.put("inline_data", inlineData);

                parts.put(textPart);
                parts.put(imagePart);
                contentObj.put("parts", parts);
                contents.put(contentObj);
                payload.put("contents", contents);

                OkHttpClient client = new OkHttpClient();
                RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + GEMINI_API_KEY)
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();
                String respStr = response.body().string(); // Read full response for error

                if (response.isSuccessful()) {
                    JSONObject respJson = new JSONObject(respStr);
                    String aiText = respJson.getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                    
                    runOnUiThread(() -> {
                        isProcessingAI = false;
                        progressBar.setVisibility(View.GONE);
                        btnCapture.setEnabled(true);
                        tvStatus.setText("✅ AI Verified: " + currentPdMm + " mm");
                        Toast.makeText(MainActivity.this, "AI: " + aiText, Toast.LENGTH_LONG).show();
                    });
                } else {
                    // API Response fail hone par exact error print karo
                    handleError("API Failed (" + response.code() + "): " + respStr);
                }

            } catch (Exception e) {
                handleError("Exception: " + e.getMessage());
            }
        }).start();
    }

    private void handleError(String errorMsg) {
        Log.e("GEMINI_ERROR", errorMsg);
        runOnUiThread(() -> {
            isProcessingAI = false;
            progressBar.setVisibility(View.GONE);
            btnCapture.setEnabled(true);
            tvStatus.setText("❌ Error: " + errorMsg); // Screen par error dikhao
            Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}

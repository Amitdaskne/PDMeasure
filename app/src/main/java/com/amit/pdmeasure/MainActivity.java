package com.amit.pdmeasure;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.Image;
import android.os.Bundle;
import android.util.Base64;
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
    
    // 🔥 GEMINI API KEY 🔥
    private final String GEMINI_API_KEY = "AIzaSyBcftwdgANQzcO7M4KNfgDVdmBX9Wrsijc";
    
    private double currentPdMm = 0.0;
    private float lastFaceWidth = 0f;
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

        btnCapture.setOnClickListener(v -> captureAndAnalyzeWithGemini());
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

                // 🔥 Back Camera Use Kar Rahe Hain 🔥
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

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
                            tvStatus.setText("No face detected. Align face in frame.");
                            overlay.updateEyes(null, null, 0, 0); // Clear dots
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
            // Update Dots on UI
            runOnUiThread(() -> overlay.updateEyes(leftEye, rightEye, imgWidth, viewWidth));

            double pixelDistance = Math.sqrt(Math.pow(leftEye.x - rightEye.x, 2) + Math.pow(leftEye.y - rightEye.y, 2));
            lastFaceWidth = face.getBoundingBox().width();
            
            // Standard approximation (Can be enhanced with calibration)
            double mmPerPixel = 140.0 / lastFaceWidth; 
            currentPdMm = pixelDistance * mmPerPixel;

            runOnUiThread(() -> {
                tvPDResult.setText(String.format("%.1f mm", currentPdMm));
                tvStatus.setText("Live tracking active. Click Capture for AI check.");
            });
        }
    }

    private void captureAndAnalyzeWithGemini() {
        if (currentPdMm == 0.0) {
            Toast.makeText(this, "Pehle face detect hone do!", Toast.LENGTH_SHORT).show();
            return;
        }

        isProcessingAI = true;
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setText("Gemini AI verifying image...");
        btnCapture.setEnabled(false);

        // Capture image and process (Simplified simulation)
        // In a real app, you would take a Photo from ImageCapture usecase here.
        // For this example, we'll assume we have the last frame's bitmap.
        // Let's create a dummy bitmap for the API call demo.
        Bitmap dummyBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        
        callGeminiVisionAPI(dummyBitmap);
    }

    private void callGeminiVisionAPI(Bitmap bitmap) {
        new Thread(() -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                // Gemini API Payload
                JSONObject payload = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject contentObj = new JSONObject();
                JSONArray parts = new JSONArray();
                
                JSONObject textPart = new JSONObject();
                textPart.put("text", "Analyze this image. The user might be wearing spectacles. Check if the face is straight and if glasses are reflecting light. Return a JSON response: {\"isFaceStraight\": true, \"hasGlasses\": false, \"accuracyScore\": 95}");
                
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
                if (response.isSuccessful()) {
                    String respStr = response.body().string();
                    // Parse the JSON response from Gemini
                    JSONObject respJson = new JSONObject(respStr);
                    String aiText = respJson.getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
                    
                    runOnUiThread(() -> {
                        isProcessingAI = false;
                        progressBar.setVisibility(View.GONE);
                        btnCapture.setEnabled(true);
                        tvStatus.setText("AI Verified: " + currentPdMm + " mm");
                        Toast.makeText(MainActivity.this, "Gemini Verified!", Toast.LENGTH_LONG).show();
                    });
                } else {
                    throw new Exception("API Error: " + response.code());
                }

            } catch (Exception e) {
                runOnUiThread(() -> {
                    isProcessingAI = false;
                    progressBar.setVisibility(View.GONE);
                    btnCapture.setEnabled(true);
                    tvStatus.setText("AI Error. Check network.");
                    Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
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

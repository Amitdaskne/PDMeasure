package com.amit.pdmeasure;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.media.Image;
import android.os.Bundle;
import android.util.Size;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private PreviewView viewFinder;
    private TextView tvPDResult, tvStatus;
    private ExecutorService cameraExecutor;
    private FaceDetector faceDetector;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        tvPDResult = findViewById(R.id.tvPDResult);
        tvStatus = findViewById(R.id.tvStatus);

        // Setup ML Kit Face Detector (Fast Mode + Landmarks)
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();
        faceDetector = FaceDetection.getClient(options);
        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.camera.core.ExperimentalGetImage
    private void processImageProxy(ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
            
            faceDetector.process(image)
                .addOnSuccessListener(faces -> {
                    if (!faces.isEmpty()) {
                        Face face = faces.get(0);
                        calculatePD(face, imageProxy.getWidth(), imageProxy.getHeight());
                    } else {
                        runOnUiThread(() -> tvStatus.setText("No face detected. Align your face."));
                    }
                })
                .addOnFailureListener(e -> runOnUiThread(() -> tvStatus.setText("Error: " + e.getMessage())))
                .addOnCompleteListener(task -> imageProxy.close());
        } else {
            imageProxy.close();
        }
    }

    private void calculatePD(Face face, int imgWidth, int imgHeight) {
        PointF leftEye = face.getLandmark(FaceLandmark.LEFT_EYE) != null ? 
                face.getLandmark(FaceLandmark.LEFT_EYE).getPosition() : null;
        PointF rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE) != null ? 
                face.getLandmark(FaceLandmark.RIGHT_EYE).getPosition() : null;

        if (leftEye != null && rightEye != null) {
            // Calculate pixel distance between eyes
            double pixelDistance = Math.sqrt(Math.pow(leftEye.x - rightEye.x, 2) + Math.pow(leftEye.y - rightEye.y, 2));
            
            // 🔥 CALIBRATION FACTOR 🔥
            // Since we don't have depth sensor, we use a standard approximation based on face width.
            // Average human face width is ~140mm. We use the face bounding box to calculate mm-per-pixel ratio.
            float faceWidthPixels = face.getBoundingBox().width();
            double mmPerPixel = 140.0 / faceWidthPixels; 

            // Final PD in mm
            double pdInMm = pixelDistance * mmPerPixel;

            // Smooth out the reading
            runOnUiThread(() -> {
                tvPDResult.setText(String.format("%.1f mm", pdInMm));
                tvStatus.setText("Hold phone steady at arm's length (approx 40cm).");
            });
        } else {
            runOnUiThread(() -> tvStatus.setText("Eyes not visible. Remove glasses/obstructions."));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}

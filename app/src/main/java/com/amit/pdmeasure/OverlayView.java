package com.amit.pdmeasure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

public class OverlayView extends View {
    private Paint leftDotPaint, rightDotPaint, linePaint;
    private PointF leftEye = new PointF(-100, -100);
    private PointF rightEye = new PointF(-100, -100);
    private float scaleFactor = 1f;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        leftDotPaint = new Paint();
        leftDotPaint.setColor(Color.parseColor("#00F0FF")); // Cyan Left
        leftDotPaint.setStyle(Paint.Style.FILL);
        leftDotPaint.setAntiAlias(true);

        rightDotPaint = new Paint();
        rightDotPaint.setColor(Color.parseColor("#E23744")); // Zomato Red Right
        rightDotPaint.setStyle(Paint.Style.FILL);
        rightDotPaint.setAntiAlias(true);

        linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);
    }

    public void updateEyes(PointF left, PointF right, float imgWidth, float viewWidth) {
        this.leftEye = left;
        this.rightEye = right;
        this.scaleFactor = viewWidth / imgWidth;
        invalidate(); // Redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (leftEye != null && rightEye != null) {
            float lx = leftEye.x * scaleFactor;
            float ly = leftEye.y * scaleFactor;
            float rx = rightEye.x * scaleFactor;
            float ry = rightEye.y * scaleFactor;

            // Draw Line between eyes
            canvas.drawLine(lx, ly, rx, ry, linePaint);
            
            // Draw Dots
            canvas.drawCircle(lx, ly, 15f, leftDotPaint);
            canvas.drawCircle(rx, ry, 15f, rightDotPaint);
        }
    }
}

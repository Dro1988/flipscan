package com.droapps.flipscan;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;


import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live barcode scanner: Camera2 preview + continuous ZXing decode.
 * Point the camera at a book barcode; it detects automatically —
 * no shutter press. Returns the decoded text in the "upc" extra.
 */
public class ScanActivity extends Activity {

    public static final String EXTRA_UPC = "upc";

    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader imageReader;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private Size previewSize;
    private int sensorOrientation = 0;
    private String cameraId;
    private boolean torchOn = false;
    private final AtomicBoolean found = new AtomicBoolean(false);
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private MultiFormatReader reader;
    private int frameCount = 0;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);

        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(this);
        root.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        textureView.setSurfaceTextureListener(surfaceListener);

        // Dimmed overlay with a clear scan window + red guide line
        root.addView(new ScanOverlay(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("Scan Barcode");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setShadowLayer(4, 0, 2, Color.BLACK);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tp.gravity = Gravity.TOP;
        tp.topMargin = dp(48);
        root.addView(title, tp);

        TextView hint = new TextView(this);
        hint.setText("Point at the barcode on the item.\nIt scans automatically.");
        hint.setTextColor(0xFFDDDDDD);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(4, 0, 2, Color.BLACK);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM;
        hp.bottomMargin = dp(150);
        root.addView(hint, hp);

        // Bottom controls: Cancel + Torch
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        cp.bottomMargin = dp(56);

        android.widget.LinearLayout controls = new android.widget.LinearLayout(this);
        controls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        TextView cancelBtn = makeButton("Cancel");
        cancelBtn.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        controls.addView(cancelBtn);

        TextView torchBtn = makeButton("🔦 Flashlight");
        torchBtn.setOnClickListener(v -> toggleTorch());
        android.widget.LinearLayout.LayoutParams tlp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(16);
        controls.addView(torchBtn, tlp);

        root.addView(controls, cp);
        setContentView(root);

        // ZXing reader configured for retail barcodes
        reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_128, BarcodeFormat.CODE_39));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
    }

    private TextView makeButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(22), dp(12), dp(22), dp(12));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xCC333333);
        bg.setCornerRadius(dp(24));
        bg.setStroke(1, 0x88FFFFFF);
        b.setBackground(bg);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Dark overlay with a clear rounded-rect scan window and red line. */
    private class ScanOverlay extends View {
        private final Paint dimPaint = new Paint();
        private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ScanOverlay(Context c) {
            super(c);
            dimPaint.setColor(0x99000000);
            framePaint.setColor(Color.WHITE);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setStrokeWidth(3f);
            linePaint.setColor(0xFFE5484D);
            linePaint.setStrokeWidth(3f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int fw = (int) (w * 0.82);
            int fh = (int) (fw * 0.55);
            int left = (w - fw) / 2;
            int top = (h - fh) / 2 - dp(40);
            RectF frame = new RectF(left, top, left + fw, top + fh);
            // dim everything outside the frame
            canvas.drawRect(0, 0, w, frame.top, dimPaint);
            canvas.drawRect(0, frame.bottom, w, h, dimPaint);
            canvas.drawRect(0, frame.top, frame.left, frame.bottom, dimPaint);
            canvas.drawRect(frame.right, frame.top, w, frame.bottom, dimPaint);
            // border + center line
            canvas.drawRoundRect(frame, 12, 12, framePaint);
            float midY = (frame.top + frame.bottom) / 2f;
            canvas.drawLine(frame.left + 10, midY, frame.right - 10, midY, linePaint);
        }
    }

    // -------------------------------------------------------------- Camera

    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    openCamera();
                }
                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    configureTransform(w, h);
                }
                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            };

    @Override
    protected void onResume() {
        super.onResume();
        startBgThread();
        if (textureView.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBgThread();
        super.onPause();
    }

    private void startBgThread() {
        bgThread = new HandlerThread("LiveScanCamera");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    private void stopBgThread() {
        if (bgThread != null) {
            bgThread.quitSafely();
            try { bgThread.join(); } catch (InterruptedException ignored) {}
            bgThread = null;
            bgHandler = null;
        }
    }

    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    sensorOrientation = so != null ? so : 0;
                    StreamConfigurationMap map = chars.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        previewSize = choosePreviewSize(
                                map.getOutputSizes(SurfaceTexture.class));
                    }
                    break;
                }
            }
            if (cameraId == null) { finishWithCancel(); return; }
            if (previewSize == null) previewSize = new Size(1280, 720);

            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            configureTransform(textureView.getWidth(), textureView.getHeight());

            imageReader = ImageReader.newInstance(
                    previewSize.getWidth(), previewSize.getHeight(),
                    android.graphics.ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, bgHandler);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cd) {
                    cameraDevice = cd;
                    createSession();
                }
                @Override
                public void onDisconnected(CameraDevice cd) {
                    cd.close();
                    cameraDevice = null;
                }
                @Override
                public void onError(CameraDevice cd, int error) {
                    cd.close();
                    cameraDevice = null;
                    finishWithCancel();
                }
            }, bgHandler);
        } catch (Exception e) {
            finishWithCancel();
        }
    }

    private Size choosePreviewSize(Size[] choices) {
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : choices) {
            int w = s.getWidth(), h = s.getHeight();
            if (w > 1920 || h > 1920) continue;
            // prefer 16:9-ish, close to 1280x720
            long score = Math.abs((long) w * h - 1280L * 720L);
            double ratio = (double) Math.max(w, h) / Math.min(w, h);
            if (ratio < 1.6 || ratio > 1.9) score += 500_000;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best != null ? best : choices[0];
    }

    private void createSession() {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null || cameraDevice == null) return;
            Surface previewSurface = new Surface(st);
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(imageReader.getSurface());
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            previewBuilder.addTarget(imageReader.getSurface());
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            cameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(
                                        previewBuilder.build(), null, bgHandler);
                            } catch (Exception ignored) {}
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            finishWithCancel();
                        }
                    }, bgHandler);
        } catch (Exception e) {
            finishWithCancel();
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) return;
        // preview buffer dims as displayed (sensor 90/270 swaps w/h)
        int pw = previewSize.getWidth(), ph = previewSize.getHeight();
        if (sensorOrientation == 90 || sensorOrientation == 270) {
            int tmp = pw; pw = ph; ph = tmp;
        }
        // center-crop: scale so the buffer fills the view in both axes
        float scaleX = (float) pw / viewWidth;
        float scaleY = (float) ph / viewHeight;
        float maxScale = Math.max(scaleX, scaleY);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(maxScale / scaleX, maxScale / scaleY,
                viewWidth / 2f, viewHeight / 2f);
        textureView.setTransform(m);
    }

    private void toggleTorch() {
        if (cameraDevice == null || previewBuilder == null || captureSession == null) return;
        torchOn = !torchOn;
        previewBuilder.set(CaptureRequest.FLASH_MODE,
                torchOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        try {
            captureSession.setRepeatingRequest(previewBuilder.build(), null, bgHandler);
        } catch (Exception ignored) {}
    }

    private void closeCamera() {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
        } catch (Exception ignored) {}
    }

    private void finishWithCancel() {
        runOnUiThread(() -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    // -------------------------------------------------------------- Decode

    private void onImageAvailable(ImageReader r) {
        Image image;
        try {
            image = r.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (image == null) return;
        if (found.get() || !decoding.compareAndSet(false, true)) {
            image.close();
            return;
        }
        try {
            byte[] yuv = extractYPlane(image);
            int w = image.getWidth(), h = image.getHeight();
            image.close();
            if (yuv == null) { decoding.set(false); return; }
            frameCount++;
            String text = tryDecode(yuv, w, h);
            if (text == null && needsRotation()) {
                // retry with the frame rotated upright
                byte[] rot = rotate90(yuv, w, h);
                text = tryDecode(rot, h, w);
            }
            if (text != null && found.compareAndSet(false, true)) {
                onBarcodeFound(text);
            }
        } catch (Exception e) {
            try { image.close(); } catch (Exception ignored) {}
        } finally {
            decoding.set(false);
        }
    }

    private boolean needsRotation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        // back camera, portrait display -> frames arrive sideways
        return (sensorOrientation == 90 && rotation == Surface.ROTATION_0)
                || (sensorOrientation == 270 && rotation == Surface.ROTATION_180);
    }

    private byte[] extractYPlane(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride();
        int w = image.getWidth(), h = image.getHeight();
        byte[] out = new byte[w * h];
        byte[] row = new byte[rowStride];
        for (int y = 0; y < h; y++) {
            buffer.position(y * rowStride);
            int len = Math.min(rowStride, buffer.remaining());
            buffer.get(row, 0, len);
            System.arraycopy(row, 0, out, y * w, Math.min(w, len));
        }
        buffer.rewind();
        return out;
    }

    private byte[] rotate90(byte[] data, int w, int h) {
        byte[] out = new byte[data.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[x * h + (h - 1 - y)] = data[y * w + x];
            }
        }
        return out;
    }

    private String tryDecode(byte[] y, int w, int h) {
        try {
            // crop to the central region (where the overlay frame is)
            int cropW = (int) (w * 0.85);
            int cropH = (int) (h * 0.6);
            int left = (w - cropW) / 2;
            int top = (h - cropH) / 2;
            LuminanceSource source = new PlanarYUVLuminanceSource(
                    y, w, h, left, top, cropW, cropH, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader.decodeWithState(bitmap);
            if (result != null && result.getText() != null
                    && result.getText().trim().length() >= 8) {
                return result.getText().trim();
            }
        } catch (Exception ignored) {
            // NotFound / checksum / format — keep scanning
        } finally {
            try { reader.reset(); } catch (Exception ignored) {}
        }
        return null;
    }

    private void onBarcodeFound(String text) {
        // feedback: beep + buzz
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (Exception ignored) {}
        try {
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null) {
                vib.vibrate(VibrationEffect.createOneShot(120,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {}
        Intent data = new Intent();
        data.putExtra(EXTRA_UPC, text);
        setResult(RESULT_OK, data);
        finish();
    }
}

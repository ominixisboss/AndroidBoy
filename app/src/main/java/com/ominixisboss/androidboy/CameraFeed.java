package com.ominixisboss.androidboy;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * The phone's camera as the Game Boy Camera's sensor: a small greyscale stream, cropped to the
 * sensor's shape and shrunk to 128×112. Frames go to {@link Listener#onCameraImage} on a
 * background thread, a few times a second (the Game Boy Camera isn't fast either).
 */
final class CameraFeed {
    static final int WIDTH = 128;
    static final int HEIGHT = 112;
    private static final String TAG = "AndroidBoy";
    private static final long FRAME_INTERVAL_MS = 50;

    interface Listener {
        /** A new picture: WIDTH × HEIGHT brightness bytes, rows top to bottom. Background thread. */
        void onCameraImage(byte[] pixels);
    }

    private final Context context;
    private final Listener listener;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean front = true;
    private boolean mirror;
    private int rotation;
    private long lastFrame;

    CameraFeed(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Front camera (a selfie, like the Game Boy Camera's usual way round) or the back one. */
    boolean isFront() {
        return front;
    }

    void setFront(boolean useFront) {
        if (front == useFront) return;
        front = useFront;
        if (device != null) {
            stop();
            start();
        }
    }

    /** Opens the camera. Needs the CAMERA permission; does nothing if there's no camera. */
    @SuppressWarnings("MissingPermission") // Callers check the permission first.
    void start() {
        if (device != null || thread != null) return;
        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager == null) return;
        try {
            String id = pickCamera(manager);
            if (id == null) return;
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            mirror = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            rotation = orientation != null ? orientation : 0;
            Size size = pickSize(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));

            thread = new HandlerThread("Camera");
            thread.start();
            handler = new Handler(thread.getLooper());
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, handler);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    device = camera;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (device == camera) device = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.w(TAG, "Camera error " + error);
                    camera.close();
                    if (device == camera) device = null;
                }
            }, handler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Could not open the camera", e);
            stop();
        }
    }

    @SuppressWarnings("deprecation") // The list-of-surfaces session API is the one that exists on Android 8.
    private void startSession() {
        try {
            device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession configured) {
                            if (device == null) return;
                            session = configured;
                            try {
                                CaptureRequest.Builder request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(reader.getSurface());
                                session.setRepeatingRequest(request.build(), null, handler);
                            } catch (CameraAccessException | IllegalStateException e) {
                                Log.w(TAG, "Could not start the camera", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession failed) {
                            Log.w(TAG, "Camera session failed");
                        }
                    }, handler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "Could not start the camera", e);
        }
    }

    void stop() {
        if (session != null) {
            try {
                session.close();
            } catch (IllegalStateException e) {
                // Already closed with the device.
            }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    private String pickCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        int wanted = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == wanted) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    /** The smallest size that's still comfortably bigger than the Game Boy Camera's. */
    private static Size pickSize(StreamConfigurationMap map) {
        Size best = null;
        if (map != null) {
            for (Size size : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                if (size.getWidth() < 320 || size.getHeight() < 240) continue;
                if (best == null || size.getWidth() * size.getHeight() < best.getWidth() * best.getHeight()) best = size;
            }
        }
        return best != null ? best : new Size(640, 480);
    }

    private void onImage(ImageReader imageReader) {
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame < FRAME_INTERVAL_MS) return;
            lastFrame = now;
            Image.Plane luma = image.getPlanes()[0];
            ByteBuffer buffer = luma.getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            listener.onCameraImage(toSensor(data, image.getWidth(), image.getHeight(), luma.getRowStride(),
                    luma.getPixelStride(), rotation, mirror));
        } finally {
            image.close();
        }
    }

    /**
     * Turns a camera frame's brightness plane into the Game Boy Camera's 128×112 picture: rotated
     * upright (the sensor's mounting angle, for a phone held upright), the middle cropped to the
     * sensor's 8:7 shape, averaged down, and mirrored for a front camera, like a mirror.
     */
    static byte[] toSensor(byte[] luma, int width, int height, int rowStride, int pixelStride, int rotation,
                           boolean mirror) {
        boolean sideways = rotation == 90 || rotation == 270;
        // Size of the upright picture.
        int uprightWidth = sideways ? height : width;
        int uprightHeight = sideways ? width : height;
        // Largest 8:7 area in the middle.
        int cropWidth = Math.min(uprightWidth, uprightHeight * WIDTH / HEIGHT);
        int cropHeight = cropWidth * HEIGHT / WIDTH;
        int left = (uprightWidth - cropWidth) / 2;
        int top = (uprightHeight - cropHeight) / 2;
        byte[] out = new byte[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            int y0 = top + y * cropHeight / HEIGHT;
            int y1 = Math.max(y0 + 1, top + (y + 1) * cropHeight / HEIGHT);
            for (int x = 0; x < WIDTH; x++) {
                int outX = mirror ? WIDTH - 1 - x : x;
                int x0 = left + x * cropWidth / WIDTH;
                int x1 = Math.max(x0 + 1, left + (x + 1) * cropWidth / WIDTH);
                int sum = 0;
                int count = 0;
                // Sample a few points of the area rather than every pixel; plenty for 128×112.
                int stepY = Math.max(1, (y1 - y0) / 2);
                int stepX = Math.max(1, (x1 - x0) / 2);
                for (int uy = y0; uy < y1; uy += stepY) {
                    for (int ux = x0; ux < x1; ux += stepX) {
                        // Upright (ux, uy) back to the sensor's own coordinates.
                        int sx;
                        int sy;
                        switch (rotation) {
                            case 90: sx = uy; sy = height - 1 - ux; break;
                            case 180: sx = width - 1 - ux; sy = height - 1 - uy; break;
                            case 270: sx = width - 1 - uy; sy = ux; break;
                            default: sx = ux; sy = uy; break;
                        }
                        int index = sy * rowStride + sx * pixelStride;
                        if (index >= 0 && index < luma.length) {
                            sum += luma[index] & 0xFF;
                            count++;
                        }
                    }
                }
                out[y * WIDTH + outX] = (byte) (count == 0 ? 0 : sum / count);
            }
        }
        return out;
    }
}

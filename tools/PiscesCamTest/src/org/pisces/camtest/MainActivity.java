package org.pisces.camtest;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String TAG = "PiscesCamTest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String mode = getIntent() != null ? getIntent().getStringExtra("mode") : null;
        if (mode == null) mode = "cam";
        Log.i(TAG, "start mode=" + mode);

        if ("led".equals(mode) || "notify".equals(mode)) {
            runNotifyTest();
        } else {
            int count = getIntent() != null ? getIntent().getIntExtra("count", 10) : 10;
            int gap = getIntent() != null ? getIntent().getIntExtra("gap", 150) : 150;
            boolean frames = getIntent() == null || getIntent().getBooleanExtra("frames", true);
            runCamTest(count, gap, frames);
        }
    }

    /* ---------------- camera stress test ---------------- */

    private void runCamTest(final int count, final int gapMs, final boolean frames) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HandlerThread t = new HandlerThread("cam");
                t.start();
                Handler h = new Handler(t.getLooper());
                CameraManager cm =
                        (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                try {
                    long t0 = SystemClock.elapsedRealtime();
                    String[] ids = cm.getCameraIdList();
                    Log.i(TAG, "CAMTEST start ids=" + Arrays.toString(ids)
                            + " listMs=" + (SystemClock.elapsedRealtime() - t0)
                            + " count=" + count + " gap=" + gapMs + " frames=" + frames);
                    if (frames) {
                        for (String id : ids) {
                            dumpInfo(cm, id);
                        }
                    }
                    int failures = 0;
                    for (int i = 0; i < count; i++) {
                        String id = ids[i % ids.length];
                        StepResult r = openUseClose(cm, id, h, frames);
                        Log.i(TAG, "ITER " + i + " cam=" + id
                                + " open=" + r.openMs + "ms sess=" + r.sessMs
                                + " frame=" + r.frameMs + "ms total=" + r.totalMs
                                + "ms " + (r.ok ? "OK" : "FAIL(" + r.status + ")"));
                        if (!r.ok) {
                            failures++;
                            Thread.sleep(1000);
                            StepResult rr = openUseClose(cm, ids[0], h, false);
                            Log.i(TAG, "RECOVER after fail: " + (rr.ok ? "OK" : "FAIL(" + rr.status + ")")
                                    + " open=" + rr.openMs + "ms");
                            break;
                        }
                        if (gapMs > 0) Thread.sleep(gapMs);
                    }
                    Log.i(TAG, "CAMTEST done failures=" + failures);
                } catch (Throwable e) {
                    Log.e(TAG, "CAMTEST exception", e);
                }
                t.quitSafely();
            }
        }).start();
    }

    private void dumpInfo(CameraManager cm, String id) {
        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map =
                    ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            Integer level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            Log.i(TAG, "INFO cam=" + id + " facing=" + facing + " level=" + level
                    + " jpeg=" + sizeList(map, ImageFormat.JPEG)
                    + " yuv=" + sizeList(map, ImageFormat.YUV_420_888)
                    + " st=" + sizeList(map, SurfaceTexture.class));
        } catch (Throwable e) {
            Log.e(TAG, "INFO failed for " + id, e);
        }
    }

    private static String sizeList(StreamConfigurationMap map, int fmt) {
        try {
            android.util.Size[] s = map.getOutputSizes(fmt);
            if (s == null) return "null";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(s.length, 8); i++) {
                if (i > 0) b.append('/');
                b.append(s[i].getWidth()).append('x').append(s[i].getHeight());
            }
            return b.toString();
        } catch (Throwable t) {
            return "err";
        }
    }

    private static String sizeList(StreamConfigurationMap map, Class<?> klass) {
        try {
            android.util.Size[] s = map.getOutputSizes(klass);
            if (s == null) return "null";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(s.length, 8); i++) {
                if (i > 0) b.append('/');
                b.append(s[i].getWidth()).append('x').append(s[i].getHeight());
            }
            return b.toString();
        } catch (Throwable t) {
            return "err";
        }
    }

    private static class StepResult {
        boolean ok = true;
        long openMs, sessMs, frameMs, totalMs;
        String status = "OK";
    }

    private StepResult openUseClose(CameraManager cm, final String id, Handler h, boolean frames)
            throws Exception {
        final StepResult r = new StepResult();
        final long t0 = SystemClock.elapsedRealtime();
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CameraDevice[] holder = new CameraDevice[1];
        final String[] err = new String[1];

        try {
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice c) {
                    holder[0] = c;
                    openLatch.countDown();
                }

                @Override
                public void onDisconnected(CameraDevice c) {
                    err[0] = "DISCONNECTED";
                    c.close();
                    openLatch.countDown();
                }

                @Override
                public void onError(CameraDevice c, int e) {
                    err[0] = "DEVICE_ERROR_" + e;
                    c.close();
                    openLatch.countDown();
                }
            }, h);
        } catch (Throwable t) {
            err[0] = "OPEN_EXCEPTION_" + t.getClass().getSimpleName() + ":" + t.getMessage();
            openLatch.countDown();
        }

        if (!openLatch.await(15, TimeUnit.SECONDS)) {
            err[0] = "OPEN_TIMEOUT";
        }
        r.openMs = SystemClock.elapsedRealtime() - t0;
        if (holder[0] == null) {
            r.ok = false;
            r.status = err[0] != null ? err[0] : "NO_DEVICE";
            r.totalMs = SystemClock.elapsedRealtime() - t0;
            return r;
        }

        ImageReader reader = null;
        CameraCaptureSession session = null;
        try {
            if (frames) {
                StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                android.util.Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                android.util.Size size = jpegSizes != null && jpegSizes.length > 0
                        ? jpegSizes[0] : new android.util.Size(640, 480);

                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                        ImageFormat.JPEG, 2);
                final CountDownLatch frameLatch = new CountDownLatch(1);
                final int[] frameLen = new int[1];
                reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader ir) {
                        try {
                            Image img = ir.acquireLatestImage();
                            if (img != null) {
                                java.nio.ByteBuffer buf = img.getPlanes()[0].getBuffer();
                                byte[] head = new byte[2];
                                if (buf.remaining() >= 2) {
                                    buf.get(head);
                                }
                                frameLen[0] = img.getPlanes()[0].getBuffer().remaining();
                                img.close();
                                Log.i(TAG, "FRAME " + size.getWidth() + "x" + size.getHeight()
                                        + " bytes=" + frameLen[0]
                                        + " jpeg=" + (head[0] == (byte) 0xff
                                                && head[1] == (byte) 0xd8));
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "frame error", t);
                        }
                        frameLatch.countDown();
                    }
                }, h);

                final CountDownLatch sessLatch = new CountDownLatch(1);
                final CameraCaptureSession[] sessHolder = new CameraCaptureSession[1];
                final String[] sessErr = new String[1];
                final long ts = SystemClock.elapsedRealtime();
                holder[0].createCaptureSession(
                        Arrays.asList(reader.getSurface()),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession s) {
                                sessHolder[0] = s;
                                sessLatch.countDown();
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession s) {
                                sessErr[0] = "CONFIG_FAILED";
                                sessLatch.countDown();
                            }
                        }, h);
                if (!sessLatch.await(15, TimeUnit.SECONDS)) {
                    sessErr[0] = "SESS_TIMEOUT";
                }
                r.sessMs = SystemClock.elapsedRealtime() - ts;
                session = sessHolder[0];
                if (session == null) {
                    r.ok = false;
                    r.status = sessErr[0] != null ? sessErr[0] : "NO_SESSION";
                    r.totalMs = SystemClock.elapsedRealtime() - t0;
                    return r;
                }

                CaptureRequest.Builder b =
                        holder[0].createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(reader.getSurface());
                session.capture(b.build(), null, h);

                long tf = SystemClock.elapsedRealtime();
                if (!frameLatch.await(8, TimeUnit.SECONDS)) {
                    r.ok = false;
                    r.status = "FRAME_TIMEOUT";
                    r.frameMs = SystemClock.elapsedRealtime() - tf;
                    r.totalMs = SystemClock.elapsedRealtime() - t0;
                    return r;
                }
                r.frameMs = SystemClock.elapsedRealtime() - tf;
            }
        } catch (Throwable t) {
            r.ok = false;
            r.status = "USE_EXCEPTION_" + t.getClass().getSimpleName() + ":" + t.getMessage();
        } finally {
            try {
                if (session != null) session.close();
            } catch (Throwable ignored) {
            }
            try {
                holder[0].close();
            } catch (Throwable ignored) {
            }
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored) {
            }
            r.totalMs = SystemClock.elapsedRealtime() - t0;
        }
        return r;
    }

    /* ---------------- LED test ---------------- */

    private void runNotifyTest() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        for (int c : new int[]{0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff}) {
            Notification n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("camtest led")
                    .setPriority(Notification.PRIORITY_HIGH)
                    // a silent (pre-O) notification is capped at IMPORTANCE_LOW by
                    // NotificationRecord, which is below the LED threshold
                    .setVibrate(new long[]{0, 50})
                    .setLights(c, 500, 500)
                    .build();
            nm.notify(1000 + (c & 0xff), n);
            Log.i(TAG, "LED notify color=" + Integer.toHexString(c));
            SystemClock.sleep(4000);
        }
    }
}

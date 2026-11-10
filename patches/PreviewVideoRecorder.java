/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * pisces: records video from the camera preview frames (YUV) instead of using
 * MediaRecorder with a video source.
 *
 * The 2013 camera HAL exposes a single preview output, so a MediaRecorder that
 * drives the camera through a surface freezes the on-screen viewfinder.  Here
 * the camera keeps previewing to the display and every preview frame is also
 * encoded to H.264, muxed together with AAC audio from the microphone.
 */
public class PreviewVideoRecorder {
    private static final String TAG = "PreviewVideoRecorder";
    private static final String VIDEO_MIME = "video/avc";
    private static final String AUDIO_MIME = "audio/mp4a-latm";
    private static final int AUDIO_SAMPLE_RATE = 48000;
    private static final int AUDIO_BIT_RATE = 128000;
    private static final int DEQUEUE_TIMEOUT_US = 10000;

    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private MediaMuxer mMuxer;
    private AudioRecord mAudioRecord;
    private Thread mAudioThread;

    private final Object mLock = new Object();
    private int mVideoTrack = -1;
    private int mAudioTrack = -1;
    private boolean mMuxerStarted;
    private boolean mStopping;
    private volatile boolean mReleased;
    private boolean mAudioEnabled = true;
    private boolean mVideoEnabled = true;

    private int mWidth;
    private int mHeight;
    private byte[] mConvert;
    private int mColorFormat;
    private boolean mUseFlexibleImage;
    private byte[] mAudioBuffer;
    private int mVideoFrames;
    private long mStartTimeUs;
    private long mMinFrameIntervalUs;
    private long mLastFrameUs;
    private int mDebugFrames;
    private int mDroppedFrames;

    /** Thrown when the encoder cannot be set up. */
    public static class RecorderException extends IOException {
        public RecorderException(String message) {
            super(message);
        }
    }

    /** Duration of the recorded track, used to size the silent audio track. */
    public long getRecordedDurationUs() {
        return mLastFrameUs > mStartTimeUs ? mLastFrameUs - mStartTimeUs : 0;
    }

    public void setVideoEnabled(boolean enabled) {
        mVideoEnabled = enabled;
    }

    public void setAudioEnabled(boolean enabled) {
        mAudioEnabled = enabled;
    }

    public void start(int width, int height, int fps, int videoBitRate, String path,
            int orientationHint) throws RecorderException {
        mWidth = width;
        mHeight = height;
        mVideoFrames = 0;
        mDroppedFrames = 0;
        mStartTimeUs = System.nanoTime() / 1000;
        mStopping = false;
        mReleased = false;
        mMuxerStarted = false;
        mVideoTrack = -1;
        mAudioTrack = -1;

        try {
            mMuxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mMuxer.setOrientationHint(orientationHint);

            String encoderName = chooseEncoder(VIDEO_MIME);
            Log.i(TAG, "video encoder: " + encoderName);
            int colorFormat = chooseColorFormat(encoderName, VIDEO_MIME);
            Log.i(TAG, "video color format: " + colorFormat);
            if (mVideoEnabled) {
            MediaFormat videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            mVideoEncoder = encoderName != null
                    ? MediaCodec.createByCodecName(encoderName)
                    : MediaCodec.createEncoderByType(VIDEO_MIME);
            mVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mVideoEncoder.start();
            }

            MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME,
                    AUDIO_SAMPLE_RATE, 1);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
            mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioEncoder.start();

            mColorFormat = colorFormat;
            mConvert = new byte[width * height * 3 / 2];
            // The Image based path produced black frames on this codec (it keeps
            // using its own buffers), so write the raw NV12 planes instead.
            mUseFlexibleImage = false && colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
        } catch (Exception e) {
            Log.e(TAG, "cannot start the encoders", e);
            release();
            throw new RecorderException(e.toString());
        }

        if (mAudioEnabled) {
            startAudio();
        }
        Log.i(TAG, "recording " + width + "x" + height + "@" + fps + " to " + path
                + " (rotation " + orientationHint + ")");
    }

    /** Prefers a codec that can take YUV byte buffers (the software one does). */
    private static String chooseEncoder(String mime) {
        String fallback = null;
        android.media.MediaCodecList list =
                new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(mime)) {
                    continue;
                }
                if (fallback == null) {
                    fallback = info.getName();
                }
                if (info.getName().startsWith("OMX.google.")) {
                    return info.getName();
                }
            }
        }
        return fallback;
    }

    private static int chooseColorFormat(String name, String mime) {
        int preferred = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        try {
            MediaCodecInfo info = null;
            android.media.MediaCodecList list =
                    new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo candidate : list.getCodecInfos()) {
                if (candidate.getName().equals(name)) {
                    info = candidate;
                    break;
                }
            }
            if (info == null) {
                return preferred;
            }
            for (int format : info.getCapabilitiesForType(mime).colorFormats) {
                if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                        || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                        || format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                    return format;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot query the color formats", e);
        }
        return preferred;
    }

    private void startAudio() {
        try {
            int minBuffer = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBuffer * 2, 8192);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER,
                    AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "no audio: AudioRecord not initialised");
                mAudioRecord = null;
                return;
            }
            mAudioRecord.startRecording();
            mAudioBuffer = new byte[bufferSize];
            mAudioThread = new Thread("PreviewVideoRecorderAudio") {
                @Override
                public void run() {
                    audioLoop();
                }
            };
            mAudioThread.start();
        } catch (Exception e) {
            Log.w(TAG, "no audio", e);
            mAudioRecord = null;
        }
    }

    private void audioLoop() {
        try {
            while (!mStopping) {
                int read = mAudioRecord.read(mAudioBuffer, 0, mAudioBuffer.length);
                if (read <= 0) {
                    continue;
                }
                feedAudio(mAudioBuffer, read);
                drainAudioEncoder(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "audio capture failed", e);
        }
    }

    private void feedAudio(byte[] data, int size) {
        if (mAudioEncoder == null) {
            return;
        }
        try {
            int index = mAudioEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (index < 0) {
                return;
            }
            ByteBuffer buffer = mAudioEncoder.getInputBuffer(index);
            if (buffer == null) {
                return;
            }
            buffer.clear();
            buffer.put(data, 0, Math.min(size, buffer.capacity()));
            mAudioEncoder.queueInputBuffer(index, 0, Math.min(size, buffer.capacity()),
                    System.nanoTime() / 1000 - mStartTimeUs, 0);
        } catch (IllegalStateException e) {
            Log.w(TAG, "audio encoder gone", e);
        }
    }

    private void drainAudioEncoder(boolean endOfStream) {
        if (mAudioEncoder == null) {
            return;
        }
        if (endOfStream) {
            try {
                int index = mAudioEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (index >= 0) {
                    mAudioEncoder.queueInputBuffer(index, 0, 0,
                            System.nanoTime() / 1000 - mStartTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (IllegalStateException e) {
                return;
            }
        }
        drainEncoder(mAudioEncoder, true);
    }

    /** Feeds one camera preview frame (NV21) to the encoder. */
    public void offerFrame(byte[] nv21) {
        if (mReleased || mStopping || mVideoEncoder == null || !mVideoEnabled) {
            return;
        }
        long nowUs = System.nanoTime() / 1000;
        if (mMinFrameIntervalUs > 0 && mLastFrameUs != 0
                && nowUs - mLastFrameUs < mMinFrameIntervalUs) {
            mDroppedFrames++;
            return;
        }
        mLastFrameUs = nowUs;
        if (mDebugFrames < 3) {
            mDebugFrames++;
            long sum = 0;
            int count = Math.min(nv21.length, mWidth * mHeight);
            for (int i = 0; i < count; i += 97) {
                sum += nv21[i] & 0xff;
            }
            long avg = count > 0 ? sum / (count / 97 + 1) : 0;
            Log.i(TAG, "frame " + mDebugFrames + ": luma average " + avg
                    + " size " + nv21.length + " (w=" + mWidth + " h=" + mHeight + ")");
        }
        if (nv21.length < mConvert.length) {
            mDroppedFrames++;
            return;
        }
        // The camera delivers NV21 (YYYY...VUVU); the encoder wants NV12
        // (YYYY...UVUV), so swap the chroma bytes into our own buffer.
        // The camera hands us NV21 (YYYY...VUVU).  Convert it to whatever the
        // encoder was configured with: I420 (planar) or NV12 (semi planar).
        // persist.pisces.cam_uvswap flips the chroma order in case the HAL
        // actually delivers NV12 instead of NV21.
        final int ySize = mWidth * mHeight;
        final boolean uvSwap = android.os.SystemProperties.getBoolean(
                "persist.pisces.cam_uvswap", false);
        System.arraycopy(nv21, 0, mConvert, 0, ySize);
        if (mColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            int uIndex = ySize;
            int vIndex = ySize + ySize / 4;
            for (int i = 0; i < ySize / 4; i++) {
                byte first = nv21[ySize + i * 2];
                byte second = nv21[ySize + i * 2 + 1];
                mConvert[uIndex + i] = uvSwap ? first : second;
                mConvert[vIndex + i] = uvSwap ? second : first;
            }
        } else {
            for (int i = ySize; i + 1 < mConvert.length; i += 2) {
                if (uvSwap) {
                    mConvert[i] = nv21[i];
                    mConvert[i + 1] = nv21[i + 1];
                } else {
                    mConvert[i] = nv21[i + 1];
                    mConvert[i + 1] = nv21[i];
                }
            }
        }
        try {
            int index = mVideoEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (index < 0) {
                mDroppedFrames++;
                return;
            }
            ByteBuffer buffer = mVideoEncoder.getInputBuffer(index);
            if (buffer == null) {
                return;
            }
            // Presentation times must be relative to the start of the recording:
            // absolute values made the merged file report a bogus duration.
            long ptsUs = System.nanoTime() / 1000 - mStartTimeUs;
            android.media.Image image = mUseFlexibleImage
                    ? mVideoEncoder.getInputImage(index) : null;
            if (image != null) {
                // The codec chose a flexible format: its buffers follow the Image
                // strides, so write the planes through the Image (a flat buffer
                // copy misplaced the chroma and the video lost its colours).
                // The Image is only valid until queueInputBuffer(), so it is
                // fetched fresh for every frame.
                copyPlane(image.getPlanes()[0], nv21, 0, 1);
                copyPlane(image.getPlanes()[1], nv21, ySize + 1, 2);
                copyPlane(image.getPlanes()[2], nv21, ySize, 2);
            } else {
                buffer.clear();
                buffer.put(mConvert, 0, mConvert.length);
            }
            mVideoEncoder.queueInputBuffer(index, 0, mConvert.length, ptsUs, 0);
            mVideoFrames++;
            drainEncoder(mVideoEncoder, false);
        } catch (IllegalStateException e) {
            Log.w(TAG, "video encoder gone", e);
        }
    }


    /**
     * Writes a silent AAC track of the given length.  The gallery's player
     * refuses to render video-only files (it shows a black first frame), so the
     * silent recording gets a silent audio track before it is published.
     */
    public static boolean makeSilentAudio(String path, long durationUs) {
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        try {
            encoder = MediaCodec.createEncoderByType(AUDIO_MIME);
            MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 16000);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            final int frameSamples = 1024;
            final long frameUs = 1000000L * frameSamples / AUDIO_SAMPLE_RATE;
            final byte[] silence = new byte[2048];   // AAC LC silence frame payload
            long ptsUs = 0;
            int track = -1;
            boolean started = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = SystemClock.elapsedRealtime() + 15000;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (ptsUs <= durationUs) {
                    int index = encoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer input = encoder.getInputBuffer(index);
                        if (input != null) {
                            input.clear();
                            input.put(silence);
                            encoder.queueInputBuffer(index, 0, silence.length, ptsUs, 0);
                        }
                        ptsUs += frameUs;
                    }
                } else {
                    int index = encoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, ptsUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }
                while (true) {
                    int outIndex = encoder.dequeueOutputBuffer(info, 0);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        started = true;
                    } else if (outIndex >= 0) {
                        ByteBuffer buffer = encoder.getOutputBuffer(outIndex);
                        if (buffer != null && info.size > 0 && started && track >= 0) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            muxer.writeSampleData(track, buffer, info);
                        }
                        encoder.releaseOutputBuffer(outIndex, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return true;
                        }
                    } else {
                        break;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "could not create the silent audio track", e);
            return false;
        } finally {
            try {
                if (encoder != null) {
                    encoder.stop();
                    encoder.release();
                }
            } catch (Exception ignored) {
            }
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Copies one Image plane out of the NV21 frame, honouring its strides. */
    private void copyPlane(android.media.Image.Plane plane, byte[] nv21, int offset, int step) {
        java.nio.ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int planeWidth = (step == 1) ? mWidth : mWidth / 2;
        int planeHeight = (step == 1) ? mHeight : mHeight / 2;
        byte[] row = new byte[planeWidth * pixelStride];
        for (int y = 0; y < planeHeight; y++) {
            buffer.position(y * rowStride);
            buffer.get(row);
            for (int x = 0; x < planeWidth; x++) {
                int source = offset + (y * planeWidth + x) * step;
                if (source < nv21.length) {
                    row[x * pixelStride] = nv21[source];
                }
            }
            buffer.position(y * rowStride);
            buffer.put(row);
        }
    }

    private boolean drainEncoder(MediaCodec encoder, boolean isAudio) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index;
            try {
                index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            } catch (Exception e) {
                Log.w(TAG, "dequeueOutputBuffer failed", e);
                return true;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (mLock) {
                    if (isAudio) {
                        mAudioTrack = mMuxer.addTrack(encoder.getOutputFormat());
                    } else {
                        mVideoTrack = mMuxer.addTrack(encoder.getOutputFormat());
                    }
                    maybeStartMuxer();
                }
                continue;
            }
            if (index < 0) {
                continue;
            }
            try {
                ByteBuffer buffer = encoder.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }
                if (info.size > 0 && buffer != null) {
                    synchronized (mLock) {
                        if (mMuxerStarted) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            mMuxer.writeSampleData(isAudio ? mAudioTrack : mVideoTrack,
                                    buffer, info);
                        }
                    }
                }
                encoder.releaseOutputBuffer(index, false);
            } catch (Exception e) {
                Log.w(TAG, "dropping an encoder buffer", e);
                try {
                    encoder.releaseOutputBuffer(index, false);
                } catch (Exception ignored) {
                }
                return false;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return true;
            }
        }
    }

    /** Drains an encoder until it reports end of stream (bounded). */
    private void drainUntilEos(MediaCodec encoder, boolean isAudio) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (drainEncoder(encoder, isAudio)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.w(TAG, "encoder did not reach end of stream in 5 s");
    }


    /**
     * Copies an MP4 so that the moov box comes before the mdat, adjusting the
     * chunk offsets.  Players (and the gallery's thumbnailer) otherwise have to
     * seek to the end of the file before they can show the first frame, which is
     * what made long recordings slow to start.
     */
    public static boolean faststartPublish(String source, String destination) {
        java.io.RandomAccessFile in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.RandomAccessFile(source, "r");
            long length = in.length();
            long ftypPos = -1, ftypLen = 0, moovPos = -1, moovLen = 0, mdatPos = -1, mdatLen = 0;
            long pos = 0;
            byte[] header = new byte[8];
            while (pos + 8 <= length) {
                in.seek(pos);
                in.readFully(header);
                long size = readU32(header, 0);
                String type = new String(header, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                long headerSize = 8;
                if (size == 1) {
                    byte[] large = new byte[8];
                    in.readFully(large);
                    size = ((long) readU32(large, 0) << 32) | readU32(large, 4);
                    headerSize = 16;
                } else if (size == 0) {
                    size = length - pos;
                }
                if (size < headerSize) {
                    return false;
                }
                if ("ftyp".equals(type)) {
                    ftypPos = pos;
                    ftypLen = size;
                } else if ("moov".equals(type)) {
                    moovPos = pos;
                    moovLen = size;
                } else if ("mdat".equals(type)) {
                    mdatPos = pos;
                    mdatLen = size;
                }
                pos += size;
            }
            if (moovPos < 0 || mdatPos < 0 || ftypPos < 0 || moovPos < mdatPos) {
                return false;   // already faststart or not a plain MP4
            }
            long newMdat = ftypLen + moovLen;
            long delta = newMdat - mdatPos;

            byte[] moov = new byte[(int) moovLen];
            in.seek(moovPos);
            in.readFully(moov);
            patchChunkOffsets(moov, 8, (int) moovLen, delta);

            out = new java.io.FileOutputStream(destination);
            copyRange(in, out, ftypPos, ftypLen);
            out.write(moov);
            copyRange(in, out, mdatPos, mdatLen);
            out.getFD().sync();
            out.close();
            out = null;
            in.close();
            in = null;
            Log.i(TAG, "faststart applied (moov before mdat, delta " + delta + ")");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "faststart failed", e);
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static long readU32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }

    private static void writeU32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static void writeU64(byte[] data, int offset, long value) {
        writeU32(data, offset, value >>> 32);
        writeU32(data, offset + 4, value & 0xffffffffL);
    }

    /** Walks the box tree inside [start,end) and shifts stco/co64 entries. */
    private static void patchChunkOffsets(byte[] box, int start, int end, long delta) {
        int pos = start;
        while (pos + 8 <= end) {
            long size = readU32(box, pos);
            String type = new String(box, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (size < 8 || pos + size > end) {
                return;
            }
            int payload = pos + 8;
            if ("stco".equals(type)) {
                long count = readU32(box, payload + 4);
                for (int i = 0; i < count; i++) {
                    int at = payload + 8 + i * 4;
                    writeU32(box, at, readU32(box, at) + delta);
                }
            } else if ("co64".equals(type)) {
                long count = readU32(box, payload + 4);
                for (int i = 0; i < count; i++) {
                    int at = payload + 8 + i * 8;
                    long value = (readU32(box, at) << 32) | readU32(box, at + 4);
                    writeU64(box, at, value + delta);
                }
            } else if ("moov".equals(type) || "trak".equals(type) || "mdia".equals(type)
                    || "minf".equals(type) || "stbl".equals(type)) {
                patchChunkOffsets(box, payload, (int) (pos + size), delta);
            }
            pos += size;
        }
    }

    private static void copyRange(java.io.RandomAccessFile in, java.io.FileOutputStream out,
            long offset, long length) throws java.io.IOException {
        in.seek(offset);
        byte[] buffer = new byte[256 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, chunk);
            if (read <= 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }


    private void maybeStartMuxer() {
        if (!mMuxerStarted && (!mVideoEnabled || mVideoTrack >= 0)
                && (!mAudioEnabled || mAudioTrack >= 0)) {
            mMuxer.start();
            mMuxerStarted = true;
            Log.i(TAG, "muxer started (video=" + mVideoTrack + ", audio=" + mAudioTrack + ")");
        }
    }

    /** Stops the encoders and finalises the file. */
    public void stop() {
        if (mReleased) {
            return;
        }
        mStopping = true;

        // 1. stop the microphone and let the audio thread finish its last drain
        //    before anything touches the muxer again.
        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (mAudioThread != null) {
            try {
                mAudioThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mAudioThread = null;
        }
        if (mAudioRecord != null) {
            try {
                mAudioRecord.release();
            } catch (Exception ignored) {
            }
            mAudioRecord = null;
        }

        // 2. drain both encoders to their end of stream, one at a time.
        if (mAudioEncoder != null) {
            try {
                int index = mAudioEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (index >= 0) {
                    mAudioEncoder.queueInputBuffer(index, 0, 0,
                            System.nanoTime() / 1000 - mStartTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                drainUntilEos(mAudioEncoder, true);
            } catch (Exception e) {
                Log.w(TAG, "audio end of stream failed", e);
            }
        }
        if (mVideoEncoder != null) {
            try {
                int index = mVideoEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (index >= 0) {
                    mVideoEncoder.queueInputBuffer(index, 0, 0,
                            System.nanoTime() / 1000 - mStartTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                drainUntilEos(mVideoEncoder, false);
            } catch (Exception e) {
                Log.w(TAG, "video end of stream failed", e);
            }
        }

        Log.i(TAG, "stopped after " + mVideoFrames + " video frames ("
                + mDroppedFrames + " dropped)");
        // 3. only now is it safe to finalise the file (this writes the moov).
        release();
    }

    /**
     * Copies the video track of videoPath and the audio track of audioPath into
     * a single MP4 at outPath.
     */
    public static boolean remux(String videoPath, String audioPath, String outPath,
            int orientationHint) {
        android.media.MediaExtractor video = new android.media.MediaExtractor();
        android.media.MediaExtractor audio = new android.media.MediaExtractor();
        MediaMuxer muxer = null;
        try {
            video.setDataSource(videoPath);
            audio.setDataSource(audioPath);
            int videoTrack = -1;
            for (int i = 0; i < video.getTrackCount(); i++) {
                String mime = video.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    break;
                }
            }
            int audioTrack = -1;
            for (int i = 0; i < audio.getTrackCount(); i++) {
                String mime = audio.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    break;
                }
            }
            if (videoTrack < 0) {
                Log.e(TAG, "no video track in " + videoPath);
                return false;
            }
            video.selectTrack(videoTrack);
            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(orientationHint);
            int outVideo = muxer.addTrack(video.getTrackFormat(videoTrack));
            int outAudio = -1;
            if (audioTrack >= 0) {
                audio.selectTrack(audioTrack);
                outAudio = muxer.addTrack(audio.getTrackFormat(audioTrack));
            }
            muxer.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
            long videoEndUs = copyTrack(video, muxer, outVideo, buffer, info, Long.MAX_VALUE);
            if (outAudio >= 0) {
                // The audio recorder keeps running while the video encoder is
                // drained, so cut the audio at the last video frame.
                copyTrack(audio, muxer, outAudio, buffer, info, videoEndUs);
            }
            muxer.stop();
            Log.i(TAG, "remuxed into " + outPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "remux failed", e);
            return false;
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
            video.release();
            audio.release();
        }
    }

    private static void copyTrack(android.media.MediaExtractor extractor, MediaMuxer muxer,
            int track, ByteBuffer buffer, MediaCodec.BufferInfo info) {
        copyTrack(extractor, muxer, track, buffer, info, Long.MAX_VALUE);
    }

    /** Copies samples, stops at limitUs and returns the last timestamp seen. */
    private static long copyTrack(android.media.MediaExtractor extractor, MediaMuxer muxer,
            int track, ByteBuffer buffer, MediaCodec.BufferInfo info, long limitUs) {
        long last = 0;
        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            if (extractor.getSampleTime() > limitUs) {
                break;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(track, buffer, info);
            last = info.presentationTimeUs;
            extractor.advance();
        }
        return last;
    }

    private void release() {
        mReleased = true;
        synchronized (mLock) {
            if (mMuxer != null) {
                try {
                    if (mMuxerStarted) {
                        mMuxer.stop();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "muxer stop failed", e);
                }
                try {
                    mMuxer.release();
                } catch (Exception ignored) {
                }
                mMuxer = null;
                mMuxerStarted = false;
            }
        }
        if (mVideoEncoder != null) {
            try {
                mVideoEncoder.stop();
            } catch (Exception ignored) {
            }
            try {
                mVideoEncoder.release();
            } catch (Exception ignored) {
            }
            mVideoEncoder = null;
        }
        if (mAudioEncoder != null) {
            try {
                mAudioEncoder.stop();
            } catch (Exception ignored) {
            }
            try {
                mAudioEncoder.release();
            } catch (Exception ignored) {
            }
            mAudioEncoder = null;
        }
    }
}

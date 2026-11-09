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

    private int mWidth;
    private int mHeight;
    private byte[] mConvert;
    private byte[] mAudioBuffer;
    private int mVideoFrames;
    private long mStartTimeUs;
    private int mDroppedFrames;

    /** Thrown when the encoder cannot be set up. */
    public static class RecorderException extends IOException {
        public RecorderException(String message) {
            super(message);
        }
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

            MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME,
                    AUDIO_SAMPLE_RATE, 1);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME);
            mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioEncoder.start();

            mConvert = new byte[width * height * 3 / 2];
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
        while (!mStopping) {
            int read = mAudioRecord.read(mAudioBuffer, 0, mAudioBuffer.length);
            if (read <= 0) {
                continue;
            }
            feedAudio(mAudioBuffer, read);
            drainAudioEncoder(false);
        }
        drainAudioEncoder(true);
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
                    System.nanoTime() / 1000, 0);
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
                    mAudioEncoder.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000,
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
        if (mReleased || mStopping || mVideoEncoder == null) {
            return;
        }
        if (nv21.length < mConvert.length) {
            mDroppedFrames++;
            return;
        }
        // The camera delivers NV21 (YYYY...VUVU); the encoder wants NV12
        // (YYYY...UVUV), so swap the chroma bytes into our own buffer.
        System.arraycopy(nv21, 0, mConvert, 0, mWidth * mHeight);
        for (int i = mWidth * mHeight; i + 1 < mConvert.length; i += 2) {
            mConvert[i] = nv21[i + 1];
            mConvert[i + 1] = nv21[i];
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
            buffer.clear();
            buffer.put(mConvert, 0, mConvert.length);
            // Presentation times must be relative to the start of the recording:
            // absolute values made the merged file report a bogus duration.
            long ptsUs = System.nanoTime() / 1000 - mStartTimeUs;
            mVideoEncoder.queueInputBuffer(index, 0, mConvert.length, ptsUs, 0);
            mVideoFrames++;
            drainEncoder(mVideoEncoder, false);
        } catch (IllegalStateException e) {
            Log.w(TAG, "video encoder gone", e);
        }
    }

    private void drainEncoder(MediaCodec encoder, boolean isAudio) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index;
            try {
                index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            } catch (IllegalStateException e) {
                return;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
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
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return;
            }
        }
    }

    private void maybeStartMuxer() {
        if (!mMuxerStarted && mVideoTrack >= 0 && (!mAudioEnabled || mAudioTrack >= 0)) {
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
        if (mAudioThread != null) {
            try {
                mAudioThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mAudioRecord != null) {
            try {
                mAudioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if (mVideoEncoder != null) {
            try {
                int index = mVideoEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                if (index >= 0) {
                    mVideoEncoder.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (IllegalStateException ignored) {
            }
            drainEncoder(mVideoEncoder, false);
        }
        Log.i(TAG, "stopped after " + mVideoFrames + " video frames ("
                + mDroppedFrames + " dropped)");
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
            copyTrack(video, muxer, outVideo, buffer, info);
            if (outAudio >= 0) {
                copyTrack(audio, muxer, outAudio, buffer, info);
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
        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                break;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags = extractor.getSampleFlags();
            muxer.writeSampleData(track, buffer, info);
            extractor.advance();
        }
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

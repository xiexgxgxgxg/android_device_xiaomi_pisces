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

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * pisces: the camera can only preview into a single surface, and video recording
 * has to feed the encoder through the recorder's surface.  This class takes the
 * camera preview into a SurfaceTexture and mirrors it into both the on-screen
 * surface and the recorder surface, so the viewfinder keeps running while the
 * video is being recorded.
 */
public class PreviewMirror implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "PreviewMirror";
    private static final int TEXTURE_ID = 10;

    private static final float[] QUAD = {
            -1.0f, -1.0f, 1.0f, 0.0f,
             1.0f, -1.0f, 0.0f, 0.0f,
            -1.0f,  1.0f, 1.0f, 1.0f,
             1.0f,  1.0f, 0.0f, 1.0f,
    };

    private static final String VERTEX_SHADER =
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    private final SurfaceTexture mTexture;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private EGLDisplay mEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mDisplayEgl = EGL14.EGL_NO_SURFACE;
    private EGLSurface mRecorderEgl = EGL14.EGL_NO_SURFACE;
    private Surface mRecorderSurface;

    private int mProgram = 0;
    private int mPositionHandle = 0;
    private int mTexCoordHandle = 0;
    private int mTexMatrixHandle = 0;
    private FloatBuffer mQuad;

    private final float[] mTexMatrix = new float[16];
    private boolean mReleased = false;

    public PreviewMirror() {
        mTexture = new SurfaceTexture(TEXTURE_ID);
        mTexture.setOnFrameAvailableListener(this);

        ByteBuffer bb = ByteBuffer.allocateDirect(QUAD.length * 4);
        bb.order(ByteOrder.nativeOrder());
        mQuad = bb.asFloatBuffer();
        mQuad.put(QUAD);
        mQuad.position(0);
    }

    public SurfaceTexture getSurfaceTexture() {
        return mTexture;
    }

    /** Sets the resolution the camera should render its preview at. */
    public void setDefaultBufferSize(int width, int height) {
        mTexture.setDefaultBufferSize(width, height);
    }

    /** Sets the surface that receives the frames for the encoder, if any. */
    public void setRecorderSurface(Surface surface) {
        mRecorderSurface = surface;
        if (surface != null) {
            mRecorderEgl = createWindowSurface(surface);
        } else if (mRecorderEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEglDisplay, mRecorderEgl);
            mRecorderEgl = EGL14.EGL_NO_SURFACE;
        }
    }

    /** Mirrors into the given on-screen surface. */
    public boolean start(Surface displaySurface) {
        if (!initEgl()) {
            return false;
        }
        mDisplayEgl = createWindowSurface(displaySurface);
        if (mDisplayEgl == EGL14.EGL_NO_SURFACE) {
            return false;
        }
        if (!initGl()) {
            return false;
        }
        mReleased = false;
        return true;
    }

    public void stop() {
        mReleased = true;
        if (mRecorderEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEglDisplay, mRecorderEgl);
            mRecorderEgl = EGL14.EGL_NO_SURFACE;
        }
        if (mDisplayEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(mEglDisplay, mDisplayEgl);
            mDisplayEgl = EGL14.EGL_NO_SURFACE;
        }
        mRecorderSurface = null;
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
        releaseEgl();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mHandler.post(this::drawFrame);
    }

    private void drawFrame() {
        if (mReleased || mProgram == 0) {
            return;
        }
        mTexture.updateTexImage();
        mTexture.getTransformMatrix(mTexMatrix);
        render(mDisplayEgl);
        if (mRecorderEgl != EGL14.EGL_NO_SURFACE) {
            render(mRecorderEgl);
        }
    }

    private void render(EGLSurface target) {
        if (target == EGL14.EGL_NO_SURFACE) {
            return;
        }
        if (!EGL14.eglMakeCurrent(mEglDisplay, target, target, mEglContext)) {
            Log.w(TAG, "eglMakeCurrent failed");
            return;
        }
        int[] width = new int[1];
        int[] height = new int[1];
        EGL14.eglQuerySurface(mEglDisplay, target, EGL14.EGL_WIDTH, width, 0);
        EGL14.eglQuerySurface(mEglDisplay, target, EGL14.EGL_HEIGHT, height, 0);
        GLES20.glViewport(0, 0, width[0], height[0]);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, TEXTURE_ID);
        GLES20.glUniformMatrix4fv(mTexMatrixHandle, 1, false, mTexMatrix, 0);
        mQuad.position(0);
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 16, mQuad);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        mQuad.position(2);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 16, mQuad);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glFinish();
        EGL14.eglSwapBuffers(mEglDisplay, target);
    }

    private boolean initEgl() {
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "no EGL display");
            return false;
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed");
            return false;
        }
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] == 0) {
            Log.e(TAG, "eglChooseConfig failed");
            return false;
        }
        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        return mEglContext != EGL14.EGL_NO_CONTEXT;
    }

    private EGLSurface createWindowSurface(Surface surface) {
        int[] attribs = {EGL14.EGL_NONE};
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(mEglDisplay,
                getConfig(), surface, attribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed");
        }
        return eglSurface;
    }

    private EGLConfig getConfig() {
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(mEglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0);
        return configs[0];
    }

    private boolean initGl() {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertex == 0 || fragment == 0) {
            return false;
        }
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertex);
        GLES20.glAttachShader(mProgram, fragment);
        GLES20.glLinkProgram(mProgram);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.e(TAG, "program link failed: " + GLES20.glGetProgramInfoLog(mProgram));
            return false;
        }
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        mTexMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uTexMatrix");
        return true;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private void releaseEgl() {
        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (mEglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mEglDisplay, mEglContext);
            }
            EGL14.eglTerminate(mEglDisplay);
        }
        mEglDisplay = EGL14.EGL_NO_DISPLAY;
        mEglContext = EGL14.EGL_NO_CONTEXT;
    }
}

package com.ominixisboss.androidboy;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Draws frames with OpenGL ES 3.0 through SameBoy's filter shaders (assets/shaders). */
final class GlScreenView extends GLSurfaceView implements GameScreen, GLSurfaceView.Renderer {
    private static final String TAG = "AndroidBoy";
    private static final String VERTEX_SHADER = "#version 300 es\n"
            + "in vec2 position;\n"
            + "void main() { gl_Position = vec4(position, 0.0, 1.0); }\n";

    private final Object lock = new Object();
    private final Map<Integer, Integer> programs = new HashMap<>();
    private final int[] textures = new int[2];
    private final FloatBuffer quad;
    private String masterSource;

    // Shared with the emulation and UI threads, guarded by lock.
    private Frame pendingFrame;
    private int filter;
    private int frameBlending;

    // GL thread only.
    private int currentTexture;
    private int textureWidth;
    private int textureHeight;
    private boolean lastFrameOdd;
    private int surfaceWidth;
    private int surfaceHeight;

    GlScreenView(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        try {
            masterSource = readAsset(context, "shaders/Master.glsl");
        } catch (IOException e) {
            Log.e(TAG, "Could not load the master shader", e);
        }
    }

    @Override
    public void setFrame(Frame frame) {
        synchronized (lock) {
            pendingFrame = frame;
        }
        requestRender();
    }

    @Override
    public void setFilter(int newFilter) {
        synchronized (lock) {
            filter = newFilter;
        }
        requestRender();
    }

    @Override
    public void setFrameBlending(int mode) {
        synchronized (lock) {
            frameBlending = mode;
        }
        requestRender();
    }

    @Override
    public View view() {
        return this;
    }

    // ---- Renderer (GL thread) ----

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // A new context: everything from the old one is gone.
        programs.clear();
        GLES30.glGenTextures(2, textures, 0);
        for (int texture : textures) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        }
        textureWidth = textureHeight = 0;
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
        // Show black until the first frame arrives.
        allocateTextures(160, 144);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        GLES30.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        Frame frame;
        int filterIndex;
        int blending;
        synchronized (lock) {
            frame = pendingFrame;
            pendingFrame = null;
            filterIndex = filter;
            blending = frameBlending;
        }

        if (frame != null) {
            if (frame.width != textureWidth || frame.height != textureHeight) {
                allocateTextures(frame.width, frame.height);
            }
            // The newest frame goes in one texture; the other keeps the previous frame for blending.
            currentTexture ^= 1;
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[currentTexture]);
            frame.pixels.position(0);
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, frame.width, frame.height,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, frame.pixels);
            lastFrameOdd = frame.odd;
        }

        int program = program(filterIndex);
        if (program == 0) {
            GLES30.glClearColor(0, 0, 0, 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        GLES30.glUseProgram(program);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[currentTexture]);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[currentTexture ^ 1]);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "image"), 0);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "previous_image"), 1);
        // Shader modes: 0 off, 1 simple, 2 accurate (even frame), 3 accurate (odd frame).
        int mode = blending == 2 ? (lastFrameOdd ? 3 : 2) : blending;
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "frame_blending_mode"), mode);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "output_resolution"), surfaceWidth, surfaceHeight);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "origin"), 0, 0);

        int position = GLES30.glGetAttribLocation(program, "position");
        GLES30.glEnableVertexAttribArray(position);
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, quad);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(position);
    }

    private void allocateTextures(int width, int height) {
        ByteBuffer black = ByteBuffer.allocateDirect(width * height * 4);
        for (int texture : textures) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, black);
        }
        textureWidth = width;
        textureHeight = height;
    }

    /** Compiles filters on first use; falls back to sharp pixels if one fails to build. */
    private int program(int filterIndex) {
        if (filterIndex < 0 || filterIndex >= Filters.FILES.length) filterIndex = 0;
        Integer cached = programs.get(filterIndex);
        if (cached != null) return cached;

        int program = 0;
        try {
            String filterSource = readAsset(getContext(), "shaders/" + Filters.FILES[filterIndex] + ".fsh");
            program = link(masterSource.replace("{filter}", filterSource));
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not load filter " + Filters.FILES[filterIndex], e);
        }
        if (program == 0 && filterIndex != 0) {
            program = program(0);
        }
        programs.put(filterIndex, program);
        return program;
    }

    private static int link(String fragmentSource) {
        int vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertex == 0 || fragment == 0) return 0;
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vertex);
        GLES30.glAttachShader(program, fragment);
        GLES30.glLinkProgram(program);
        GLES30.glDeleteShader(vertex);
        GLES30.glDeleteShader(fragment);
        int[] status = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Shader link failed: " + GLES30.glGetProgramInfoLog(program));
            GLES30.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static String readAsset(Context context, String path) throws IOException {
        try (InputStream in = context.getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}

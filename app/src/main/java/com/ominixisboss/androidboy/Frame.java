package com.ominixisboss.androidboy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** One emulated frame: tightly packed RGBA pixels plus what the renderer needs to know about it. */
final class Frame {
    static final int MAX_WIDTH = 256;
    static final int MAX_HEIGHT = 224;

    final ByteBuffer pixels = ByteBuffer.allocateDirect(MAX_WIDTH * MAX_HEIGHT * 4).order(ByteOrder.nativeOrder());
    int width = 160;
    int height = 144;
    /** Whether SameBoy flagged this as an odd frame, for accurate frame blending. */
    boolean odd;
}

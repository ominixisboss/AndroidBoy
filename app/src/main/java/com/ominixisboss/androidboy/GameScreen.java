package com.ominixisboss.androidboy;

import android.view.View;

/** Displays emulated frames. Implemented with OpenGL ES 3 (filters) or a plain canvas (fallback). */
interface GameScreen {
    /** Called from the emulation thread; the frame won't be rewritten for at least two more frames. */
    void setFrame(Frame frame);

    /** Index into {@link Filters#FILES}. Ignored by renderers that don't support filters. */
    void setFilter(int filter);

    /** One of the {@link Settings#FRAME_BLENDING} values: 0 off, 1 simple, 2 accurate. */
    void setFrameBlending(int mode);

    View view();

    void onResume();

    void onPause();
}

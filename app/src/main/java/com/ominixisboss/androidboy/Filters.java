package com.ominixisboss.androidboy;

/** SameBoy's screen filters, shipped unmodified in assets/shaders. Order matches {@link Settings#FILTER}. */
final class Filters {
    private Filters() {}

    static final String[] LABELS = {
            "None (sharp pixels)",
            "Bilinear",
            "Smooth bilinear",
            "LCD display",
            "Monochrome LCD",
            "CRT display",
            "Flat CRT display",
            "Scale2x",
            "Scale4x",
            "Anti-aliased Scale2x",
            "Anti-aliased Scale4x",
            "HQ2x",
            "OmniScale",
            "OmniScale Legacy",
            "Anti-aliased OmniScale Legacy",
    };

    static final String[] FILES = {
            "NearestNeighbor",
            "Bilinear",
            "SmoothBilinear",
            "LCD",
            "MonoLCD",
            "CRT",
            "FlatCRT",
            "Scale2x",
            "Scale4x",
            "AAScale2x",
            "AAScale4x",
            "HQ2x",
            "OmniScale",
            "OmniScaleLegacy",
            "AAOmniScaleLegacy",
    };
}

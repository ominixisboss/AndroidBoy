package com.ominixisboss.androidboy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cheat: a description and one or more GameShark or Game Genie codes joined by "+", as in
 * RetroArch's .cht files, which is also how a game's cheats are stored.
 */
final class Cheat {
    private static final Pattern LINE = Pattern.compile("^\\s*([A-Za-z0-9_]+)\\s*=\\s*(.*?)\\s*$");

    String description;
    String code;
    boolean enabled;

    Cheat(String description, String code, boolean enabled) {
        this.description = description;
        this.code = code;
        this.enabled = enabled;
    }

    /** The individual codes, tidied up (upper case, no spaces). */
    List<String> codes() {
        List<String> list = new ArrayList<>();
        for (String part : code.split("\\+")) {
            String normalized = normalize(part);
            if (!normalized.isEmpty()) list.add(normalized);
        }
        return list;
    }

    /** Whether every code is one the emulator understands; database entries can hold placeholders like "01??C0D5". */
    boolean isValid() {
        List<String> codes = codes();
        if (codes.isEmpty()) return false;
        String previous = "";
        for (String part : codes) {
            // A Game Boy Advance CodeBreaker code's second half: 8 digits, then 4.
            boolean secondHalf = part.length() == 4 && isHex(part) && previous.length() == 8 && isHex(previous);
            if (!secondHalf && !isValidCode(part)) return false;
            previous = part;
        }
        return true;
    }

    static String normalize(String code) {
        return code.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    /**
     * A GameShark code (8 hex digits: 01, value, address low byte, address high byte) or a Game
     * Genie code (6 or 9 hex digits, usually written VVA-AAA or VVA-AAA-OOO) that patches ROM.
     */
    static boolean isValidCode(String code) {
        code = normalize(code);
        if (code.length() == 8 && isHex(code)) return true;
        // Game Boy Advance: GameShark / Action Replay (8+8 digits), CodeBreaker (8+4), VBA (address:value).
        if ((code.length() == 16 || code.length() == 12) && isHex(code)) return true;
        int colon = code.indexOf(':');
        if (colon == 8 && isHex(code.substring(0, 8)) && code.length() > 9 && code.length() <= 17
                && isHex(code.substring(9))) {
            return true;
        }
        String digits = code.replace("-", "");
        if ((digits.length() != 6 && digits.length() != 9) || !isHex(digits)) return false;
        int encoded = Integer.parseInt(digits.substring(2, 6), 16);
        int address = (((encoded >> 4) | (encoded << 12)) & 0xFFFF) ^ 0xF000;
        return address <= 0x7FFF;
    }

    private static boolean isHex(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** The codes of the enabled, valid cheats, one per line, for {@link Emulator#nativeSetCheats}. */
    static String activeCodes(List<Cheat> cheats) {
        StringBuilder text = new StringBuilder();
        for (Cheat cheat : cheats) {
            if (!cheat.enabled || !cheat.isValid()) continue;
            for (String part : cheat.codes()) text.append(part).append('\n');
        }
        return text.toString();
    }

    /** Reads a .cht file. Entries without a code (section headings in the database) are left out. */
    static List<Cheat> parse(String text) {
        Map<String, String> values = new java.util.HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String value = m.group(2);
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(m.group(1).toLowerCase(Locale.ROOT), value);
        }
        // Collect by index rather than trusting "cheats = N", which some files get wrong.
        Map<Integer, Cheat> byIndex = new TreeMap<>();
        Pattern key = Pattern.compile("cheat(\\d+)_code");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Matcher m = key.matcher(entry.getKey());
            if (!m.matches() || entry.getValue().trim().isEmpty()) continue;
            int index;
            try {
                index = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String description = values.get("cheat" + index + "_desc");
            description = description == null || description.trim().isEmpty()
                    ? entry.getValue().trim() : unescapeHtml(description.trim());
            byIndex.put(index, new Cheat(description, entry.getValue().trim(),
                    "true".equalsIgnoreCase(values.get("cheat" + index + "_enable"))));
        }
        return new ArrayList<>(byIndex.values());
    }

    static String format(List<Cheat> cheats) {
        StringBuilder text = new StringBuilder();
        text.append("cheats = ").append(cheats.size()).append("\n");
        for (int i = 0; i < cheats.size(); i++) {
            Cheat cheat = cheats.get(i);
            text.append("\ncheat").append(i).append("_desc = \"").append(quoteSafe(cheat.description)).append("\"\n");
            text.append("cheat").append(i).append("_code = \"").append(quoteSafe(cheat.code)).append("\"\n");
            text.append("cheat").append(i).append("_enable = ").append(cheat.enabled).append("\n");
        }
        return text.toString();
    }

    static List<Cheat> load(File file) {
        if (!file.isFile()) return new ArrayList<>();
        try {
            return parse(new String(RomLibrary.readFile(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    static void save(File file, List<Cheat> cheats) throws IOException {
        if (cheats.isEmpty()) {
            file.delete();
            return;
        }
        RomLibrary.writeAtomically(file, format(cheats).getBytes(StandardCharsets.UTF_8));
    }

    /** Adds {@code incoming} to {@code cheats}, skipping codes already there. Returns how many were added. */
    static int merge(List<Cheat> cheats, List<Cheat> incoming) {
        int added = 0;
        for (Cheat cheat : incoming) {
            boolean duplicate = false;
            for (Cheat existing : cheats) {
                if (existing.codes().equals(cheat.codes())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                cheats.add(cheat);
                added++;
            }
        }
        return added;
    }

    // The .cht format has no escapes; keep values on one line and inside their quotes.
    private static String quoteSafe(String text) {
        return text.replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
    }

    // Some database descriptions carry HTML entities ("Monsters &amp; Party").
    static String unescapeHtml(String text) {
        return text.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}

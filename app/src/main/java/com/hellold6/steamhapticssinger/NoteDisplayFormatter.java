package com.hellold6.steamhapticssinger;

final class NoteDisplayFormatter {
    private static final String[] CHANNEL_LABELS = {
        "LEFT haptic : ",
        "RIGHT haptic : ",
        "LEFT haptic : ",
        "RIGHT haptic : "
    };
    private static final String[] NOTE_NAMES = {
        "C-", "C#", "D-", "D#", "E-", "F-",
        "F#", "G-", "G#", "A-", "A#", "B-"
    };

    private NoteDisplayFormatter() {
    }

    static String format(int[] activeNotes, int channelCount) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < channelCount; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(CHANNEL_LABELS[index]);
            int logicalChannel = index < 2 ? invertBinary(index) : invertBinary(index - 2) + 2;
            int note = logicalChannel < activeNotes.length ? activeNotes[logicalChannel] : SteamHapticsCommandEncoder.NOTE_STOP;
            builder.append(noteName(note));
        }
        return builder.toString();
    }

    static String noteName(int note) {
        if (note == SteamHapticsCommandEncoder.NOTE_STOP) {
            return "OFF";
        }
        int octave = (note / 12) - 1;
        return NOTE_NAMES[note % 12] + octave;
    }

    private static int invertBinary(int value) {
        return value == 0 ? 1 : 0;
    }
}

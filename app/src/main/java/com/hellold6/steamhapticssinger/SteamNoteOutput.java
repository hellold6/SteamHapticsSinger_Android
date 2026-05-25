package com.hellold6.steamhapticssinger;

import java.io.Closeable;
import java.io.IOException;

interface SteamNoteOutput extends Closeable {
    SteamControllerType getType();

    void sendNote(int channel, int note, int velocity, PlaybackOptions options) throws IOException;

    @Override
    void close() throws IOException;
}

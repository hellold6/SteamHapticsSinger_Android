package com.hellold6.steamhapticssinger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MidiFileParser {
    private static final int META_EVENT = 0xFF;
    private static final int META_TEMPO = 0x51;
    private static final int DEFAULT_TEMPO_US_PER_QUARTER = 500_000;

    private MidiFileParser() {
    }

    static PlaybackPlan parse(InputStream inputStream, String sourceName) throws IOException {
        return parse(inputStream.readAllBytes(), sourceName);
    }

    static PlaybackPlan parse(byte[] data, String sourceName) throws IOException {
        Cursor cursor = new Cursor(data);
        String header = cursor.readAscii(4);
        if (!"MThd".equals(header)) {
            throw new IOException("Selected file is not a Standard MIDI file.");
        }
        int headerLength = cursor.readInt();
        if (headerLength < 6) {
            throw new IOException("Invalid MIDI header length.");
        }
        int format = cursor.readUnsignedShort();
        int trackCount = cursor.readUnsignedShort();
        int division = cursor.readUnsignedShort();
        cursor.skip(headerLength - 6);

        if (format < 0 || format > 2) {
            throw new IOException("Unsupported MIDI format: " + format);
        }

        List<RawMidiEvent> noteEvents = new ArrayList<>();
        List<TempoChange> tempoChanges = new ArrayList<>();
        long serial = 0;

        for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
            if (!"MTrk".equals(cursor.readAscii(4))) {
                throw new IOException("Invalid MIDI track header.");
            }
            int trackLength = cursor.readInt();
            int trackEnd = cursor.position() + trackLength;
            long tick = 0;
            int runningStatus = -1;

            while (cursor.position() < trackEnd) {
                long delta = cursor.readVariableLength();
                tick += delta;
                int status = cursor.readUnsignedByte();
                int firstDataByte = -1;

                if (status < 0x80) {
                    if (runningStatus < 0) {
                        throw new IOException("Running status encountered without a previous status byte.");
                    }
                    firstDataByte = status;
                    status = runningStatus;
                } else if (status < 0xF0) {
                    runningStatus = status;
                }

                if (status == META_EVENT) {
                    int metaType = cursor.readUnsignedByte();
                    int length = (int) cursor.readVariableLength();
                    if (metaType == META_TEMPO && length == 3) {
                        int tempo = (cursor.readUnsignedByte() << 16)
                            | (cursor.readUnsignedByte() << 8)
                            | cursor.readUnsignedByte();
                        tempoChanges.add(new TempoChange(tick, tempo, serial++));
                    } else {
                        cursor.skip(length);
                    }
                    continue;
                }

                if (status == 0xF0 || status == 0xF7) {
                    int length = (int) cursor.readVariableLength();
                    cursor.skip(length);
                    continue;
                }

                int eventType = status & 0xF0;
                int channel = status & 0x0F;
                int data1 = firstDataByte >= 0 ? firstDataByte : cursor.readUnsignedByte();

                if (eventType == 0xC0 || eventType == 0xD0) {
                    continue;
                }

                int data2 = cursor.readUnsignedByte();
                if (eventType == 0x90) {
                    boolean isNoteOn = data2 > 0;
                    noteEvents.add(new RawMidiEvent(tick, channel, data1, data2, isNoteOn, serial++));
                } else if (eventType == 0x80) {
                    noteEvents.add(new RawMidiEvent(tick, channel, data1, data2, false, serial++));
                }
            }

            if (cursor.position() != trackEnd) {
                throw new IOException("MIDI track length mismatch.");
            }
        }

        if (noteEvents.isEmpty()) {
            throw new IOException("MIDI file does not contain any playable note events.");
        }

        noteEvents.sort(Comparator
            .comparingLong(RawMidiEvent::tick)
            .thenComparingLong(RawMidiEvent::serial));
        tempoChanges.sort(Comparator
            .comparingLong(TempoChange::tick)
            .thenComparingLong(TempoChange::serial));

        boolean isSmpte = (division & 0x8000) != 0;
        List<MidiPlaybackEvent> playbackEvents = new ArrayList<>(noteEvents.size());
        long durationMicros = 0;

        if (isSmpte) {
            int framesPerSecond = (byte) ((division >> 8) & 0xFF);
            int ticksPerFrame = division & 0xFF;
            double fps = Math.abs(framesPerSecond);
            if (fps == 29.0) {
                fps = 29.97;
            }
            double microsPerTick = 1_000_000.0 / (fps * ticksPerFrame);
            for (RawMidiEvent event : noteEvents) {
                long timeMicros = Math.round(event.tick() * microsPerTick);
                playbackEvents.add(new MidiPlaybackEvent(
                    event.tick(),
                    timeMicros,
                    event.channel(),
                    event.note(),
                    event.velocity(),
                    event.noteOn()
                ));
                durationMicros = Math.max(durationMicros, timeMicros);
            }
        } else {
            int resolution = division & 0x7FFF;
            int tempoIndex = 0;
            int currentTempo = DEFAULT_TEMPO_US_PER_QUARTER;
            long currentTick = 0;
            long currentTimeMicros = 0;

            for (RawMidiEvent event : noteEvents) {
                while (tempoIndex < tempoChanges.size() && tempoChanges.get(tempoIndex).tick() <= event.tick()) {
                    TempoChange tempoChange = tempoChanges.get(tempoIndex);
                    if (tempoChange.tick() > currentTick) {
                        currentTimeMicros += ((tempoChange.tick() - currentTick) * (long) currentTempo) / resolution;
                        currentTick = tempoChange.tick();
                    }
                    currentTempo = tempoChange.tempoMicrosPerQuarter();
                    tempoIndex++;
                }

                if (event.tick() > currentTick) {
                    currentTimeMicros += ((event.tick() - currentTick) * (long) currentTempo) / resolution;
                    currentTick = event.tick();
                }

                playbackEvents.add(new MidiPlaybackEvent(
                    event.tick(),
                    currentTimeMicros,
                    event.channel(),
                    event.note(),
                    event.velocity(),
                    event.noteOn()
                ));
                durationMicros = Math.max(durationMicros, currentTimeMicros);
            }
        }

        return new PlaybackPlan(sourceName, playbackEvents, durationMicros);
    }

    private record RawMidiEvent(long tick, int channel, int note, int velocity, boolean noteOn, long serial) {
    }

    private record TempoChange(long tick, int tempoMicrosPerQuarter, long serial) {
    }

    private static final class Cursor {
        private final byte[] data;
        private int position;

        private Cursor(byte[] data) {
            this.data = data;
        }

        int position() {
            return position;
        }

        String readAscii(int count) throws IOException {
            ensureAvailable(count);
            String value = new String(data, position, count);
            position += count;
            return value;
        }

        int readInt() throws IOException {
            ensureAvailable(4);
            int value = ((data[position] & 0xFF) << 24)
                | ((data[position + 1] & 0xFF) << 16)
                | ((data[position + 2] & 0xFF) << 8)
                | (data[position + 3] & 0xFF);
            position += 4;
            return value;
        }

        int readUnsignedShort() throws IOException {
            ensureAvailable(2);
            int value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            position += 2;
            return value;
        }

        int readUnsignedByte() throws IOException {
            ensureAvailable(1);
            return data[position++] & 0xFF;
        }

        long readVariableLength() throws IOException {
            long value = 0;
            int currentByte;
            do {
                currentByte = readUnsignedByte();
                value = (value << 7) | (currentByte & 0x7F);
            } while ((currentByte & 0x80) != 0);
            return value;
        }

        void skip(int count) throws IOException {
            ensureAvailable(count);
            position += count;
        }

        private void ensureAvailable(int count) throws IOException {
            if (position + count > data.length) {
                throw new IOException("Unexpected end of MIDI data.");
            }
        }
    }
}

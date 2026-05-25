package com.hellold6.steamhapticssinger;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class SteamHapticsPlayer {
    interface Listener {
        void onPlaybackStarted(String sourceName, SteamControllerType type, int channelCount);

        void onPlaybackStateChanged(String stateText);

        void onPlaybackLog(String message);

        void onPlaybackFinished(String message);

        void onPlaybackError(String message, Exception error);
    }

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread playbackThread;

    synchronized boolean isPlaying() {
        return playbackThread != null && playbackThread.isAlive();
    }

    synchronized void start(PlaybackPlan playbackPlan, PlaybackOptions options, SteamNoteOutput output, Listener listener) {
        if (isPlaying()) {
            throw new IllegalStateException("Playback is already running.");
        }

        stopRequested.set(false);
        Thread worker = new Thread(() -> runPlayback(playbackPlan, options, output, listener), "steam-haptics-player");
        playbackThread = worker;
        worker.start();
    }

    synchronized void stop() {
        stopRequested.set(true);
        if (playbackThread != null) {
            playbackThread.interrupt();
        }
    }

    private void runPlayback(PlaybackPlan playbackPlan, PlaybackOptions options, SteamNoteOutput output, Listener listener) {
        SteamControllerType type = output.getType();
        int channelCount = type.getChannelCount(options);
        int[] activeNotes = new int[4];
        long[] activeNoteTicks = new long[4];
        Arrays.fill(activeNotes, SteamHapticsCommandEncoder.NOTE_STOP);

        try (output) {
            listener.onPlaybackStarted(playbackPlan.getSourceName(), type, channelCount);
            do {
                Arrays.fill(activeNotes, SteamHapticsCommandEncoder.NOTE_STOP);
                Arrays.fill(activeNoteTicks, -1L);
                listener.onPlaybackStateChanged(NoteDisplayFormatter.format(activeNotes, channelCount));
                playOnce(playbackPlan.getEvents(), options, output, listener, channelCount, activeNotes, activeNoteTicks);
                silenceAllChannels(output, options, channelCount, activeNotes, listener);
            } while (options.isRepeatSong() && !stopRequested.get());

            String message = stopRequested.get() ? "Playback stopped." : "Playback completed.";
            listener.onPlaybackFinished(message);
        } catch (Exception error) {
            if (stopRequested.get() && error instanceof InterruptedException) {
                listener.onPlaybackFinished("Playback stopped.");
            } else {
                listener.onPlaybackError("Playback failed: " + error.getMessage(), error);
            }
        } finally {
            clearThread();
        }
    }

    private void playOnce(
        List<MidiPlaybackEvent> events,
        PlaybackOptions options,
        SteamNoteOutput output,
        Listener listener,
        int channelCount,
        int[] activeNotes,
        long[] activeNoteTicks
    ) throws IOException, InterruptedException {
        long loopStart = System.nanoTime();

        for (MidiPlaybackEvent event : events) {
            if (stopRequested.get()) {
                return;
            }

            long targetMicros = event.getTimeMicros();
            waitUntil(loopStart, targetMicros, options.getIntervalUsec());
            if (stopRequested.get()) {
                return;
            }

            int channel = event.getChannel();
            if (channel < 0 || channel >= channelCount) {
                continue;
            }

            if (event.isNoteOn()) {
                output.sendNote(channel, SteamHapticsCommandEncoder.NOTE_STOP, 0, options);
                output.sendNote(channel, event.getNote(), event.getVelocity(), options);
                activeNotes[channel] = event.getNote();
                activeNoteTicks[channel] = event.getTick();
                if (options.getDebugLevel() > 0) {
                    listener.onPlaybackLog("CH" + channel + " ON " + NoteDisplayFormatter.noteName(event.getNote()));
                }
            } else if (activeNotes[channel] == event.getNote() && activeNoteTicks[channel] != event.getTick()) {
                output.sendNote(channel, SteamHapticsCommandEncoder.NOTE_STOP, 0, options);
                activeNotes[channel] = SteamHapticsCommandEncoder.NOTE_STOP;
                activeNoteTicks[channel] = -1L;
                if (options.getDebugLevel() > 1) {
                    listener.onPlaybackLog("CH" + channel + " OFF " + NoteDisplayFormatter.noteName(event.getNote()));
                }
            }

            listener.onPlaybackStateChanged(NoteDisplayFormatter.format(activeNotes, channelCount));
        }
    }

    private void silenceAllChannels(
        SteamNoteOutput output,
        PlaybackOptions options,
        int channelCount,
        int[] activeNotes,
        Listener listener
    ) throws IOException {
        for (int channel = 0; channel < channelCount; channel++) {
            output.sendNote(channel, SteamHapticsCommandEncoder.NOTE_STOP, 0, options);
            activeNotes[channel] = SteamHapticsCommandEncoder.NOTE_STOP;
        }
        listener.onPlaybackStateChanged(NoteDisplayFormatter.format(activeNotes, channelCount));
    }

    private void waitUntil(long loopStartNanos, long targetMicros, int sleepGranularityMicros) throws InterruptedException {
        while (!stopRequested.get()) {
            long elapsedMicros = (System.nanoTime() - loopStartNanos) / 1_000L;
            long remainingMicros = targetMicros - elapsedMicros;
            if (remainingMicros <= 0) {
                return;
            }

            long sleepMicros = Math.min(remainingMicros, Math.max(1, sleepGranularityMicros));
            long millis = sleepMicros / 1_000L;
            int nanos = (int) (sleepMicros % 1_000L) * 1_000;
            Thread.sleep(millis, nanos);
        }
    }

    private synchronized void clearThread() {
        playbackThread = null;
    }
}

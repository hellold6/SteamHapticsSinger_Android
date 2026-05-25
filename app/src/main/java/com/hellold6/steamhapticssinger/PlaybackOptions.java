package com.hellold6.steamhapticssinger;

final class PlaybackOptions {
    private final int intervalUsec;
    private final int debugLevel;
    private final boolean repeatSong;
    private final boolean directVelocity;
    private final boolean tritonLimit;
    private final boolean tritonSwap;

    PlaybackOptions(
        int intervalUsec,
        int debugLevel,
        boolean repeatSong,
        boolean directVelocity,
        boolean tritonLimit,
        boolean tritonSwap
    ) {
        if (intervalUsec <= 0) {
            throw new IllegalArgumentException("Interval must be greater than 0.");
        }
        if (debugLevel < 0) {
            throw new IllegalArgumentException("Debug level cannot be negative.");
        }
        this.intervalUsec = intervalUsec;
        this.debugLevel = debugLevel;
        this.repeatSong = repeatSong;
        this.directVelocity = directVelocity;
        this.tritonLimit = tritonLimit;
        this.tritonSwap = tritonSwap;
    }

    int getIntervalUsec() {
        return intervalUsec;
    }

    int getDebugLevel() {
        return debugLevel;
    }

    boolean isRepeatSong() {
        return repeatSong;
    }

    boolean isDirectVelocity() {
        return directVelocity;
    }

    boolean isTritonLimit() {
        return tritonLimit;
    }

    boolean isTritonSwap() {
        return tritonSwap;
    }
}

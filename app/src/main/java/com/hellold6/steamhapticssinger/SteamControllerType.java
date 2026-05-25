package com.hellold6.steamhapticssinger;

enum SteamControllerType {
    NONE("No device"),
    ORIGINAL("Steam Controller (2015)"),
    TRITON("Steam Controller (2026)"),
    JUPITER("Steam Deck"),
    GALILEO("Steam Puck");

    private final String displayName;

    SteamControllerType(String displayName) {
        this.displayName = displayName;
    }

    String getDisplayName() {
        return displayName;
    }

    int getChannelCount(PlaybackOptions options) {
        if (this == TRITON || this == GALILEO) {
            return options.isTritonLimit() ? 2 : 4;
        }
        return 2;
    }
}

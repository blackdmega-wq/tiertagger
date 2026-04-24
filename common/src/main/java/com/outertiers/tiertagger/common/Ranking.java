package com.outertiers.tiertagger.common;

/**
 * One per-mode ranking entry. Tier level is 1..5 with 1 being the strongest.
 * {@code high} corresponds to MCTiers' {@code pos == 0} ("HT"); the inverse
 * is "LT". {@code retired == true} indicates a tier the player no longer
 * actively defends (rendered with parentheses on the profile screen).
 */
public final class Ranking {
    public final int     tierLevel;     // 1..5 (or 0 if unknown)
    public final boolean high;          // true => HT, false => LT
    public final int     peakLevel;     // 1..5
    public final boolean peakHigh;
    public final boolean retired;
    public final long    attained;      // unix seconds, 0 if unknown
    public final int     posInTier;     // numeric ranking within the tier (1-based, 0 if unknown)

    public Ranking(int tierLevel, boolean high, int peakLevel, boolean peakHigh,
                   boolean retired, long attained, int posInTier) {
        this.tierLevel = tierLevel;
        this.high      = high;
        this.peakLevel = peakLevel;
        this.peakHigh  = peakHigh;
        this.retired   = retired;
        this.attained  = attained;
        this.posInTier = posInTier;
    }

    /** Convenience constructor when only the current tier is known. */
    public static Ranking simple(int tierLevel, boolean high) {
        return new Ranking(tierLevel, high, tierLevel, high, false, 0L, 0);
    }

    /** Returns "HT3", "LT4", "" if level == 0. */
    public String label() {
        if (tierLevel <= 0) return "";
        return (high ? "HT" : "LT") + tierLevel;
    }

    /** Returns "HT3" / "LT4" for the peak. */
    public String peakLabel() {
        if (peakLevel <= 0) return "";
        return (peakHigh ? "HT" : "LT") + peakLevel;
    }

    /** True when the peak tier is strictly stronger than the current tier. */
    public boolean peakDiffers() {
        if (peakLevel <= 0) return false;
        if (peakLevel < tierLevel) return true;
        return peakLevel == tierLevel && peakHigh && !high;
    }

    /** Score where higher is better (HT1 highest). Compatible with {@link TierTaggerCore#score}. */
    public int score() {
        if (tierLevel <= 0) return -1;
        // HT_n -> (6 - n) * 2,  LT_n -> (6 - n) * 2 - 1   ;  HT1=10, LT1=9, HT2=8, ... LT5=1
        return (6 - tierLevel) * 2 - (high ? 0 : 1);
    }
}

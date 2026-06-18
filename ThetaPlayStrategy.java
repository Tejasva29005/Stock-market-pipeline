import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ThetaPlay: an intraday theta-harvesting strategy that sells two call legs
 * on a fixed 5-minute cadence and exits the entire position at 3:15 PM.
 *
 * Entry logic (fired at most once per 5-minute interval):
 *   1. Sell ATM call  — strike = round(currentPrice / STRIKE_STEP) * STRIKE_STEP
 *   2. Sell OTM call  — strike = ATM strike + OTM_OFFSET
 *
 * Exit logic:
 *   - At or after EXIT_TIME (15:15) a full CLOSE signal is emitted for the
 *     entire remaining position.
 *   - An optional stop-loss fires if IV spikes above IV_STOP_THRESHOLD,
 *     protecting against sudden vol expansion that crushes short-call P&L.
 *
 * Scheduling note:
 *   generateSignals() is assumed to be called on every market-data tick.
 *   The strategy self-throttles: it records the last entry timestamp and
 *   only opens new legs once ENTRY_INTERVAL_MS (5 min) has elapsed since
 *   the prior entry.
 *
 * DEMO — numbers are illustrative, NOT real trading logic.
 */
public class ThetaPlayStrategy implements Strategy {

    // ── Strike geometry ───────────────────────────────────────────────────────
    /** Normalise strikes to the nearest $1 grid (common for equity options). */
    private static final double STRIKE_STEP  = 1.0;

    /** Distance above ATM for the OTM short call ($). */
    private static final double OTM_OFFSET   = 3.0;

    // ── Timing ────────────────────────────────────────────────────────────────
    /** Minimum milliseconds between successive entry signals (5 minutes). */
    private static final long ENTRY_INTERVAL_MS = 5L * 60L * 1_000L;   // 300 000 ms

    /** Hard end-of-day exit time: 15:15:00. */
    private static final LocalTime EXIT_TIME = LocalTime.of(15, 15, 0);

    // ── Vol filter / stop ─────────────────────────────────────────────────────
    /**
     * Minimum IV required to enter — ensures we collect meaningful premium.
     * Below this threshold no new legs are opened.
     */
    private static final double IV_MIN_THRESHOLD = 0.10;   // 10 %

    /**
     * IV spike stop-loss: if IV climbs above this level after entry,
     * close everything immediately to cap losses on the naked short calls.
     */
    private static final double IV_STOP_THRESHOLD = 1.20;  // 120 %

    // ── Volume filter ─────────────────────────────────────────────────────────
    /** Minimum underlying daily volume; avoids illiquid underlyings. */
    private static final double MIN_VOLUME = 500_000.0;

    // ── State ─────────────────────────────────────────────────────────────────
    /** Wall-clock millis of the last time entry signals were generated. */
    private long lastEntryTimeMs = 0L;

    /** True once the EOD CLOSE has been emitted, so we don't re-emit it. */
    private boolean closedForDay = false;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Round {@code price} to the nearest multiple of {@code step}.
     * e.g. round(157.63, 1.0) → 158.0
     */
    private double roundToStep(double price, double step) {
        return Math.round(price / step) * step;
    }

    /**
     * Check whether the current wall-clock time is at or past the EOD exit.
     * Uses the system default time-zone (production systems should pin a zone).
     */
    private boolean isPastExitTime() {
        return !LocalTime.now().isBefore(EXIT_TIME);
    }

    @Override
    public List<Signal> generateSignals(MarketData data) {
        List<Signal> signals = new ArrayList<>();

        double price  = data.getPrice();
        double iv     = data.getImpliedVolatility();
        double volume = data.getVolume();
        String symbol = data.getSymbol();

        // ── 1. EOD exit (highest priority) ───────────────────────────────────
        if (isPastExitTime()) {
            if (!closedForDay) {
                // Close 100 % of any open position
                signals.add(new Signal(symbol, SignalType.EXIT, 1.0));
                closedForDay = true;
            }
            return signals;   // no new entries after EOD
        }

        // Reset the closed-for-day flag at the start of a new session
        // (simple heuristic: if we haven't emitted EOD yet, we're still open)
        closedForDay = false;

        // ── 2. IV spike stop-loss ─────────────────────────────────────────────
        if (iv >= IV_STOP_THRESHOLD) {
            signals.add(new Signal(symbol, SignalType.EXIT, 1.0));
            return signals;
        }

        // ── 3. 5-minute entry cadence check ──────────────────────────────────
        long nowMs = System.currentTimeMillis();
        boolean intervalElapsed = (nowMs - lastEntryTimeMs) >= ENTRY_INTERVAL_MS;

        if (!intervalElapsed) {
            return signals;   // too soon — wait for the next 5-min window
        }

        // ── 4. Pre-trade filters ──────────────────────────────────────────────
        if (iv < IV_MIN_THRESHOLD) {
            return signals;   // premium too thin to be worth selling
        }
        if (volume < MIN_VOLUME) {
            return signals;   // underlying too illiquid
        }

        // ── 5. Build entry signals ────────────────────────────────────────────
        double atmStrike = roundToStep(price, STRIKE_STEP);  // ATM call strike
        double otmStrike = atmStrike + OTM_OFFSET;           // OTM call strike

        // Leg 1: sell the ATM call — maximises theta decay
        signals.add(new Signal(symbol, SignalType.SELL_CALL, atmStrike));

        // Leg 2: sell the OTM call — additional premium, slightly less delta
        signals.add(new Signal(symbol, SignalType.SELL_CALL, otmStrike));

        // Record the timestamp so the next entry waits a full 5 minutes
        lastEntryTimeMs = nowMs;

        return signals;
    }
}

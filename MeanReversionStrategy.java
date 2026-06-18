import java.util.ArrayList;
import java.util.List;

/**
 * Mean Reversion Strategy: sells options (straddle / strangle) when implied
 * volatility rank (IV Rank) is elevated, exploiting the tendency of IV to
 * revert toward its historical mean after spikes.
 *
 * IV Rank proxy: because a live 52-week high/low window is not available
 * in MarketData, IV Rank is approximated as the ratio of implied volatility
 * to historical volatility (IV / HV).  A ratio above IV_RANK_THRESHOLD
 * (default 7.0, matching the spec) signals that options are richly priced
 * relative to realized volatility — the ideal time to sell premium.
 *
 * Entry:
 *   IV/HV ratio  > IV_RANK_THRESHOLD  — sell ATM straddle (call + put at spot)
 *   IV > IV_CALL_THRESHOLD (6) AND IV/HV ratio <= IV_RANK_THRESHOLD
 *                              — buy ATM call BEFORE IV falls below 6
 *
 * Position sizing:
 *   When the ratio is extreme (> IV_RANK_EXTREME) the position is scaled up
 *   by a configurable multiplier to take fuller advantage of the dislocation.
 *
 * Exit:
 *   1. Profit target : close when ratio compresses below IV_RANK_EXIT
 *      (IV has mean-reverted — edge is gone).
 *   2. Time stop     : close everything at or below EXIT_DTE days to expiry.
 *   3. Stop-loss     : close if IV/HV ratio has expanded beyond STOP_LOSS_RATIO
 *      (trade is moving against us; IV is still rising rather than reverting).
 *
 * DEMO — numbers are illustrative, NOT real trading logic.
 */
public class MeanReversionStrategy implements Strategy {

    // -----------------------------------------------------------------------
    // Entry / sizing parameters
    // -----------------------------------------------------------------------

    /** IV/HV ratio must exceed this level to open a position (spec: 7). */
    private static final double IV_RANK_THRESHOLD = 7.0;

    /**
     * When the ratio is above this "extreme" level the position is given a
     * larger notional weight — volatility is deeply mis-priced.
     */
    private static final double IV_RANK_EXTREME   = 10.0;

    /** Strike offset for converting the straddle into a strangle (0 = straddle). */
    private static final double STRANGLE_OFFSET   = 0.0;   // ATM straddle by default

    // -----------------------------------------------------------------------
    // Exit parameters
    // -----------------------------------------------------------------------

    /**
     * Profit-take: when the IV/HV ratio compresses below this threshold we
     * treat the mean-reversion trade as complete and close the position.
     */
    private static final double IV_RANK_EXIT      = 3.0;

    /**
     * Stop-loss: if the IV/HV ratio expands beyond this level after entry,
     * close the position to cap losses from further IV expansion.
     */
    private static final double STOP_LOSS_RATIO   = 14.0;

    /**
     * Time-based exit: close everything at or below this many days to expiry
     * to avoid gamma / pin risk near expiration.
     */
    private static final int    EXIT_DTE          = 21;

    /**
     * Buy-call threshold: when IV is still above this level (i.e. IV > 6) but
     * has not entered the premium-selling zone (IV/HV <= IV_RANK_THRESHOLD),
     * the strategy buys an ATM call to capture directional upside before IV
     * falls below 6 and call premiums become too cheap to justify entry.
     */
    private static final double IV_CALL_THRESHOLD = 6.0;

    // -----------------------------------------------------------------------
    // Signal generation
    // -----------------------------------------------------------------------

    @Override
    public List<Signal> generateSignals(MarketData data) {
        List<Signal> signals = new ArrayList<>();

        double price  = data.getPrice();
        double iv     = data.getImpliedVolatility();
        double hv     = data.getHistoricalVolatility();
        int    dte    = data.getDte();
        String symbol = data.getSymbol();

        // Guard against degenerate HV (avoid division by zero)
        if (hv <= 0.0) {
            return signals;
        }

        double ivRank = iv / hv;   // IV Rank proxy (IV relative to realized vol)

        // ------------------------------------------------------------------
        // Time-based exit — takes priority over everything else
        // ------------------------------------------------------------------
        if (dte <= EXIT_DTE) {
            signals.add(new Signal(symbol, SignalType.EXIT, 1.0));
            return signals;
        }

        // ------------------------------------------------------------------
        // Stop-loss exit — IV has expanded further; cut the loss
        // ------------------------------------------------------------------
        if (ivRank >= STOP_LOSS_RATIO) {
            signals.add(new Signal(symbol, SignalType.EXIT, 1.0));
            return signals;
        }

        // ------------------------------------------------------------------
        // Profit-take exit — IV has mean-reverted sufficiently
        // ------------------------------------------------------------------
        if (ivRank <= IV_RANK_EXIT) {
            signals.add(new Signal(symbol, SignalType.EXIT, 1.0));
            return signals;
        }

        // ------------------------------------------------------------------
        // Pre-emptive long call — buy before IV falls below 6
        // Fires when IV is still above IV_CALL_THRESHOLD (6) but the IV/HV
        // ratio is NOT in the premium-selling zone.  This captures directional
        // upside while call premiums are still reasonably priced, before IV
        // drops through the 6 floor and premiums collapse.
        // ------------------------------------------------------------------
        if (iv > IV_CALL_THRESHOLD && ivRank <= IV_RANK_THRESHOLD) {
            // ATM call at the current spot price
            signals.add(new Signal(symbol, SignalType.BUY_CALL, price));
            return signals;
        }

        // ------------------------------------------------------------------
        // Entry — sell premium when IV Rank proxy exceeds threshold (> 7)
        // ------------------------------------------------------------------
        if (ivRank > IV_RANK_THRESHOLD) {
            // ATM straddle: sell call and put at (approximately) the spot price.
            // A non-zero STRANGLE_OFFSET converts this to an OTM strangle.
            double callStrike = price + STRANGLE_OFFSET;
            double putStrike  = price - STRANGLE_OFFSET;

            if (ivRank > IV_RANK_EXTREME) {
                // Extreme IV dislocation: sell a wider strangle in addition to
                // the ATM straddle to collect even more premium.
                double extraOffset = price * 0.02;  // 2 % OTM wings
                signals.add(new Signal(symbol, SignalType.SELL_CALL, callStrike));
                signals.add(new Signal(symbol, SignalType.SELL_PUT,  putStrike));
                signals.add(new Signal(symbol, SignalType.SELL_CALL, callStrike + extraOffset));
                signals.add(new Signal(symbol, SignalType.SELL_PUT,  putStrike  - extraOffset));
            } else {
                // Standard entry: plain ATM straddle / strangle
                signals.add(new Signal(symbol, SignalType.SELL_CALL, callStrike));
                signals.add(new Signal(symbol, SignalType.SELL_PUT,  putStrike));
            }
        }

        return signals;
    }
}

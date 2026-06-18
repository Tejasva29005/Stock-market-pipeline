import java.util.ArrayList;
import java.util.List;

/**
 * Iron Condor: sells OTM call spread + OTM put spread simultaneously.
 * Profits from low-volatility, range-bound price action and time decay.
 *
 * Entry condition: implied volatility must exceed IV_THRESHOLD.
 * Higher IV means fatter premiums and a wider profitable range.
 * DEMO — numbers are illustrative, NOT real trading logic.
 */
public class IronCondorStrategy implements Strategy {

    private static final double WING_WIDTH   = 8.0;   // distance between strikes ($)
    private static final double IV_THRESHOLD = 0.85;  // min IV to enter (85 %)

    // V2.1 — Four-tier, fully data-driven profit-take ladder.
    // Each entry pairs a profit threshold (fraction of max profit captured)
    // with the fraction of the position to close once that threshold is hit.
    // Tiers are ordered from highest profit to lowest so the richest tier
    // reached is the one that fires. The fractions sum to 1.0 (100 %).
    private static final double[] PROFIT_TIERS = { 0.80, 0.60, 0.40, 0.20 };
    private static final double[] TIER_FRACTIONS = { 0.10, 0.20, 0.30, 0.40 };

    // Time-based exit: close everything remaining at or below this DTE
    private static final int    EXIT_DTE = 21;

    @Override
    public List<Signal> generateSignals(MarketData data) {
        List<Signal> signals = new ArrayList<>();
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        // Only enter when IV is elevated — ensures we collect meaningful premium
        if (iv > IV_THRESHOLD) {
            // sell call spread (short call closer to money, long call further OTM)
            signals.add(new Signal(data.getSymbol(), SignalType.SELL_CALL, price + WING_WIDTH));
            signals.add(new Signal(data.getSymbol(), SignalType.BUY_CALL,  price + 2 * WING_WIDTH));
            // sell put spread (short put closer to money, long put further OTM)
            signals.add(new Signal(data.getSymbol(), SignalType.SELL_PUT,  price - WING_WIDTH));
            signals.add(new Signal(data.getSymbol(), SignalType.BUY_PUT,   price - 2 * WING_WIDTH));
        }

        // Four-tier profit-take exit, plus a 21-DTE time stop.
        // Profit is expressed as the fraction of max profit currently captured.
        double profit = data.getProfitPct();
        int    dte    = data.getDaysToExpiration();

        // Time-based exit takes priority: at or below 21 DTE close the entire
        // remaining position regardless of where profit currently sits.
        if (dte <= EXIT_DTE) {
            signals.add(new Signal(data.getSymbol(), SignalType.CLOSE, 1.0));
        } else {
            // Data-driven profit-take ladder: tiers are ordered highest-first,
            // so the first threshold met fires and closes that tier's fraction.
            //   close 40 % at 20 % profit
            //   close 30 % at 40 % profit
            //   close 20 % at 60 % profit
            //   close 10 % at 80 % profit
            for (int i = 0; i < PROFIT_TIERS.length; i++) {
                if (profit >= PROFIT_TIERS[i]) {
                    signals.add(new Signal(data.getSymbol(), SignalType.CLOSE, TIER_FRACTIONS[i]));
                    break;
                }
            }
        }

        return signals;
    }
}

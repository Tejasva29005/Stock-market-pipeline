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

    // Three-tier profit-take thresholds (fraction of max profit captured)
    private static final double TIER1_PROFIT = 0.25;  // close 50 % of position here
    private static final double TIER2_PROFIT = 0.50;  // close another 25 % here
    private static final double TIER3_PROFIT = 0.75;  // close the rest here

    // Portion of the position closed at each tier
    private static final double TIER1_FRACTION = 0.50;
    private static final double TIER2_FRACTION = 0.25;
    private static final double TIER3_FRACTION = 0.25;

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

        // Three-tier profit-take exit, plus a 21-DTE time stop.
        // Profit is expressed as the fraction of max profit currently captured.
        double profit = data.getProfitPct();
        int    dte    = data.getDaysToExpiration();

        // Time-based exit takes priority: at or below 21 DTE close the entire
        // remaining position regardless of where profit currently sits.
        if (dte <= EXIT_DTE) {
            signals.add(new Signal(data.getSymbol(), SignalType.CLOSE, 1.0));
        } else {
            // Tier 3: at 75 % profit, close the remaining 25 % of the position.
            if (profit >= TIER3_PROFIT) {
                signals.add(new Signal(data.getSymbol(), SignalType.CLOSE, TIER3_FRACTION));
            }
            // Tier 2: at 50 % profit, close another 25 % of the position.
            else if (profit >= TIER2_PROFIT) {
                signals.add(new Signal(data.getSymbol(), SignalType.CLOSE, TIER2_FRACTION));
            }
            // Tier 1: at 25 % profit, close 50 % of the position.
            else if (profit >= TIER1_PROFIT) {
                signals.add(new Signal(data.getSymbol(), SignalType.CLOSE, TIER1_FRACTION));
            }
        }

        return signals;
    }
}

import java.util.ArrayList;
import java.util.List;

/**
 * Put Credit Spread: sells an OTM put and buys a further OTM put for protection.
 * Mildly bullish / neutral; profits from time decay and upward price movement.
 * DEMO — numbers are illustrative, NOT real trading logic.
 */
public class PutCreditSpreadStrategy implements Strategy {

    private static final double SPREAD_WIDTH  = 5.0;   // distance between strikes ($)
    private static final double DELTA_TARGET  = 0.30;  // target delta for the short put (~30-delta)
    private static final double IV_THRESHOLD  = 0.15;  // min IV to enter (15 %)

    @Override
    public List<Signal> generateSignals(MarketData data) {
        List<Signal> signals = new ArrayList<>();
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        // Enter when IV is elevated and market is not in free-fall
        if (iv > IV_THRESHOLD && price > 0) {
            // Approximate the 30-delta put strike using a simplified heuristic
            double shortStrike = price * (1.0 - DELTA_TARGET * 0.3);
            double longStrike  = shortStrike - SPREAD_WIDTH;

            signals.add(new Signal(data.getSymbol(), SignalType.SELL_PUT, shortStrike));
            signals.add(new Signal(data.getSymbol(), SignalType.BUY_PUT,  longStrike));
        }
        return signals;
    }
}

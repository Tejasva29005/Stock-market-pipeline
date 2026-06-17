// demo1
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

    private static final double WING_WIDTH   = 10.0;  // distance between strikes ($)
    private static final double IV_THRESHOLD = 0.20;  // min IV to enter (20 %)

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
        return signals;
    }
}

// MOCK placeholder — in production carries option-chain legs, Greeks, metadata
public class Signal {
    public final String symbol;
    public final SignalType type;
    public final double strike;

    public Signal(String symbol, SignalType type, double strike) {
        this.symbol = symbol;
        this.type = type;
        this.strike = strike;
    }
}

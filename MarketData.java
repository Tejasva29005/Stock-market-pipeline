// MOCK placeholder — in production wraps live option chain + NBBO data
public class MarketData {
    private final String symbol;
    private final double price;
    private final double impliedVolatility;

    public MarketData(String symbol, double price, double impliedVolatility) {
        this.symbol = symbol;
        this.price = price;
        this.impliedVolatility = impliedVolatility;
    }

    public String getSymbol() { return symbol; }
    public double getPrice() { return price; }
    public double getImpliedVolatility() { return impliedVolatility; }
}

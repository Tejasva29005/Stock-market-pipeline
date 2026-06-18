// MOCK placeholder — in production wraps live option chain + NBBO data
public class MarketData {
    private final String symbol;
    private final double price;
    private final double impliedVolatility;
    private final double historicalVolatility; // 30-day realized vol
    private final int    dte;                  // days to front-month expiration
    private final double volume;               // underlying daily volume

    // Backward-compatible 3-arg constructor — defaults: HV=0.85*IV, DTE=45, vol=1M
    public MarketData(String symbol, double price, double impliedVolatility) {
        this(symbol, price, impliedVolatility, impliedVolatility * 0.85, 45, 1_000_000.0);
    }

    public MarketData(String symbol, double price, double impliedVolatility,
                      double historicalVolatility, int dte, double volume) {
        this.symbol = symbol;
        this.price = price;
        this.impliedVolatility = impliedVolatility;
        this.historicalVolatility = historicalVolatility;
        this.dte = dte;
        this.volume = volume;
    }

    public String getSymbol()               { return symbol; }
    public double getPrice()                { return price; }
    public double getImpliedVolatility()    { return impliedVolatility; }
    public double getHistoricalVolatility() { return historicalVolatility; }
    public int    getDte()                  { return dte; }
    public double getVolume()               { return volume; }
}

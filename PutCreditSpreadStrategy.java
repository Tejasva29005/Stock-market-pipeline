import java.util.ArrayList;
import java.util.List;

/**
 * Put Credit Spread: sells an OTM put and buys a further OTM put for protection.
 * Mildly bullish / neutral; profits from time decay and upward price movement.
 * DEMO — numbers are illustrative, NOT real trading logic.
 */
public class PutCreditSpreadStrategy implements Strategy {

    private static final double SHORT_DELTA   = 0.20;  // target delta for the short put (~20-delta)
    private static final double LONG_DELTA    = 0.10;  // target delta for the long put (~10-delta)
    private static final double IV_THRESHOLD  = 0.15;  // min IV to enter (15 %)

    @Override
    public List<Signal> generateSignals(MarketData data) {
        List<Signal> signals = new ArrayList<>();
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        // Enter when IV is elevated and market is not in free-fall
        if (iv > IV_THRESHOLD && price > 0) {
            // Select strikes by target delta rather than fixed dollar offsets
            double shortStrike = findPutStrikeByDelta(price, iv, SHORT_DELTA);
            double longStrike  = findPutStrikeByDelta(price, iv, LONG_DELTA);

            signals.add(new Signal(data.getSymbol(), SignalType.SELL_PUT, shortStrike));
            signals.add(new Signal(data.getSymbol(), SignalType.BUY_PUT,  longStrike));
        }
        return signals;
    }

    /**
     * Estimate the OTM put strike whose absolute delta is closest to the target.
     *
     * Uses a simplified Black-Scholes approximation (zero rate, ~30-day horizon)
     * to map a target delta onto a strike below the current price. Lower target
     * deltas yield strikes that are further out of the money.
     *
     * @param price       underlying price
     * @param iv          implied volatility (annualised, e.g. 0.20 = 20%)
     * @param targetDelta absolute delta to target (e.g. 0.20 = 20-delta)
     * @return estimated put strike
     */
    private double findPutStrikeByDelta(double price, double iv, double targetDelta) {
        // ~30 calendar days expressed in years
        double t = 30.0 / 365.0;
        double sigma = (iv > 0) ? iv : 0.01;

        // For a put, |delta| = N(-d1). Invert to find d1 for the target delta.
        // N(-d1) = targetDelta  ->  -d1 = invNorm(targetDelta)  ->  d1 = -invNorm(targetDelta)
        double d1 = -inverseNormalCdf(targetDelta);

        // d1 = [ ln(S/K) + (sigma^2 / 2) * t ] / (sigma * sqrt(t))
        // Solve for K:
        //   ln(S/K) = d1 * sigma * sqrt(t) - (sigma^2 / 2) * t
        //   K = S / exp( d1 * sigma * sqrt(t) - (sigma^2 / 2) * t )
        double sqrtT = Math.sqrt(t);
        double lnRatio = d1 * sigma * sqrtT - (sigma * sigma / 2.0) * t;
        double strike = price / Math.exp(lnRatio);

        // Round to the nearest whole dollar to approximate a listed strike
        return Math.round(strike);
    }

    /**
     * Inverse of the standard normal cumulative distribution (probit function).
     * Acklam's rational approximation; accurate to ~1e-9 over (0,1).
     *
     * @param p probability in (0, 1)
     * @return z such that N(z) = p
     */
    private double inverseNormalCdf(double p) {
        if (p <= 0.0) {
            return -Double.MAX_VALUE;
        }
        if (p >= 1.0) {
            return Double.MAX_VALUE;
        }

        // Coefficients for Acklam's algorithm
        final double[] a = {
            -3.969683028665376e+01,  2.209460984245205e+02,
            -2.759285104469687e+02,  1.383577518672690e+02,
            -3.066479806614716e+01,  2.506628277459239e+00
        };
        final double[] b = {
            -5.447609879822406e+01,  1.615858368580409e+02,
            -1.556989798598866e+02,  6.680131188771972e+01,
            -1.328068155288572e+01
        };
        final double[] c = {
            -7.784894002430293e-03, -3.223964580411365e-01,
            -2.400758277161838e+00, -2.549732539343734e+00,
             4.374664141464968e+00,  2.938163982698783e+00
        };
        final double[] d = {
             7.784695709041462e-03,  3.224671290700398e-01,
             2.445134137142996e+00,  3.754408661907416e+00
        };

        final double pLow  = 0.02425;
        final double pHigh = 1.0 - pLow;

        double x;
        if (p < pLow) {
            double q = Math.sqrt(-2.0 * Math.log(p));
            x = (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        } else if (p <= pHigh) {
            double q = p - 0.5;
            double r = q * q;
            x = (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
        } else {
            double q = Math.sqrt(-2.0 * Math.log(1.0 - p));
            x = -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        return x;
    }
}

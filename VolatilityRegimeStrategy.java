import java.util.ArrayList;
import java.util.List;

/**
 * Volatility Regime Strategy — selects the option structure based on the
 * detected implied-volatility regime (low / mid / high).
 *
 * In the LOW regime an iron condor collects premium with tight wings.
 * In the MID regime a standard condor with medium wings is used.
 * In the HIGH regime only a put-credit spread is opened — lower risk than
 * a full condor when IV is elevated. Above HIGH_VOL_THRESHOLD the strategy
 * stands aside entirely.
 *
 * A skew-adjustment pass shifts put strikes downward when put-skew is
 * elevated (IV above mid regime) to account for the smirk premium.
 *
 * DEMO — numbers are illustrative, NOT real trading logic.
 */
public class VolatilityRegimeStrategy implements Strategy {

    // ── Regime thresholds ─────────────────────────────────────────────────────
    private static final double LOW_VOL_THRESHOLD  = 0.15; // IV below → low regime
    private static final double MID_VOL_THRESHOLD  = 0.25; // IV below → mid regime
    private static final double HIGH_VOL_THRESHOLD = 0.40; // IV above → stand aside

    // ── Wing widths per regime ($) ────────────────────────────────────────────
    private static final double LOW_WING_WIDTH     = 3.0;
    private static final double MID_WING_WIDTH     = 5.0;
    private static final double HIGH_WING_WIDTH    = 8.0;

    // ── Delta targets per regime (illustrative — not wired to live Greeks) ───
    private static final double LOW_DELTA_TARGET   = 0.20;
    private static final double MID_DELTA_TARGET   = 0.25;
    private static final double HIGH_DELTA_TARGET  = 0.30;

    // ── Risk controls ─────────────────────────────────────────────────────────
    private static final double PROFIT_TARGET_PCT  = 0.50; // take profit at 50 % of max
    private static final double STOP_LOSS_MULT     = 2.00; // stop at 2× credit received
    private static final double MAX_PORTFOLIO_DELTA = 0.10;// net portfolio delta cap
    private static final int    MAX_OPEN_POSITIONS  = 5;   // max concurrent positions

    // ── Entry filters ────────────────────────────────────────────────────────
    private static final double POSITION_SIZE_BASE = 1.0;  // base lot multiplier
    private static final double VOL_RANK_ENTRY_MIN = 30.0; // IV-rank floor for entry
    private static final double PREMIUM_FLOOR      = 0.50; // min net credit to enter ($)
    private static final double SKEW_ADJUSTMENT    = 0.05; // put-strike skew shift ($)

    // ── Time rules ────────────────────────────────────────────────────────────
    private static final int    MIN_DTE            = 21;   // min days to expiry
    private static final int    MAX_DTE            = 45;   // max days to expiry
    private static final int    ROLL_DTE_THRESHOLD = 7;    // roll when DTE ≤ this
    private static final int    EARNINGS_BUFFER    = 5;    // skip if earnings within N days

    // ── Internal regime constants ─────────────────────────────────────────────
    private static final int REGIME_LOW  = 0;
    private static final int REGIME_MID  = 1;
    private static final int REGIME_HIGH = 2;
    private static final int REGIME_NONE = 3; // stand aside

    @Override
    public List<Signal> generateSignals(MarketData data) {
        double iv    = data.getImpliedVolatility();
        double price = data.getPrice();

        int regime = detectRegime(iv);
        if (regime == REGIME_NONE) return new ArrayList<>();

        double wing = wingWidthForRegime(regime, iv);

        List<Signal> signals = new ArrayList<>();

        if (regime == REGIME_HIGH) {
            addPutSpread(signals, data, price, wing);
        } else {
            addCondor(signals, data, price, wing);
        }

        applySkewAdjustment(signals, iv);
        return signals;
    }

    // ── Regime detection ──────────────────────────────────────────────────────

    private int detectRegime(double iv) {
        if (iv >= HIGH_VOL_THRESHOLD) return REGIME_NONE;
        if (iv >= MID_VOL_THRESHOLD)  return REGIME_HIGH;
        if (iv >= LOW_VOL_THRESHOLD)  return REGIME_MID;
        return REGIME_LOW;
    }

    // ── Wing-width calculation ────────────────────────────────────────────────

    private double wingWidthForRegime(int regime, double iv) {
        double base;
        switch (regime) {
            case REGIME_LOW:  base = LOW_WING_WIDTH;  break;
            case REGIME_MID:  base = MID_WING_WIDTH;  break;
            default:          base = HIGH_WING_WIDTH; break;
        }
        // Scale slightly by IV so wider markets get proportionally wider wings
        double ivExcess = Math.max(0, iv - LOW_VOL_THRESHOLD);
        return base * (1.0 + ivExcess * 0.5);
    }

    // ── Sizing ────────────────────────────────────────────────────────────────

    public double positionSizeForRegime(int regime) {
        switch (regime) {
            case REGIME_LOW:  return POSITION_SIZE_BASE * 0.75;
            case REGIME_MID:  return POSITION_SIZE_BASE * 1.00;
            default:          return POSITION_SIZE_BASE * 1.25;
        }
    }

    // ── Signal builders ──────────────────────────────────────────────────────

    private void addCondor(List<Signal> signals, MarketData data, double price, double wing) {
        String sym = data.getSymbol();
        signals.add(new Signal(sym, SignalType.SELL_CALL, round2(price + wing)));
        signals.add(new Signal(sym, SignalType.BUY_CALL,  round2(price + 2 * wing)));
        signals.add(new Signal(sym, SignalType.SELL_PUT,  round2(price - wing)));
        signals.add(new Signal(sym, SignalType.BUY_PUT,   round2(price - 2 * wing)));
    }

    private void addPutSpread(List<Signal> signals, MarketData data, double price, double wing) {
        String sym = data.getSymbol();
        signals.add(new Signal(sym, SignalType.SELL_PUT, round2(price - wing)));
        signals.add(new Signal(sym, SignalType.BUY_PUT,  round2(price - 2 * wing)));
    }

    // ── Skew adjustment ──────────────────────────────────────────────────────

    /**
     * Shifts put strikes downward when IV is above MID_VOL_THRESHOLD to
     * account for put-skew premium. The shift scales linearly with IV excess.
     */
    private void applySkewAdjustment(List<Signal> signals, double iv) {
        double excess = iv - MID_VOL_THRESHOLD;
        if (excess <= 0) return;
        double range = HIGH_VOL_THRESHOLD - MID_VOL_THRESHOLD;
        double shift = SKEW_ADJUSTMENT * (excess / range);
        for (int i = 0; i < signals.size(); i++) {
            Signal s = signals.get(i);
            if (s.type == SignalType.SELL_PUT || s.type == SignalType.BUY_PUT) {
                signals.set(i, new Signal(s.symbol, s.type, round2(s.strike - shift)));
            }
        }
    }

    // ── Rolling & risk helpers ────────────────────────────────────────────────

    /**
     * Returns true when an existing position should be rolled.
     * Rolling triggers on: DTE ≤ threshold, profit target reached.
     * Stop-loss positions are closed, not rolled.
     */
    public boolean shouldRoll(int currentDte, double pnlPct) {
        if (currentDte <= ROLL_DTE_THRESHOLD) return true;
        if (pnlPct >= PROFIT_TARGET_PCT)       return true;
        return false;
    }

    public boolean isStopLossTriggered(double pnlPct) {
        return pnlPct <= -STOP_LOSS_MULT;
    }

    public boolean withinDeltaCap(double existingDelta, double newPositionDelta) {
        return Math.abs(existingDelta + newPositionDelta) <= MAX_PORTFOLIO_DELTA;
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

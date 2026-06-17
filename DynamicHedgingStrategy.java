import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic Delta-Hedging Strategy — opens a near-delta-neutral strangle and
 * rebalances continuously as delta drifts outside a configurable band.
 *
 * Core flow:
 *  1. Entry gate: IV must be within [MIN_IV_ENTRY, MAX_IV_ENTRY].
 *  2. Initial position: sell a call + sell a put at configurable OTM offsets.
 *  3. IV spike detection: if IV ≥ IV_SPIKE_THRESHOLD, add long option wings
 *     to cap vega exposure during extreme moves.
 *  4. Delta rebalancing: if net delta breaches TARGET_DELTA ± DELTA_BAND_WIDTH,
 *     generate adjustment signals to restore neutrality.
 *  5. Gamma scalping: when estimated gamma exceeds the threshold and
 *     unrealised P&L covers transaction costs, scalp the move.
 *  6. Risk exits: emergency-exit on large loss or runaway IV;
 *     profit-lock roll when P&L exceeds the threshold.
 *
 * DEMO — numbers are illustrative, NOT real trading logic.
 */
public class DynamicHedgingStrategy implements Strategy {

    // ── Core delta targets ────────────────────────────────────────────────────
    private static final double TARGET_DELTA        = 0.00;  // delta-neutral target
    private static final double DELTA_BAND_WIDTH    = 0.05;  // rebalance outside ±0.05
    private static final double MAX_STRIKE_DISTANCE = 20.0;  // max OTM distance cap ($)

    // ── Initial strangle leg offsets ──────────────────────────────────────────
    private static final double CALL_STRIKE_OFFSET  = 5.0;   // initial short call OTM ($)
    private static final double PUT_STRIKE_OFFSET   = 5.0;   // initial short put OTM ($)

    // ── Adjustment leg offsets ────────────────────────────────────────────────
    private static final double ADJ_CALL_OFFSET     = 2.5;   // rebalancing call OTM ($)
    private static final double ADJ_PUT_OFFSET      = 2.5;   // rebalancing put OTM ($)

    // ── Greek limits ──────────────────────────────────────────────────────────
    private static final double GAMMA_SENSITIVITY   = 0.02;  // min gamma to consider scalp
    private static final double VEGA_LIMIT          = 0.50;  // max net vega per position
    private static final double THETA_DAILY_MIN     = -0.10; // min acceptable theta ($/day)

    // ── IV entry filters ──────────────────────────────────────────────────────
    private static final double MIN_IV_ENTRY        = 0.15;  // skip below 15 % IV
    private static final double MAX_IV_ENTRY        = 0.60;  // skip above 60 % IV
    private static final double IV_SPIKE_THRESHOLD  = 0.40;  // trigger wing-add above this

    // ── Risk & profit controls ────────────────────────────────────────────────
    private static final double EMERGENCY_LOSS      = -5.00; // forced exit at –$5/share
    private static final double PROFIT_LOCK_THRESHOLD =  3.00;// lock profit at +$3/share
    private static final double MIN_PREMIUM_PER_LEG =  0.25; // skip legs below this credit

    // ── Trade-management parameters ───────────────────────────────────────────
    private static final int    MAX_ADJUSTMENTS     = 3;     // max delta-rebalances per day
    private static final double ADJUSTMENT_COST_MAX = 0.10;  // max cost per rebalance ($)
    private static final double HEDGE_RATIO         = 1.00;  // options per share hedged
    private static final double PORTFOLIO_BETA      = 1.00;  // beta for index-hedge sizing
    private static final double ROLL_PROFIT_CAPTURE = 0.70;  // roll when 70 % profit captured
    private static final int    REBALANCE_FREQ_MIN  = 30;    // min minutes between rebalances

    @Override
    public List<Signal> generateSignals(MarketData data) {
        double iv    = data.getImpliedVolatility();
        double price = data.getPrice();
        List<Signal> signals = new ArrayList<>();

        if (iv < MIN_IV_ENTRY || iv > MAX_IV_ENTRY) return signals;

        // Cap strike distances to MAX_STRIKE_DISTANCE
        double callStrike = Math.min(price + CALL_STRIKE_OFFSET,
                                     price + MAX_STRIKE_DISTANCE);
        double putStrike  = Math.max(price - PUT_STRIKE_OFFSET,
                                     price - MAX_STRIKE_DISTANCE);

        signals.add(new Signal(data.getSymbol(), SignalType.SELL_CALL, round2(callStrike)));
        signals.add(new Signal(data.getSymbol(), SignalType.SELL_PUT,  round2(putStrike)));

        // Add protective long wings on an IV spike
        if (iv >= IV_SPIKE_THRESHOLD) {
            signals.addAll(spikeWings(data, price));
        }
        return signals;
    }

    // ── Spike-wing generation ─────────────────────────────────────────────────

    private List<Signal> spikeWings(MarketData data, double price) {
        List<Signal> wings = new ArrayList<>();
        wings.add(new Signal(data.getSymbol(), SignalType.BUY_CALL,
                round2(price + CALL_STRIKE_OFFSET + ADJ_CALL_OFFSET)));
        wings.add(new Signal(data.getSymbol(), SignalType.BUY_PUT,
                round2(price - PUT_STRIKE_OFFSET - ADJ_PUT_OFFSET)));
        return wings;
    }

    // ── Delta rebalancing ─────────────────────────────────────────────────────

    /**
     * Returns the signed delta imbalance that exceeds the band.
     * A value of 0.0 means no rebalancing is needed.
     */
    public double deltaImbalance(double currentDelta) {
        double excess = currentDelta - TARGET_DELTA;
        return Math.abs(excess) > DELTA_BAND_WIDTH ? excess : 0.0;
    }

    /**
     * Builds rebalancing signals for a given signed delta imbalance.
     * Positive imbalance (too long) → sell call to reduce delta.
     * Negative imbalance (too short) → sell put to increase delta.
     */
    public List<Signal> rebalanceSignals(MarketData data, double imbalance) {
        List<Signal> signals = new ArrayList<>();
        double price = data.getPrice();
        if (imbalance > 0) {
            signals.add(new Signal(data.getSymbol(), SignalType.SELL_CALL,
                    round2(price + ADJ_CALL_OFFSET)));
        } else if (imbalance < 0) {
            signals.add(new Signal(data.getSymbol(), SignalType.SELL_PUT,
                    round2(price - ADJ_PUT_OFFSET)));
        }
        return signals;
    }

    // ── Gamma scalping ────────────────────────────────────────────────────────

    /**
     * Returns true when a gamma scalp is warranted.
     * Conditions: estimated gamma exceeds threshold AND
     * unrealised P&L covers the expected adjustment cost.
     */
    public boolean shouldGammaScalp(double gamma, double unrealisedPnl) {
        return gamma >= GAMMA_SENSITIVITY && unrealisedPnl > ADJUSTMENT_COST_MAX;
    }

    // ── Risk management ──────────────────────────────────────────────────────

    public boolean isEmergencyExit(double positionPnl, double iv) {
        return positionPnl <= EMERGENCY_LOSS || iv > MAX_IV_ENTRY;
    }

    public boolean shouldLockProfit(double positionPnl) {
        return positionPnl >= PROFIT_LOCK_THRESHOLD;
    }

    public boolean shouldRollForProfitCapture(double pnlPct) {
        return pnlPct >= ROLL_PROFIT_CAPTURE;
    }

    public int adjustmentsRemaining(int doneToday) {
        return Math.max(0, MAX_ADJUSTMENTS - doneToday);
    }

    // ── Portfolio hedging ─────────────────────────────────────────────────────

    public double requiredHedgeNotional(double equityExposure) {
        return equityExposure * PORTFOLIO_BETA * HEDGE_RATIO;
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

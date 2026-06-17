import java.util.ArrayList;
import java.util.List;

/**
 * Gamma Scalping Strategy — long gamma / delta-neutral options strategy
 * designed to profit from large price moves in either direction.
 *
 * Core mechanic: Purchases at-the-money straddles (long call + long put at the
 * same strike) when implied volatility is relatively cheap versus expected
 * realised volatility. The position then generates scalping profits by
 * repeatedly delta-hedging back to neutral as the underlying moves.
 *
 * Signal generation pipeline:
 *  1. Validate entry: IV must be below VOL_OF_VOL-adjusted threshold
 *  2. Compute gamma opportunity score from IV surface shape
 *  3. Determine straddle entry strike (ATM, rounded to STRIKE_ROUNDING)
 *  4. Compute initial delta hedge quantity to make the portfolio delta-neutral
 *  5. On subsequent calls, re-hedge when delta drifts beyond REBALANCE_BAND
 *  6. Exit when theta bleed exceeds THETA_MAX_DAILY_LOSS, or when IV spikes
 *     above entry IV × VEGA_EXIT_MULTIPLE (take profit on the vol expansion),
 *     or when the position has been held for MAX_HOLD_DAYS
 *
 * DEMO — all values are illustrative. Not financial advice.
 */
public class GammaScalpingStrategy implements Strategy {

    // ── Position phase lifecycle ──────────────────────────────────────────────

    private enum PositionPhase {
        FLAT,         // no open position
        STRADDLE_ON,  // long straddle in place, awaiting hedge rebalance
        HEDGING,      // actively generating delta-hedge signals this bar
        EXITING       // position being unwound
    }

    // ── State tracker ─────────────────────────────────────────────────────────

    private static class ScalpState {
        PositionPhase phase        = PositionPhase.FLAT;
        double  straddleStrike     = 0.0;  // ATM strike at entry
        double  entryIV            = 0.0;  // IV at time of straddle entry
        double  entryPrice         = 0.0;  // underlying price at entry
        double  currentDelta       = 0.0;  // estimated net delta of the position
        double  cumulativeScalp    = 0.0;  // total P&L attributed to delta scalps
        double  thetaAccrued       = 0.0;  // total theta cost accrued (negative)
        int     daysHeld           = 0;    // age of position in trading days
        int     hedgeCount         = 0;    // number of delta rebalances performed
        double  maxIVSeen          = 0.0;  // trailing maximum IV since entry (for trailing stop)
    }

    private final ScalpState state = new ScalpState();

    // ── Entry parameters ──────────────────────────────────────────────────────

    /** Maximum IV allowed at entry; above this, straddles are too expensive. */
    private static final double IV_ENTRY_MAX           = 0.35;
    /** Minimum IV at entry; below this, gamma opportunity is insufficient. */
    private static final double IV_ENTRY_MIN           = 0.12;
    /** Minimum ratio of IV / historical-vol to justify entry (HV > IV needed). */
    private static final double IV_TO_HV_RATIO_MIN     = 0.75;
    /** Historical (realised) volatility estimate for the underlying. */
    private static final double HIST_VOL_ESTIMATE      = 0.20;
    /** Vol-of-vol threshold: if vvix proxy exceeds this, skew too uncertain. */
    private static final double VOL_OF_VOL_THRESHOLD   = 0.30;
    /** Maximum allowable bid-ask IV spread at entry (filters illiquid options). */
    private static final double IV_SPREAD_ENTRY_MAX    = 0.03;

    // ── Strike selection parameters ───────────────────────────────────────────

    /** Strike is rounded to the nearest multiple of this value. */
    private static final double STRIKE_ROUNDING        = 1.0;
    /** Overpay limit: straddle price must not exceed this fraction of spot price. */
    private static final double STRADDLE_PRICE_CAP_PCT = 0.06;

    // ── Position sizing parameters ────────────────────────────────────────────

    /** Fraction of portfolio NAV to allocate to the straddle (premium paid). */
    private static final double PORTFOLIO_ALLOC_PCT    = 0.015;
    /** Notional portfolio size for sizing calculations. */
    private static final double PORTFOLIO_NAV          = 100_000.0;
    /** Hard maximum number of straddle contracts. */
    private static final double MAX_CONTRACTS          = 5.0;
    /** Minimum number of contracts (avoid tiny positions). */
    private static final double MIN_CONTRACTS          = 1.0;
    /** Option contract multiplier (standard = 100 shares). */
    private static final double CONTRACT_MULTIPLIER    = 100.0;

    // ── Delta hedging parameters ──────────────────────────────────────────────

    /** Rebalance the delta hedge when net delta exceeds this band (in delta units). */
    private static final double REBALANCE_DELTA_BAND   = 0.08;
    /** Target net delta after each rebalance (0.0 = delta neutral). */
    private static final double TARGET_DELTA           = 0.0;
    /** Maximum delta hedge size per rebalance (fraction of underlying notional). */
    private static final double MAX_HEDGE_SIZE_PCT     = 0.50;

    // ── Exit parameters ───────────────────────────────────────────────────────

    /** Exit if daily theta bleed exceeds this fraction of the initial premium paid. */
    private static final double THETA_MAX_DAILY_LOSS   = 0.03;
    /** Take profit on IV expansion: exit if current IV > entry IV × this multiple. */
    private static final double VEGA_EXIT_MULTIPLE     = 1.40;
    /** Stop-loss on IV contraction: exit if current IV < entry IV × this multiple. */
    private static final double VEGA_STOP_MULTIPLE     = 0.70;
    /** Exit if cumulative scalp P&L exceeds this multiple of the original premium. */
    private static final double SCALP_PROFIT_MULTIPLE  = 1.50;
    /** Maximum days to hold the position regardless of outcome. */
    private static final int    MAX_HOLD_DAYS          = 30;
    /** Close unconditionally if DTE (estimated) drops below this. */
    private static final int    DTE_CLOSE_MIN          = 3;

    // ── Gamma measurement parameters ─────────────────────────────────────────

    /** Minimum gamma opportunity score (0–1) required to justify entry. */
    private static final double GAMMA_OPP_SCORE_MIN    = 0.40;
    /** Weight of IV dispersion in the gamma opportunity score. */
    private static final double GAMMA_IV_DISP_WEIGHT   = 0.50;
    /** Weight of price-action (ATM proximity) in the gamma opportunity score. */
    private static final double GAMMA_ATM_WEIGHT       = 0.50;

    // ── Main signal generation ────────────────────────────────────────────────

    @Override
    public List<Signal> generateSignals(MarketData data) {
        List<Signal> signals = new ArrayList<>();
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        if (price <= 0.0 || iv <= 0.0) {
            return signals;
        }

        // Advance position age each bar
        if (state.phase != PositionPhase.FLAT) {
            state.daysHeld++;
            state.maxIVSeen = Math.max(state.maxIVSeen, iv);
        }

        switch (state.phase) {
            case FLAT:
                return tryEntry(data, signals);

            case STRADDLE_ON:
            case HEDGING:
                // Check exit conditions first
                if (shouldExit(data)) {
                    generateExitSignals(data, signals);
                    return signals;
                }
                // Re-hedge if delta has drifted
                if (needsRebalance(data)) {
                    generateHedgeSignals(data, signals);
                }
                return signals;

            case EXITING:
                generateExitSignals(data, signals);
                return signals;

            default:
                return signals;
        }
    }

    // ── Entry logic ───────────────────────────────────────────────────────────

    /**
     * Attempt to enter a new straddle position.
     *
     * Validates all entry conditions before generating entry signals:
     *   - IV must be within the [IV_ENTRY_MIN, IV_ENTRY_MAX] window
     *   - IV / HV ratio must be below IV_TO_HV_RATIO_MIN (buying cheap vol)
     *   - Vol-of-vol proxy must be below VOL_OF_VOL_THRESHOLD
     *   - Gamma opportunity score must exceed GAMMA_OPP_SCORE_MIN
     *   - Straddle price must not exceed STRADDLE_PRICE_CAP_PCT of spot
     */
    private List<Signal> tryEntry(MarketData data, List<Signal> signals) {
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        // Gate 1: IV window check
        if (iv < IV_ENTRY_MIN || iv > IV_ENTRY_MAX) {
            return signals;
        }

        // Gate 2: IV must be cheap relative to historical vol
        double ivToHV = iv / HIST_VOL_ESTIMATE;
        if (ivToHV > (1.0 / IV_TO_HV_RATIO_MIN)) {
            return signals; // IV too expensive relative to realised vol
        }

        // Gate 3: Volatility-of-volatility proxy (from IV and price distance)
        double vovProxy = computeVolOfVolProxy(data);
        if (vovProxy > VOL_OF_VOL_THRESHOLD) {
            return signals; // IV surface too unstable for reliable gamma scalp
        }

        // Gate 4: Gamma opportunity score
        double gammaScore = computeGammaOpportunityScore(data);
        if (gammaScore < GAMMA_OPP_SCORE_MIN) {
            return signals;
        }

        // Gate 5: Straddle price reasonableness check
        double straddlePrice = estimateStraddlePrice(price, iv);
        if (straddlePrice > price * STRADDLE_PRICE_CAP_PCT) {
            return signals; // straddle too expensive as a fraction of spot
        }

        // Compute position size
        double contracts = computeStraddleSize(price, iv);

        // ATM strike selection
        double atmStrike = roundToStrike(price);

        // Enter the long straddle (long call + long put at same strike)
        for (int i = 0; i < (int) contracts; i++) {
            signals.add(new Signal(data.getSymbol(), SignalType.BUY_CALL, atmStrike));
            signals.add(new Signal(data.getSymbol(), SignalType.BUY_PUT,  atmStrike));
        }

        // Record entry state
        state.phase          = PositionPhase.STRADDLE_ON;
        state.straddleStrike = atmStrike;
        state.entryIV        = iv;
        state.entryPrice     = price;
        state.currentDelta   = 0.0; // ATM straddle starts delta-neutral
        state.daysHeld       = 0;
        state.hedgeCount     = 0;
        state.cumulativeScalp = 0.0;
        state.thetaAccrued   = 0.0;
        state.maxIVSeen      = iv;

        // Immediately queue the initial delta hedge (price may not be exactly ATM)
        generateHedgeSignals(data, signals);

        return signals;
    }

    // ── Delta hedging ─────────────────────────────────────────────────────────

    /**
     * Determine whether the position's net delta has drifted beyond the rebalance band.
     *
     * The net delta drifts as the underlying moves away from the straddle strike.
     * When |currentDelta| > REBALANCE_DELTA_BAND, we generate a hedge trade.
     */
    private boolean needsRebalance(MarketData data) {
        double estimatedDelta = estimateDeltaExposure(data);
        return Math.abs(estimatedDelta) > REBALANCE_DELTA_BAND;
    }

    /**
     * Generate delta hedge signals to bring the net delta back to TARGET_DELTA.
     *
     * Hedge instrument: we treat the underlying as the hedge vehicle (buy/sell
     * the underlying stock/future to offset option delta).
     *
     * In the demo framework we encode hedge direction via SELL_CALL / BUY_CALL
     * signals with the underlying strike (price) to distinguish from option signals.
     * In production this would be a separate hedge-order type.
     *
     * The hedge size is clamped to MAX_HEDGE_SIZE_PCT of underlying notional.
     */
    private void generateHedgeSignals(MarketData data, List<Signal> signals) {
        double price         = data.getPrice();
        double currentDelta  = estimateDeltaExposure(data);
        double hedgeDelta    = TARGET_DELTA - currentDelta;

        // Convert delta to share-equivalent hedge size
        double hedgeShares   = hedgeDelta * CONTRACT_MULTIPLIER;
        double maxHedge      = price * MAX_HEDGE_SIZE_PCT * CONTRACT_MULTIPLIER;
        hedgeShares          = Math.min(Math.abs(hedgeShares), maxHedge) * Math.signum(hedgeShares);

        if (Math.abs(hedgeShares) < 1.0) {
            return; // hedge too small to execute
        }

        // In production: route to underlying book. In demo: approximate with option signals
        if (hedgeShares > 0) {
            // Buy underlying (positive hedge = need to buy to increase delta)
            signals.add(new Signal(data.getSymbol(), SignalType.BUY_CALL, price));
        } else {
            // Sell underlying (negative hedge = need to sell to decrease delta)
            signals.add(new Signal(data.getSymbol(), SignalType.SELL_CALL, price));
        }

        state.currentDelta  = TARGET_DELTA;
        state.hedgeCount++;
        state.phase          = PositionPhase.HEDGING;
        state.cumulativeScalp += Math.abs(hedgeShares) * price * 0.001; // scalp P&L proxy
    }

    // ── Exit conditions ───────────────────────────────────────────────────────

    /**
     * Evaluate whether the straddle position should be closed.
     *
     * Exit triggers (any one is sufficient):
     *   1. Vega take-profit: IV rose above entry IV × VEGA_EXIT_MULTIPLE
     *   2. Vega stop-loss:   IV fell below entry IV × VEGA_STOP_MULTIPLE
     *   3. Scalp profit target: cumulative scalp P&L ≥ initial premium × SCALP_PROFIT_MULTIPLE
     *   4. Theta bleed: daily theta cost exceeds THETA_MAX_DAILY_LOSS of premium
     *   5. Maximum hold period: daysHeld ≥ MAX_HOLD_DAYS
     *   6. DTE too short: risk of pin/binary outcome near expiry
     */
    private boolean shouldExit(MarketData data) {
        double iv = data.getImpliedVolatility();

        // Exit 1: Vega take-profit (IV expanded — sell the inflated straddle)
        if (iv > state.entryIV * VEGA_EXIT_MULTIPLE) {
            return true;
        }

        // Exit 2: Vega stop-loss (IV contracted — straddle lost value without enough scalping)
        if (iv < state.entryIV * VEGA_STOP_MULTIPLE) {
            return true;
        }

        // Exit 3: Scalp profit target achieved
        double initialPremium  = estimateStraddlePrice(state.entryPrice, state.entryIV);
        double targetScalpProfit = initialPremium * CONTRACT_MULTIPLIER * SCALP_PROFIT_MULTIPLE;
        if (state.cumulativeScalp >= targetScalpProfit) {
            return true;
        }

        // Exit 4: Theta bleed exceeds daily loss tolerance
        double dailyTheta = computeDailyTheta(data);
        if (Math.abs(dailyTheta) > initialPremium * THETA_MAX_DAILY_LOSS * CONTRACT_MULTIPLIER) {
            return true;
        }

        // Exit 5: Maximum holding period
        if (state.daysHeld >= MAX_HOLD_DAYS) {
            return true;
        }

        // Exit 6: DTE proxy too short
        int estimatedDTE = MAX_HOLD_DAYS - state.daysHeld;
        if (estimatedDTE <= DTE_CLOSE_MIN) {
            return true;
        }

        return false;
    }

    /**
     * Generate signals to close the entire straddle (sell the call + sell the put).
     * Also cancels any outstanding delta hedge by re-hedging back to flat.
     */
    private void generateExitSignals(MarketData data, List<Signal> signals) {
        if (state.phase == PositionPhase.FLAT) return;

        double price = data.getPrice();

        // Sell back the long call and long put
        signals.add(new Signal(data.getSymbol(), SignalType.SELL_CALL, state.straddleStrike));
        signals.add(new Signal(data.getSymbol(), SignalType.SELL_PUT,  state.straddleStrike));

        // Close delta hedge: buy back or sell any outstanding hedge shares
        if (Math.abs(state.currentDelta) > 0.01) {
            if (state.currentDelta < 0) {
                signals.add(new Signal(data.getSymbol(), SignalType.BUY_CALL, price));
            } else {
                signals.add(new Signal(data.getSymbol(), SignalType.SELL_CALL, price));
            }
        }

        // Reset state
        state.phase         = PositionPhase.FLAT;
        state.currentDelta  = 0.0;
        state.daysHeld      = 0;
        state.hedgeCount    = 0;
        state.cumulativeScalp = 0.0;
    }

    // ── Gamma opportunity scoring ─────────────────────────────────────────────

    /**
     * Compute a composite gamma opportunity score in [0, 1].
     *
     * Score components:
     *   A) IV dispersion premium: rewards low IV relative to HIST_VOL_ESTIMATE.
     *      Score_A = clamp(1 - IV/HV, 0, 1)  — higher when IV < HV
     *
     *   B) ATM proximity: rewards when the current price is close to a round
     *      strike (ATM options have maximum gamma).
     *      Score_B = 1 - min(1, fractional_distance_to_nearest_strike)
     *
     * Composite = GAMMA_IV_DISP_WEIGHT × Score_A + GAMMA_ATM_WEIGHT × Score_B
     */
    private double computeGammaOpportunityScore(MarketData data) {
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        // Component A: IV vs. historical vol
        double ivToHV    = iv / HIST_VOL_ESTIMATE;
        double scoreA    = clamp(1.0 - ivToHV, 0.0, 1.0);

        // Component B: proximity to nearest whole-dollar strike
        double nearStrike  = roundToStrike(price);
        double distToATM   = Math.abs(price - nearStrike) / Math.max(price, 1.0);
        double scoreB      = 1.0 - Math.min(1.0, distToATM * 100.0); // distance scaled

        return GAMMA_IV_DISP_WEIGHT * scoreA + GAMMA_ATM_WEIGHT * scoreB;
    }

    /**
     * Estimate a vol-of-vol proxy from the relationship between IV and the
     * price distance from the straddle strike.
     *
     * A high vol-of-vol environment makes gamma scalping less predictable since
     * the IV surface itself is unstable. We proxy this as the relative deviation
     * of IV from its recent "expected" level given the current price.
     */
    private double computeVolOfVolProxy(MarketData data) {
        double iv    = data.getImpliedVolatility();
        double price = data.getPrice();
        double expectedIV = HIST_VOL_ESTIMATE * (1.0 + 0.10 * Math.sin(price * 0.01));
        double ivDeviation = Math.abs(iv - expectedIV) / Math.max(expectedIV, 0.001);
        return ivDeviation;
    }

    // ── Greeks and pricing helpers ────────────────────────────────────────────

    /**
     * Estimate the net delta of the straddle position given current price.
     *
     * ATM straddle delta is approximately zero at inception. As price moves:
     *   - If price rises above the strike: call delta increases, put delta decreases
     *     → net delta becomes positive
     *   - If price falls below the strike: put delta increases, call delta decreases
     *     → net delta becomes negative
     *
     * Simplified: delta ≈ (price - strike) / (IV × price × √(T/252))
     * Clamped to [-0.5, 0.5] per leg for a long straddle (both calls and puts long).
     */
    private double estimateDeltaExposure(MarketData data) {
        if (state.phase == PositionPhase.FLAT) return 0.0;
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();
        double dteRemain = Math.max(1.0, MAX_HOLD_DAYS - (double) state.daysHeld);
        double sigma = iv * Math.sqrt(dteRemain / 252.0) * price;
        if (sigma <= 0.0) return 0.0;
        double rawDelta = (price - state.straddleStrike) / sigma;
        // Long straddle: call delta + put delta; call = N(d1), put = N(d1)-1
        // Net delta = N(d1) + (N(d1) - 1) = 2N(d1) - 1 ≈ 2 × rawDelta for small d1
        return clamp(2.0 * rawDelta * 0.40, -0.50, 0.50);
    }

    /**
     * Estimate the daily theta (time decay) cost for the long straddle.
     *
     * Theta is negative for long options (we pay theta as time passes).
     * Approximate: theta ≈ -IV × price / (2 × √(252 × DTE))
     *
     * For a straddle: both legs contribute theta, so multiply by 2.
     * The returned value is in dollars per day per unit (one contract's total theta).
     */
    private double computeDailyTheta(MarketData data) {
        double iv       = data.getImpliedVolatility();
        double price    = data.getPrice();
        double dteRemain = Math.max(1.0, MAX_HOLD_DAYS - (double) state.daysHeld);
        // Per-leg theta approximation (negative for long options)
        double perLegTheta = -(iv * price) / (2.0 * Math.sqrt(252.0 * dteRemain));
        // Straddle has two legs; multiply by CONTRACT_MULTIPLIER for dollar theta
        return perLegTheta * 2.0 * CONTRACT_MULTIPLIER;
    }

    /**
     * Estimate the fair-value price of an ATM straddle.
     *
     * Simplified formula (Bachelier ATM straddle approximation):
     *   straddle price ≈ 2 × IV × spot × √(T/252) / √(2π)
     *                  = IV × spot × √(T/252) × 0.7979
     * where √(2/π) ≈ 0.7979.
     *
     * This is used to validate STRADDLE_PRICE_CAP_PCT and for P&L tracking.
     */
    private double estimateStraddlePrice(double spot, double iv) {
        double T = MAX_HOLD_DAYS / 252.0;
        return iv * spot * Math.sqrt(T) * 0.7979;
    }

    /**
     * Compute the break-even daily move required for the gamma scalping strategy
     * to cover its theta bleed.
     *
     * Break-even = IV × √(1 day / 252) × spot  (one-day one-sigma move)
     * If the actual realised daily move exceeds this, the position profits from gamma.
     *
     * Returned as a dollar amount (the minimum absolute price move needed per day).
     */
    private double computeBreakevenMove(MarketData data) {
        double iv    = data.getImpliedVolatility();
        double price = data.getPrice();
        return iv * Math.sqrt(1.0 / 252.0) * price;
    }

    // ── Position sizing ───────────────────────────────────────────────────────

    /**
     * Compute the number of straddle contracts to purchase.
     *
     * Method: allocate PORTFOLIO_ALLOC_PCT of NAV to pay for the straddle premium.
     *   raw_contracts = alloc_budget / (straddle_price × CONTRACT_MULTIPLIER)
     *
     * Clamped to [MIN_CONTRACTS, MAX_CONTRACTS].
     */
    private double computeStraddleSize(double price, double iv) {
        double allocBudget    = PORTFOLIO_NAV * PORTFOLIO_ALLOC_PCT;
        double straddlePrice  = estimateStraddlePrice(price, iv);
        double costPerContract = straddlePrice * CONTRACT_MULTIPLIER;
        if (costPerContract <= 0.0) return MIN_CONTRACTS;
        double rawContracts = allocBudget / costPerContract;
        return clamp(Math.floor(rawContracts), MIN_CONTRACTS, MAX_CONTRACTS);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Round a raw price to the nearest STRIKE_ROUNDING increment.
     */
    private double roundToStrike(double rawStrike) {
        return Math.round(rawStrike / STRIKE_ROUNDING) * STRIKE_ROUNDING;
    }

    /**
     * Clamp {@code value} to the closed interval [lo, hi].
     */
    private double clamp(double value, double lo, double hi) {
        return Math.min(hi, Math.max(lo, value));
    }
}

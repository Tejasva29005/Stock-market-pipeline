import java.util.ArrayList;
import java.util.List;

/**
 * Adaptive Iron Condor Strategy — multi-leg premium collection strategy
 * that dynamically adjusts to the prevailing market regime.
 *
 * Core mechanic: Sells an OTM call spread and OTM put spread simultaneously.
 * The strategy adapts strike distances, position sizes, and exit thresholds
 * based on detected market regime (trending vs. mean-reverting vs. high-vol).
 *
 * Signal generation pipeline:
 *  1. Detect market regime via trend-strength and volatility metrics
 *  2. Compute IV percentile rank to gate entry
 *  3. Select strikes dynamically based on regime and target delta
 *  4. Size the position based on portfolio risk budget and current volatility
 *  5. Apply exit-rule checks (profit target, stop-loss, time decay, gamma risk)
 *  6. Evaluate rolling logic if the short leg is being tested
 *  7. Apply volatility-skew adjustments to the put side
 *
 * DEMO — all values are illustrative. Not financial advice.
 */
public class AdaptiveIronCondorStrategy implements Strategy {

    // ── Market regime classification ──────────────────────────────────────────

    private enum MarketRegime {
        TRENDING_UP,    // strong upward momentum: widen put spread, tighten call side
        TRENDING_DOWN,  // strong downward momentum: widen call spread, tighten put side
        RANGE_BOUND,    // low ADX, price contained: standard condor geometry
        HIGH_VOL_CRUSH, // IV spike with mean-reversion expected: tighter spreads
        UNKNOWN
    }

    // ── Per-position state machine ────────────────────────────────────────────

    private static class PositionState {
        boolean inTrade         = false;
        double  shortCallStrike = 0.0;
        double  longCallStrike  = 0.0;
        double  shortPutStrike  = 0.0;
        double  longPutStrike   = 0.0;
        double  entryPrice      = 0.0;
        double  entryIV         = 0.0;
        double  entryCredit     = 0.0;
        int     daysInTrade     = 0;
        int     rollCount       = 0;
        double  maxFavourableCredit = 0.0; // trailing high-water for profit management
    }

    private final PositionState state = new PositionState();

    // ── Core entry parameters ─────────────────────────────────────────────────

    /** Minimum IV rank (0–100 scale) to allow a new entry. */
    private static final double IV_RANK_MIN_ENTRY      = 40.0;
    /** IV rank above which we classify the environment as high-volatility. */
    private static final double IV_RANK_HIGH_VOL       = 75.0;
    /** Number of trading days used as the IV lookback window. */
    private static final double IV_PERCENTILE_LOOKBACK = 252.0;
    /** Assumed historical mean of annualised IV (calibrate to the underlying). */
    private static final double IV_HIST_MEAN           = 0.22;
    /** Assumed historical standard deviation of annualised IV. */
    private static final double IV_HIST_STD            = 0.08;

    // ── Strike selection parameters ───────────────────────────────────────────

    /** Target delta for the short call leg (0.16 = roughly 16-delta). */
    private static final double DELTA_TARGET_SHORT_CALL = 0.16;
    /** Target delta for the short put leg. */
    private static final double DELTA_TARGET_SHORT_PUT  = 0.16;
    /** Target delta for the long call wing (protection). */
    private static final double DELTA_TARGET_LONG_CALL  = 0.05;
    /** Target delta for the long put wing (protection). */
    private static final double DELTA_TARGET_LONG_PUT   = 0.05;
    /** Minimum allowable wing width in underlying price dollars. */
    private static final double WING_WIDTH_MIN          = 3.0;
    /** Maximum allowable wing width in underlying price dollars. */
    private static final double WING_WIDTH_MAX          = 15.0;
    /** Default wing width when no regime adjustment is applied. */
    private static final double WING_WIDTH_DEFAULT      = 5.0;
    /** Strike rounding increment (1.0 = round to nearest dollar). */
    private static final double STRIKE_ROUNDING         = 1.0;

    // ── Position sizing parameters ────────────────────────────────────────────

    /** Fraction of portfolio NAV to risk on any single condor position. */
    private static final double PORTFOLIO_RISK_PCT = 0.02;
    /** Notional portfolio size in dollars for sizing calculations. */
    private static final double PORTFOLIO_NAV      = 100_000.0;
    /** Hard maximum number of contracts per position. */
    private static final double MAX_CONTRACTS      = 10.0;
    /** Minimum number of contracts to trade (avoid fractional). */
    private static final double MIN_CONTRACTS      = 1.0;
    /** Minimum net credit (dollars per share) to justify entry. */
    private static final double CREDIT_MIN         = 0.50;

    // ── Exit and management parameters ───────────────────────────────────────

    /** Close when profit reaches this fraction of the initial credit received. */
    private static final double PROFIT_TAKE_PCT      = 0.50;
    /** Close when unrealised loss reaches this multiple of the initial credit. */
    private static final double STOP_LOSS_MULTIPLIER = 2.0;
    /** Exit unconditionally when estimated days-to-expiry drops below this. */
    private static final int    DAYS_TO_EXPIRY_MIN    = 7;
    /** Target DTE for new entries; used as the position duration clock baseline. */
    private static final int    DAYS_TO_EXPIRY_TARGET = 45;
    /** No new entries are allowed when DTE is strictly below this threshold. */
    private static final int    DTE_NO_ENTRY          = 14;
    /** Exit if portfolio gamma exceeds this threshold (net short gamma risk). */
    private static final double GAMMA_RISK_THRESHOLD  = 0.08;
    /** Exit if net vega dollar exposure exceeds this absolute value. */
    private static final double VEGA_EXPOSURE_MAX     = 500.0;

    // ── Rolling parameters ────────────────────────────────────────────────────

    /** Maximum number of times a position may be rolled before forcing close. */
    private static final int    MAX_ROLL_COUNT      = 2;
    /** Roll a leg when its breach fraction of the range exceeds this value. */
    private static final double ROLL_TRIGGER_DELTA  = 0.30;
    /** Only roll if the net credit from the roll is at least this value. */
    private static final double ROLL_CREDIT_MIN     = 0.10;

    // ── Regime detection parameters ───────────────────────────────────────────

    /** ADX proxy threshold above which the market is considered trending. */
    private static final double ADX_THRESHOLD_TREND    = 25.0;
    /** Window (in volatility time units) for computing momentum proxy. */
    private static final double MOMENTUM_WINDOW        = 10.0;
    /** Fraction of the put skew premium applied as a strike adjustment. */
    private static final double SKEW_ADJUSTMENT_FACTOR = 0.60;

    // ── Multi-timeframe regime weights and thresholds (V0 enhancement) ────────

    /**
     * Weight applied to the daily-timeframe trend score when computing the
     * blended regime score.  Weekly weight = 1.0 - DAILY_TREND_WEIGHT.
     */
    private static final double DAILY_TREND_WEIGHT  = 0.60;

    /**
     * Blended score strictly above this threshold means both timeframes agree
     * on a bullish / upward-trending environment.  Entry proceeds normally.
     */
    private static final double REGIME_SCORE_BULL   = 0.70;

    /**
     * Blended score strictly below this threshold means both timeframes agree
     * on a bearish / downward-trending environment.  Entry proceeds normally.
     */
    private static final double REGIME_SCORE_BEAR   = 0.30;

    /**
     * Number of dollars added to (or subtracted from) the short-strike OTM
     * distance on each side when timeframes disagree and strikes are widened.
     * One "step" = STRIKE_ROUNDING × WING_WIDTH_WIDEN_STEPS.
     */
    private static final double WING_WIDTH_WIDEN_STEPS = 2.0;

    /**
     * Position-size multiplier applied when timeframes disagree (mixed regime).
     * The position is halved to reduce exposure to directional uncertainty.
     */
    private static final double MIXED_REGIME_SIZE_FACTOR = 0.50;

    // ── Main signal generation ────────────────────────────────────────────────

    @Override
    public List<Signal> generateSignals(MarketData data) {
        List<Signal> signals = new ArrayList<>();
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        if (price <= 0.0 || iv <= 0.0) {
            return signals;
        }

        // Gate 1: IV rank must be elevated enough to justify selling premium
        double ivLow  = IV_HIST_MEAN - 2.0 * IV_HIST_STD;
        double ivHigh = IV_HIST_MEAN + 2.0 * IV_HIST_STD;
        double ivRank = computeIVRank(iv, ivLow, ivHigh);
        if (ivRank < IV_RANK_MIN_ENTRY) {
            return signals;
        }

        // Gate 2: Classify market regime using the weighted multi-timeframe score.
        //
        // detectMarketRegime() now returns a RegimeScore that bundles:
        //   • regime          – primary enum label (unchanged semantics)
        //   • blendedScore    – 60 % daily + 40 % weekly normalised trend score
        //   • timeframesAgree – true when score > 0.7 (bullish) or < 0.3 (bearish)
        RegimeScore regimeScore = detectMarketRegime(data);
        MarketRegime regime     = regimeScore.regime;

        // Gate 3: Manage existing position before considering a new entry
        if (state.inTrade) {
            if (shouldRoll(data)) {
                generateRollSignals(data, regime, signals);
                return signals;
            }
            if (evaluateExitRules(data)) {
                generateExitSignals(signals);
                return signals;
            }
            return signals; // already in a trade and no action triggered
        }

        // Gate 4: No new entries when DTE is too close to expiration
        int dte = data.getDte();
        if (dte < DTE_NO_ENTRY) {
            return signals;
        }

        // Validate minimum credit
        double expectedCredit = estimateCredit(data, regime);
        if (expectedCredit < CREDIT_MIN) {
            return signals;
        }

        // Compute position size (DTE-scaled)
        double contracts = computePositionSize(data, dte);

        // Select strikes for both spread sides
        double[] callSpread = selectCallSpread(data, regime);
        double[] putSpread  = selectPutSpread(data, regime);
        if (callSpread == null || putSpread == null) {
            return signals;
        }

        double shortCallStrike = callSpread[0];
        double longCallStrike  = callSpread[1];
        double shortPutStrike  = putSpread[0];
        double longPutStrike   = putSpread[1];

        // ── Multi-timeframe agreement gate ────────────────────────────────────
        //
        // When the daily (60 %) and weekly (40 %) trend scores disagree — i.e.
        // the blended score falls in the uncertainty band [0.3, 0.7] — we do NOT
        // skip the trade entirely.  Instead, we apply two protective adjustments:
        //
        //   1. Widen strikes: push each short strike one step further OTM and
        //      each long strike one step further OTM beyond that, increasing the
        //      buffer between spot and the short legs.
        //   2. Reduce size: multiply contracts by MIXED_REGIME_SIZE_FACTOR (0.5)
        //      to cut the notional exposure in half.
        //
        // When both timeframes agree (score > 0.7 bullish or < 0.3 bearish) we
        // proceed with the normal, unadjusted strikes and full position size.
        if (!regimeScore.timeframesAgree) {
            double widenStep = STRIKE_ROUNDING * WING_WIDTH_WIDEN_STEPS;
            // Widen the call spread: both legs shift further OTM (higher)
            shortCallStrike = roundToStrike(shortCallStrike + widenStep);
            longCallStrike  = roundToStrike(longCallStrike  + widenStep);
            // Widen the put spread: both legs shift further OTM (lower)
            shortPutStrike  = roundToStrike(shortPutStrike  - widenStep);
            longPutStrike   = roundToStrike(longPutStrike   - widenStep);
            // Halve the position size (floor still enforced below)
            contracts = Math.max(MIN_CONTRACTS,
                                 Math.floor(contracts * MIXED_REGIME_SIZE_FACTOR));
        }

        // Geometry sanity check
        if (shortCallStrike >= longCallStrike || shortPutStrike <= longPutStrike) {
            return signals;
        }
        if (shortPutStrike >= shortCallStrike) {
            return signals; // spreads overlap — not a valid condor
        }

        // Build the four-legged iron condor
        for (int i = 0; i < (int) contracts; i++) {
            signals.add(new Signal(data.getSymbol(), SignalType.SELL_CALL, shortCallStrike));
            signals.add(new Signal(data.getSymbol(), SignalType.BUY_CALL,  longCallStrike));
            signals.add(new Signal(data.getSymbol(), SignalType.SELL_PUT,  shortPutStrike));
            signals.add(new Signal(data.getSymbol(), SignalType.BUY_PUT,   longPutStrike));
        }

        // Record entry state
        state.inTrade             = true;
        state.shortCallStrike     = shortCallStrike;
        state.longCallStrike      = longCallStrike;
        state.shortPutStrike      = shortPutStrike;
        state.longPutStrike       = longPutStrike;
        state.entryPrice          = price;
        state.entryIV             = iv;
        state.entryCredit         = expectedCredit;
        state.daysInTrade         = 0;
        state.rollCount           = 0;
        state.maxFavourableCredit = expectedCredit;

        return signals;
    }

    // ── Regime detection ──────────────────────────────────────────────────────

    /**
     * Bundled result of the multi-timeframe regime detection.
     *
     * <ul>
     *   <li>{@code regime}      – the primary regime label used for strike
     *       geometry and credit estimation (derived from the daily proxy).</li>
     *   <li>{@code blendedScore} – weighted combination of the daily (60 %) and
     *       weekly (40 %) trend scores, each normalised to [0, 1] where
     *       1.0 = strongly bullish/up-trending and 0.0 = strongly bearish/down-
     *       trending.  A score near 0.5 signals a neutral / mixed environment.</li>
     *   <li>{@code timeframesAgree} – true when the blended score is outside the
     *       uncertainty band (> REGIME_SCORE_BULL or < REGIME_SCORE_BEAR).</li>
     * </ul>
     */
    private static class RegimeScore {
        final MarketRegime regime;
        final double       blendedScore;
        final boolean      timeframesAgree;

        RegimeScore(MarketRegime regime, double blendedScore) {
            this.regime          = regime;
            this.blendedScore    = blendedScore;
            this.timeframesAgree = blendedScore > REGIME_SCORE_BULL
                                || blendedScore < REGIME_SCORE_BEAR;
        }
    }

    /**
     * Classify the current market into one of four regimes AND produce a
     * weighted multi-timeframe trend score.
     *
     * <h3>Two-timeframe approach</h3>
     * <ul>
     *   <li><b>Daily proxy</b> (60 % weight): uses IV and momentum values as-is
     *       from the MarketData snapshot — these reflect intra-day / daily
     *       dynamics.</li>
     *   <li><b>Weekly proxy</b> (40 % weight): approximates a slower, weekly
     *       moving average by smoothing the IV signal ({@code iv × 0.85}) and
     *       dampening the momentum signal ({@code momentumWindow × 5}), which
     *       emulates a 5-day lookback in the absence of a full OHLCV history.</li>
     * </ul>
     *
     * <h3>Individual trend scores</h3>
     * Each timeframe produces a scalar score in [0, 1]:
     * <pre>
     *   0.0  → strongly bearish / downward-trending
     *   0.5  → neutral / range-bound
     *   1.0  → strongly bullish / upward-trending
     * </pre>
     *
     * The formula is:
     * <pre>
     *   trendStrength = clamp(adxProxy / (ADX_THRESHOLD_TREND * 2), 0, 1)
     *   momentumSign  = momentumProxy > 0 ? +1 : -1
     *   score         = 0.5 + momentumSign × trendStrength × 0.5
     * </pre>
     * A range-bound market (adxProxy ≤ threshold) produces a score near 0.5.
     *
     * <h3>Primary regime label</h3>
     * The regime enum is derived solely from the daily proxy so that strike
     * geometry and credit estimation remain identical to the original logic.
     *
     * <h3>Classification rules (evaluated in order)</h3>
     * <ol>
     *   <li>IV > mean + 1.5σ → HIGH_VOL_CRUSH (prepare for mean reversion)</li>
     *   <li>ADX-proxy > ADX_THRESHOLD_TREND:
     *     <ul>
     *       <li>momentum proxy &gt; 0 → TRENDING_UP</li>
     *       <li>momentum proxy &lt; 0 → TRENDING_DOWN</li>
     *     </ul>
     *   </li>
     *   <li>Otherwise → RANGE_BOUND</li>
     * </ol>
     */
    private RegimeScore detectMarketRegime(MarketData data) {
        double iv    = data.getImpliedVolatility();
        double price = data.getPrice();

        // ── Primary regime label (daily proxy — unchanged from V0 baseline) ──

        // Mean-reversion regime: IV significantly depressed vs. historical mean
        if (iv < IV_HIST_MEAN - 0.75 * IV_HIST_STD) {
            // In a high-vol-crush environment both timeframes are effectively
            // in agreement that mean reversion is imminent; score set to 0.5
            // (neutral directional bias) with timeframesAgree = false so the
            // caller widens strikes for additional safety.
            return new RegimeScore(MarketRegime.HIGH_VOL_CRUSH, 0.50);
        }

        // ── Daily trend score (60 % weight) ──────────────────────────────────
        double dailyScore = computeTrendScore(iv, price, MOMENTUM_WINDOW);

        // ── Weekly trend score (40 % weight) ─────────────────────────────────
        // Simulate a weekly (5-day) smoothed view by:
        //   • dampening IV by 15 % (slower-reacting weekly IV surface)
        //   • expanding the momentum window ×5 (weekly ≈ 5× daily periods)
        double weeklyIV    = iv * 0.85;
        double weeklyScore = computeTrendScore(weeklyIV, price, MOMENTUM_WINDOW * 5.0);

        // ── Blended score ─────────────────────────────────────────────────────
        double blended = DAILY_TREND_WEIGHT * dailyScore
                       + (1.0 - DAILY_TREND_WEIGHT) * weeklyScore;

        // ── Primary regime label from daily proxy ─────────────────────────────
        MarketRegime regime = regimeFromScore(iv, price, dailyScore);

        return new RegimeScore(regime, blended);
    }

    /**
     * Compute a normalised trend score in [0, 1] for a given IV and momentum
     * window combination.
     *
     * <pre>
     *   adxProxy      = min(100, (iv / price) × 10 000)
     *   fairValue     = price / (1 + iv / momentumWindow)
     *   momentumProxy = price − fairValue
     *   trendStrength = clamp(adxProxy / (ADX_THRESHOLD_TREND × 2), 0, 1)
     *   score         = 0.5 + sign(momentumProxy) × trendStrength × 0.5
     * </pre>
     *
     * @param iv             implied volatility for this timeframe
     * @param price          current spot price
     * @param momentumWindow lookback window (vol-time units) for this timeframe
     * @return trend score in [0, 1]
     */
    private double computeTrendScore(double iv, double price, double momentumWindow) {
        double ivToPrice      = iv / Math.max(price, 1.0);
        double adxProxy       = Math.min(100.0, ivToPrice * 10_000.0);
        double fairValue      = price / (1.0 + iv / momentumWindow);
        double momentumProxy  = price - fairValue;
        double trendStrength  = clamp(adxProxy / (ADX_THRESHOLD_TREND * 2.0), 0.0, 1.0);
        double momentumSign   = momentumProxy >= 0.0 ? 1.0 : -1.0;
        return 0.5 + momentumSign * trendStrength * 0.5;
    }

    /**
     * Derive the primary {@link MarketRegime} enum value from the daily trend
     * score and raw IV.  This preserves the original classification rules so
     * that all downstream strike-selection and credit-estimation logic is
     * unaffected by the multi-timeframe enhancement.
     *
     * @param iv         daily implied volatility
     * @param price      current spot price
     * @param dailyScore normalised daily trend score in [0, 1]
     * @return the appropriate {@link MarketRegime}
     */
    private MarketRegime regimeFromScore(double iv, double price, double dailyScore) {
        // Reconstruct adxProxy to stay consistent with the original logic
        double ivToPrice = iv / Math.max(price, 1.0);
        double adxProxy  = Math.min(100.0, ivToPrice * 10_000.0);

        if (adxProxy > ADX_THRESHOLD_TREND) {
            return dailyScore > 0.5 ? MarketRegime.TRENDING_UP : MarketRegime.TRENDING_DOWN;
        }
        return MarketRegime.RANGE_BOUND;
    }

    // ── IV metrics ────────────────────────────────────────────────────────────

    /**
     * Compute IV rank as a percentile from 0 to 100.
     *
     * Formula: IVR = (currentIV - ivLow) / (ivHigh - ivLow) × 100
     *
     * An IVR of 0 means IV is at its historical low; 100 means its historical high.
     * Values above IV_RANK_MIN_ENTRY (40) justify selling premium.
     */
    private double computeIVRank(double currentIV, double ivLow, double ivHigh) {
        if (ivHigh <= ivLow) {
            return 50.0; // degenerate case: assume median
        }
        double rank = (currentIV - ivLow) / (ivHigh - ivLow) * 100.0;
        return Math.min(100.0, Math.max(0.0, rank));
    }

    /**
     * Compute IV percentile using a normal-distribution approximation.
     *
     * Assumes IV is approximately normally distributed with mean IV_HIST_MEAN
     * and standard deviation IV_HIST_STD over IV_PERCENTILE_LOOKBACK trading days.
     * Returns a value in [0, 1].
     */
    private double computeIVPercentile(double currentIV) {
        double z = (currentIV - IV_HIST_MEAN) / IV_HIST_STD;
        return Math.min(1.0, Math.max(0.0, 0.5 + 0.5 * tanhApprox(z)));
    }

    /**
     * Polynomial approximation of tanh(x) for the percentile calculation.
     * Valid range: x ∈ [-5, 5]. Returns ±1 outside that range.
     */
    private double tanhApprox(double x) {
        if (x >  5.0) return  1.0;
        if (x < -5.0) return -1.0;
        double x2 = x * x;
        return x * (27.0 + x2) / (27.0 + 9.0 * x2);
    }

    // ── Strike selection ──────────────────────────────────────────────────────

    /**
     * Select short and long strikes for the call spread.
     *
     * Regime adjustments applied to the short call OTM distance:
     *   TRENDING_UP    → +20% (push call side further OTM; trend is against call shorts)
     *   TRENDING_DOWN  → -15% (tighten; collect more premium on the non-tested side)
     *   HIGH_VOL_CRUSH → -30% + floor at WING_WIDTH_MIN (tighter = faster decay)
     *   RANGE_BOUND    → no adjustment (base delta-implied distance)
     *
     * @return double[2] { shortCallStrike, longCallStrike }, or null if geometry invalid
     */
    private double[] selectCallSpread(MarketData data, MarketRegime regime) {
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        double baseShortDist = estimateStrikeFromDelta(price, iv, DELTA_TARGET_SHORT_CALL, true);
        double baseLongDist  = estimateStrikeFromDelta(price, iv, DELTA_TARGET_LONG_CALL,  true);

        double shortDist;
        switch (regime) {
            case TRENDING_UP:
                shortDist = baseShortDist * 1.20;
                break;
            case TRENDING_DOWN:
                shortDist = baseShortDist * 0.85;
                break;
            case HIGH_VOL_CRUSH:
                shortDist = Math.max(WING_WIDTH_MIN, baseShortDist * 0.70);
                break;
            case RANGE_BOUND:
            default:
                shortDist = baseShortDist;
                break;
        }

        shortDist = clamp(shortDist, WING_WIDTH_MIN, WING_WIDTH_MAX);
        double longDist  = shortDist + Math.max(WING_WIDTH_MIN, baseLongDist);

        double shortCallStrike = roundToStrike(price + shortDist);
        double longCallStrike  = roundToStrike(price + longDist);

        if (longCallStrike <= shortCallStrike) {
            longCallStrike = shortCallStrike + WING_WIDTH_MIN;
        }

        return new double[]{ shortCallStrike, longCallStrike };
    }

    /**
     * Select short and long strikes for the put spread.
     *
     * Mirror logic to the call side, with an additional vol-skew adjustment:
     * put implied volatility is typically 5–15 % higher than call IV due to
     * downside demand. We reduce the OTM distance by SKEW_ADJUSTMENT_FACTOR
     * to bring the put premium in line with the call premium.
     *
     * @return double[2] { shortPutStrike, longPutStrike }, or null if geometry invalid
     */
    private double[] selectPutSpread(MarketData data, MarketRegime regime) {
        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        double baseShortDist = estimateStrikeFromDelta(price, iv, DELTA_TARGET_SHORT_PUT, false);
        double baseLongDist  = estimateStrikeFromDelta(price, iv, DELTA_TARGET_LONG_PUT,  false);

        double shortDist;
        switch (regime) {
            case TRENDING_DOWN:
                shortDist = baseShortDist * 1.20;
                break;
            case TRENDING_UP:
                shortDist = baseShortDist * 0.85;
                break;
            case HIGH_VOL_CRUSH:
                shortDist = Math.max(WING_WIDTH_MIN, baseShortDist * 0.70);
                break;
            case RANGE_BOUND:
            default:
                shortDist = baseShortDist;
                break;
        }

        // Apply skew adjustment: put side needs less OTM distance for same premium
        double skewPremiumFraction = iv * SKEW_ADJUSTMENT_FACTOR * 0.10;
        shortDist = shortDist * (1.0 - skewPremiumFraction);
        shortDist = clamp(shortDist, WING_WIDTH_MIN, WING_WIDTH_MAX);

        double longDist       = shortDist + Math.max(WING_WIDTH_MIN, baseLongDist);
        double shortPutStrike = roundToStrike(price - shortDist);
        double longPutStrike  = roundToStrike(price - longDist);

        if (longPutStrike >= shortPutStrike) {
            longPutStrike = shortPutStrike - WING_WIDTH_MIN;
        }

        return new double[]{ shortPutStrike, longPutStrike };
    }

    // ── Position sizing ───────────────────────────────────────────────────────

    /**
     * Compute the number of contracts to trade.
     *
     * Method: risk-budget approach.
     *   risk_budget = PORTFOLIO_NAV × PORTFOLIO_RISK_PCT
     *   max_risk_per_contract = WING_WIDTH_DEFAULT × 100   (100 shares per contract)
     *   raw_contracts = risk_budget / max_risk_per_contract
     *
     * The raw count is then scaled down proportionally when IV is elevated above
     * its historical mean, because elevated IV inflates gap-move risk and
     * bid-ask spreads, reducing the strategy's edge per contract.
     *
     * Additionally, the raw count is scaled by a DTE factor that reflects the
     * diminishing risk/reward profile as expiration approaches:
     *   dte ≥ 45 → 100 % of size  (full position — optimal theta/gamma profile)
     *   dte ≥ 30 →  75 % of size  (acceptable but slightly elevated gamma)
     *   dte ≥ 21 →  50 % of size  (meaningful gamma risk; take smaller position)
     *   dte < 14 →   0 % — no new entry (blocked upstream in generateSignals)
     *
     * The result is floored at MIN_CONTRACTS and capped at MAX_CONTRACTS.
     *
     * @param data  current market snapshot
     * @param dte   days to expiration read from MarketData
     */
    private double computePositionSize(MarketData data, int dte) {
        double iv                 = data.getImpliedVolatility();
        double riskBudget         = PORTFOLIO_NAV * PORTFOLIO_RISK_PCT;
        double maxRiskPerContract = WING_WIDTH_DEFAULT * 100.0;

        double rawContracts = riskBudget / maxRiskPerContract;

        // Scale down in elevated IV environments
        if (iv > IV_HIST_MEAN + IV_HIST_STD) {
            double ivScaleFactor = IV_HIST_MEAN / iv;
            rawContracts *= ivScaleFactor;
        }

        // Scale down as DTE decreases toward expiration
        rawContracts *= dteSizingScale(dte);

        return clamp(Math.floor(rawContracts), MIN_CONTRACTS, MAX_CONTRACTS);
    }

    /**
     * Return the DTE-based position-size scaling factor.
     *
     * <pre>
     *  DTE ≥ 45  →  1.00  (full size)
     *  DTE ≥ 30  →  0.75  (three-quarter size)
     *  DTE ≥ 21  →  0.50  (half size)
     *  DTE ≥ 14  →  0.50  (half size — below 14 is blocked at entry gate)
     *  DTE < 14  →  0.00  (no new entries; handled upstream)
     * </pre>
     *
     * @param dte days to expiration (must be ≥ DTE_NO_ENTRY to reach this method)
     * @return scaling multiplier in (0, 1]
     */
    private double dteSizingScale(int dte) {
        if (dte >= 45) return 1.00;
        if (dte >= 30) return 0.75;
        if (dte >= 21) return 0.50;
        // dte is in [14, 21) — still allowed but at half size
        return 0.50;
    }

    // ── Exit rules ────────────────────────────────────────────────────────────

    /**
     * Evaluate the full suite of exit conditions for an open position.
     *
     * Conditions checked (any one triggers an exit):
     *   1. Profit target: P&L ≥ PROFIT_TAKE_PCT of initial credit
     *   2. Stop-loss: unrealised loss ≥ STOP_LOSS_MULTIPLIER × initial credit
     *   3. Time-based: estimated DTE ≤ DAYS_TO_EXPIRY_MIN (gamma risk spikes near expiry)
     *   4. Gamma risk: net portfolio gamma exceeds GAMMA_RISK_THRESHOLD
     *   5. Vega exposure: absolute vega-$ exposure exceeds VEGA_EXPOSURE_MAX
     *
     * @return true if the position should be closed immediately
     */
    private boolean evaluateExitRules(MarketData data) {
        if (!state.inTrade) return false;
        state.daysInTrade++;

        double currentCredit = estimateCurrentCredit(data);

        // Update trailing high-water mark for credit received (profit tracking)
        if (currentCredit < state.maxFavourableCredit) {
            state.maxFavourableCredit = currentCredit;
        }

        // Rule 1: Profit target — take profit when position has decayed sufficiently
        double creditDecayed = state.entryCredit - currentCredit;
        if (creditDecayed >= state.entryCredit * PROFIT_TAKE_PCT) {
            return true;
        }

        // Rule 2: Stop-loss — current mark-to-market cost exceeds loss limit
        if (currentCredit > state.entryCredit * (1.0 + STOP_LOSS_MULTIPLIER)) {
            return true;
        }

        // Rule 3: Time-based exit (DTE estimated from days in trade counter)
        int estimatedDTE = DAYS_TO_EXPIRY_TARGET - state.daysInTrade;
        if (estimatedDTE <= DAYS_TO_EXPIRY_MIN) {
            return true;
        }

        // Rule 4: Gamma risk spike — short gamma positions are dangerous near expiry
        double gamma = computeGammaExposure(data);
        if (Math.abs(gamma) > GAMMA_RISK_THRESHOLD) {
            return true;
        }

        // Rule 5: Vega exposure limit — prevents runaway losses in volatility spikes
        double vega = estimateVegaExposure(data);
        if (Math.abs(vega) > VEGA_EXPOSURE_MAX) {
            return true;
        }

        return false;
    }

    /**
     * Generate close signals for the open iron condor (buy back the shorts, sell the longs).
     * Clears the position state after emitting signals.
     */
    private void generateExitSignals(List<Signal> signals) {
        signals.add(new Signal("CLOSE", SignalType.BUY_CALL,  state.shortCallStrike));
        signals.add(new Signal("CLOSE", SignalType.SELL_CALL, state.longCallStrike));
        signals.add(new Signal("CLOSE", SignalType.BUY_PUT,   state.shortPutStrike));
        signals.add(new Signal("CLOSE", SignalType.SELL_PUT,  state.longPutStrike));

        state.inTrade   = false;
        state.rollCount = 0;
    }

    // ── Rolling logic ─────────────────────────────────────────────────────────

    /**
     * Determine whether the position should be rolled rather than closed or held.
     *
     * Rolling is triggered when:
     *   (a) A short leg's breach fraction exceeds ROLL_TRIGGER_DELTA — meaning price
     *       has moved more than ROLL_TRIGGER_DELTA × 100 % of the way from the entry
     *       price to the short strike (a proxy for the delta of the short leg expanding).
     *   (b) The roll can still be executed for a net credit ≥ ROLL_CREDIT_MIN.
     *   (c) The maximum roll count has not been reached.
     *
     * We do NOT roll if the position is already beyond the short strike (i.e., the spread
     * is deep in the money); in that case the exit rules will handle it.
     */
    private boolean shouldRoll(MarketData data) {
        if (!state.inTrade || state.rollCount >= MAX_ROLL_COUNT) {
            return false;
        }

        double price = data.getPrice();

        // Compute how far each short strike has been breached as a fraction of its range
        double callRange   = Math.max(0.01, state.shortCallStrike - state.entryPrice);
        double putRange    = Math.max(0.01, state.entryPrice - state.shortPutStrike);

        double callBreach  = (price - state.entryPrice) / callRange;
        double putBreach   = (state.entryPrice - price) / putRange;

        boolean callTested = callBreach >= ROLL_TRIGGER_DELTA;
        boolean putTested  = putBreach  >= ROLL_TRIGGER_DELTA;

        if (!callTested && !putTested) {
            return false;
        }

        // Only roll if we can collect a meaningful credit on the new leg
        double rollCredit = estimateRollCredit(data);
        return rollCredit >= ROLL_CREDIT_MIN;
    }

    /**
     * Generate roll signals for the tested leg.
     *
     * Close the existing tested spread and re-open a new spread further OTM at the
     * current regime-adjusted strike. Increment the roll counter after each roll.
     */
    private void generateRollSignals(MarketData data, MarketRegime regime, List<Signal> signals) {
        double price     = data.getPrice();
        double callRange = Math.max(0.01, state.shortCallStrike - state.entryPrice);
        double putRange  = Math.max(0.01, state.entryPrice - state.shortPutStrike);
        double callBreach = (price - state.entryPrice) / callRange;
        double putBreach  = (state.entryPrice - price) / putRange;

        if (callBreach >= putBreach) {
            // Roll the call spread up
            double[] newCall = selectCallSpread(data, regime);
            if (newCall != null && newCall[0] > state.shortCallStrike) {
                signals.add(new Signal(data.getSymbol(), SignalType.BUY_CALL,  state.shortCallStrike));
                signals.add(new Signal(data.getSymbol(), SignalType.SELL_CALL, state.longCallStrike));
                signals.add(new Signal(data.getSymbol(), SignalType.SELL_CALL, newCall[0]));
                signals.add(new Signal(data.getSymbol(), SignalType.BUY_CALL,  newCall[1]));
                state.shortCallStrike = newCall[0];
                state.longCallStrike  = newCall[1];
                state.rollCount++;
            }
        } else {
            // Roll the put spread down
            double[] newPut = selectPutSpread(data, regime);
            if (newPut != null && newPut[0] < state.shortPutStrike) {
                signals.add(new Signal(data.getSymbol(), SignalType.BUY_PUT,   state.shortPutStrike));
                signals.add(new Signal(data.getSymbol(), SignalType.SELL_PUT,  state.longPutStrike));
                signals.add(new Signal(data.getSymbol(), SignalType.SELL_PUT,  newPut[0]));
                signals.add(new Signal(data.getSymbol(), SignalType.BUY_PUT,   newPut[1]));
                state.shortPutStrike = newPut[0];
                state.longPutStrike  = newPut[1];
                state.rollCount++;
            }
        }
    }

    // ── Greeks calculations ───────────────────────────────────────────────────

    /**
     * Estimate the net portfolio gamma exposure at the current price.
     *
     * The iron condor is net short gamma: the sold options contribute negative gamma
     * and the bought wings contribute positive gamma (but smaller magnitude since they
     * are further OTM and have lower absolute delta).
     *
     * We model gamma using a Gaussian approximation centred on each short strike.
     * Returns a negative value (net short gamma) in normal operation.
     */
    private double computeGammaExposure(MarketData data) {
        if (!state.inTrade) return 0.0;

        double price = data.getPrice();
        double iv    = data.getImpliedVolatility();

        // Net gamma from the two short legs (negative — we sold these)
        double shortCallGamma = -bsGammaProxy(price, state.shortCallStrike, iv);
        double shortPutGamma  = -bsGammaProxy(price, state.shortPutStrike,  iv);

        // Net gamma from the two long wings (positive — we bought these)
        double longCallGamma  =  bsGammaProxy(price, state.longCallStrike,  iv) * 0.40;
        double longPutGamma   =  bsGammaProxy(price, state.longPutStrike,   iv) * 0.40;

        return shortCallGamma + shortPutGamma + longCallGamma + longPutGamma;
    }

    /**
     * Black-Scholes gamma proxy: Gaussian kernel centred on the strike.
     *
     * Real gamma: Γ = N'(d1) / (S × σ × √T) where N' is the standard normal PDF.
     * Approximated here by: Γ ≈ exp(-0.5 × ((S-K)/(σ×S))²) / (σ × S × √(2π))
     *
     * DEMO ONLY — not a production-grade options model.
     */
    private double bsGammaProxy(double spot, double strike, double iv) {
        double sigma = iv * spot; // approximate $ standard deviation
        if (sigma <= 0.0) return 0.0;
        double z = (spot - strike) / sigma;
        return Math.exp(-0.5 * z * z) / (sigma * Math.sqrt(2.0 * Math.PI));
    }

    /**
     * Estimate the net vega (dollar) exposure of the open condor position.
     *
     * Iron condor is net short vega: an increase in IV hurts the position.
     * Vega per contract ≈ S × √(T/252) × σ × (-0.40) for a typical condor.
     *
     * The returned value is the approximate vega-dollar exposure per unit of
     * contract. Multiply by contract count for full exposure (simplified here).
     */
    private double estimateVegaExposure(MarketData data) {
        if (!state.inTrade) return 0.0;
        double iv         = data.getImpliedVolatility();
        double price      = data.getPrice();
        double dteRemain  = Math.max(1.0, DAYS_TO_EXPIRY_TARGET - (double) state.daysInTrade);
        double timeFactor = Math.sqrt(dteRemain / 252.0);
        // Net short vega: condor collects premium but is exposed to vega expansion
        return -price * timeFactor * iv * 0.40 * 100.0;
    }

    // ── Credit estimation helpers ─────────────────────────────────────────────

    /**
     * Estimate the net credit receivable for a new iron condor.
     *
     * Real credit = sum of mid-prices of all four legs. Here we approximate
     * using a simplified rule: credit ≈ IV × √(T/252) × price × 0.08,
     * which reflects roughly 8 % of the one-standard-deviation move per year
     * being collectible as premium at a 45-DTE entry.
     *
     * Regime adjustments reflect typical supply/demand dynamics:
     *   HIGH_VOL_CRUSH: +30 % (elevated premiums available)
     *   RANGE_BOUND:    +10 % (stable environment = slightly richer premiums)
     *   TRENDING:       -10 % (directional markets reduce effective edge)
     */
    private double estimateCredit(MarketData data, MarketRegime regime) {
        double iv    = data.getImpliedVolatility();
        double price = data.getPrice();
        double T     = DAYS_TO_EXPIRY_TARGET / 252.0;
        double base  = iv * Math.sqrt(T) * price * 0.08;

        switch (regime) {
            case HIGH_VOL_CRUSH: return base * 1.30;
            case RANGE_BOUND:    return base * 1.10;
            default:             return base * 0.90;
        }
    }

    /**
     * Estimate the current mark-to-market credit value of the open condor.
     *
     * The credit remaining decreases as:
     *   (a) Time passes (theta decay — captured via √(T_remain / T_entry))
     *   (b) Price approaches a short strike (delta / gamma effect — modelled
     *       via a proximity penalty that inflates the current credit)
     *
     * When current credit > entry credit, the position is running at a loss.
     */
    private double estimateCurrentCredit(MarketData data) {
        double price       = data.getPrice();
        double dteRemain   = Math.max(1.0, DAYS_TO_EXPIRY_TARGET - (double) state.daysInTrade);
        double timeDecay   = Math.sqrt(dteRemain / (double) DAYS_TO_EXPIRY_TARGET);

        double callRange   = Math.max(0.01, state.shortCallStrike - state.entryPrice);
        double putRange    = Math.max(0.01, state.entryPrice - state.shortPutStrike);

        double callProx = clamp(1.0 - (state.shortCallStrike - price) / callRange, 0.0, 1.0);
        double putProx  = clamp(1.0 - (price - state.shortPutStrike) / putRange,   0.0, 1.0);

        double proximityPenalty = 1.0 + 0.8 * Math.max(callProx, putProx);
        return state.entryCredit * timeDecay * proximityPenalty;
    }

    /**
     * Estimate the net credit that would be obtained by rolling the tested leg.
     *
     * Rolling is only worthwhile if the credit received for opening the new
     * (further OTM) spread exceeds the cost to buy back the tested spread.
     */
    private double estimateRollCredit(MarketData data) {
        if (state.daysInTrade >= DAYS_TO_EXPIRY_TARGET) return 0.0;
        double iv         = data.getImpliedVolatility();
        double price      = data.getPrice();
        double dteRemain  = Math.max(1.0, DAYS_TO_EXPIRY_TARGET - (double) state.daysInTrade);
        double T          = dteRemain / 252.0;

        double closeCost  = estimateCurrentCredit(data) * 1.50; // tested leg costs ~50 % more
        double newLegCred = iv * Math.sqrt(T) * price * 0.06;   // new OTM spread premium

        return newLegCred - closeCost;
    }

    // ── Strike utilities ──────────────────────────────────────────────────────

    /**
     * Estimate the OTM distance (in dollars) for a given delta target.
     *
     * Derived by inverting a simplified Black-Scholes call delta formula:
     *   delta ≈ N(d1) where d1 = ln(S/K)/(σ√T) + 0.5σ√T
     *
     * For OTM options (delta < 0.5) we approximate the inverse normal as:
     *   N⁻¹(delta) ≈ -ln(delta) × 0.8    (valid for delta ∈ [0.03, 0.25])
     *
     * The resulting distance is clamped to [WING_WIDTH_MIN, WING_WIDTH_MAX].
     *
     * @param price    current spot price
     * @param iv       annualised implied volatility
     * @param delta    target delta (0 < delta < 0.5)
     * @param isCall   true for call (OTM above spot), false for put (OTM below spot)
     * @return approximate OTM dollar distance (always positive)
     */
    private double estimateStrikeFromDelta(double price, double iv, double delta, boolean isCall) {
        if (delta <= 0.0 || delta >= 0.5 || iv <= 0.0) {
            return WING_WIDTH_DEFAULT;
        }
        double T       = DAYS_TO_EXPIRY_TARGET / 252.0;
        double sigmaT  = iv * Math.sqrt(T);
        double normInv = -Math.log(delta) * 0.80; // approximate inverse normal for OTM
        double dist    = price * sigmaT * normInv;
        return clamp(dist, WING_WIDTH_MIN, WING_WIDTH_MAX);
    }

    /**
     * Round a raw strike price to the nearest STRIKE_ROUNDING increment.
     * In production, strikes are rounded to the listed increment (e.g. $0.50 or $1.00).
     */
    private double roundToStrike(double rawStrike) {
        return Math.round(rawStrike / STRIKE_ROUNDING) * STRIKE_ROUNDING;
    }

    /**
     * Clamp a value to the closed interval [lo, hi].
     */
    private double clamp(double value, double lo, double hi) {
        return Math.min(hi, Math.max(lo, value));
    }
}

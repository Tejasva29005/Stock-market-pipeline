// DEMO INTERFACE — in production this lives in a shared library with live
// options-chain types, real feed adapters, and proper Signal definitions.
import java.util.List;

public interface Strategy {
    List<Signal> generateSignals(MarketData data);
}

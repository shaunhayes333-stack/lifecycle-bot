#!/usr/bin/env python3
"""Source-contract tripwires for the 6735 regressions; no source mutation."""
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/com/lifecyclebot'
def source(path):
    return (ROOT / path).read_text()
checks = {
    'learned risk is not promoted by venue floor': 'OK_MIN_PROMOTED_6600' not in source('engine/truth/OrderSizeResolver6441.kt'),
    'exact learning cohort cache': 'CleanInput6737(inputRows6737, limit' in source('engine/StrategyTruthLedger.kt') and 'rawRows.size / 10' not in source('engine/StrategyTruthLedger.kt'),
    'no token-count dust threshold': 'if (qtyToken <= 1.0)' not in source('data/Models.kt'),
    'partial missing inventory retains paid basis': 'val openMv = markedValue + missingProjectedBasis' in source('engine/truth/CanonicalCapitalAuthority6450.kt'),
    'close convergence includes funded inventory': 'fundedPositions6737("paper")' in source('engine/PaperTerminalProjectionConvergence6509.kt'),
    'coordinator cancellation propagates': 'generation6737 == exitCoordinatorGeneration6737.get()' in source('engine/BotService.kt'),
}
executor = source('engine/Executor.kt')
terminal = re.split(r'\n    (?:(?:private|internal|public|suspend|override)\s+)*fun\s', executor.split('fun paperSell(ts: TokenState', 1)[1], maxsplit=1)[0]
checks.update({
    'terminal fill uses canonical units': 'PaperFillMath6737.grossProceeds(' in terminal,
    'terminal fill validates quote freshness': 'validEconomicExit6737(exitWitness6737, ts.mint)' in terminal,
    'no price-return manufacture of gross proceeds': 'terminalRemainingCost6492 * (1.0 + priceDerivedPnlPct' not in terminal,
    'no precommit treasury mutation': 'TreasuryManager.contribute' not in terminal,
})
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise SystemExit('6737 source integrity FAILED: ' + '; '.join(failed))
print(f'6737 source integrity passed ({len(checks)} contracts)')

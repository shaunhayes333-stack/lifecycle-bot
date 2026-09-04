#!/usr/bin/env python3
"""Fail CI when an entry lane changes identity between authority layers.

V5.0.6664: this is deliberately a source-contract scan, not a log heuristic.
The Express 1570 repair sealed SHITCOIN at FDG while authorizing EXPRESS, and
the shared transport later stamped successful specialist positions SHITCOIN.
Both compiled cleanly.  Keep the complete creation -> FDG -> authorizer ->
executor -> position/journal chain aligned, including failure-release paths.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
BOT = (ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/BotService.kt").read_text()
EXECUTOR = (ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/Executor.kt").read_text()


def section(text: str, start: str, end: str) -> str:
    if start not in text or end not in text.split(start, 1)[1]:
        raise ValueError(f"missing audit boundary: {start!r} .. {end!r}")
    return text.split(start, 1)[1].split(end, 1)[0]


def require(errors: list[str], body: str, needle: str, contract: str) -> None:
    if needle not in body:
        errors.append(f"{contract}: missing {needle!r}")


def forbid(errors: list[str], body: str, needle: str, contract: str) -> None:
    if needle in body:
        errors.append(f"{contract}: contradictory {needle!r}")


def main() -> int:
    errors: list[str] = []

    try:
        express = section(BOT, "V5.9.1570 — Express FDG verdict", "END ShitCoin Express evaluation")
        for needle in (
            'lane = "EXPRESS"',
            "requestedBook = TradeAuthorizer.ExecutionBook.EXPRESS",
            'executionLane = "EXPRESS"',
            "TradeAuthorizer.ExecutionBook.EXPRESS)",
        ):
            require(errors, express, needle, "EXPRESS")
        forbid(errors, express, 'lane = "SHITCOIN"', "EXPRESS")
        forbid(errors, express, "TradeAuthorizer.ExecutionBook.SHITCOIN)", "EXPRESS")

        manip = section(BOT, "THE MANIPULATED - Ride manipulation pumps", "END ManipulatedTraderAI evaluation")
        for needle in (
            'recordFdg(ts.mint, ts.symbol, "MANIPULATED"',
            "requestedBook = TradeAuthorizer.ExecutionBook.MANIPULATED",
            'executionLane = "MANIPULATED"',
            "TradeAuthorizer.ExecutionBook.MANIPULATED)",
        ):
            require(errors, manip, needle, "MANIPULATED")

        sniper = section(BOT, "PROJECT SNIPER - Snipe fresh launches", "END Project Sniper evaluation")
        for needle in (
            "requestedBook = TradeAuthorizer.ExecutionBook.PROJECT_SNIPER",
            'executionLane = "PROJECT_SNIPER"',
            "TradeAuthorizer.ExecutionBook.PROJECT_SNIPER)",
        ):
            require(errors, sniper, needle, "PROJECT_SNIPER")
    except ValueError as exc:
        errors.append(str(exc))

    transport = section(EXECUTOR, "fun shitCoinBuy(", "fun moonshotBuy(")
    for needle in (
        'executionLane: String = "SHITCOIN"',
        "preflightExecutableOpen(ts, isPaper, executionLane",
        "quality = executionLane",
        "layerTag = executionLane",
        "ts.position.tradingMode = executionLane",
        'ts.position.isShitCoinPosition = executionLane == "SHITCOIN"',
        "mode = executionLane",
    ):
        require(errors, transport, needle, "SHARED_MEME_TRANSPORT")
    forbid(errors, transport, 'ts.position.tradingMode = "SHITCOIN"', "SHARED_MEME_TRANSPORT")
    forbid(errors, transport, 'layerTag = "SHITCOIN"', "SHARED_MEME_TRANSPORT")

    paper_terminal = section(EXECUTOR, "V5.9.1133 — close paper position state", "fun liveSell(")
    for needle in (
        "val entryTimeSafeEdu = if (pos.entryTime",
        "tradingMode = pos.tradingMode",
        'pos.tradingMode == "EXPRESS"',
        'pos.tradingMode == "PROJECT_SNIPER"',
        "entryCostSol = pos.costSol",
        "entryScore = pos.entryScore",
    ):
        require(errors, paper_terminal, needle, "PAPER_TERMINAL_LEARNING")
    forbid(errors, paper_terminal, "book = TradeAuthorizer.ExecutionBook.CORE", "PAPER_TERMINAL_RELEASE")

    # No full sell path may release only CORE. Full terminal inventory is mint
    # canonical and can have been opened by any specialist book.
    forbid(
        errors,
        EXECUTOR,
        'reason = "SELL_$reason",\n                book = TradeAuthorizer.ExecutionBook.CORE',
        "TERMINAL_RELEASE",
    )

    call_count = BOT.count("executor.shitCoinBuy(")
    if call_count != 4:
        errors.append(f"SHARED_MEME_TRANSPORT: expected 4 callers, found {call_count}; audit new caller")
    for lane in ("MANIPULATED", "EXPRESS", "PROJECT_SNIPER"):
        if BOT.count(f'executionLane = "{lane}"') != 1:
            errors.append(f"{lane}: shared transport identity must be explicit exactly once")

    # Known cross-lane failure releases are especially damaging: the failed
    # lane remains locked while an unrelated book is released.
    forbid(
        errors,
        BOT,
        'lane=EXPRESS symbol=${ts.symbol} mint=${ts.mint.take(10)}") } catch (_: Throwable) {}\n'
        '                                    try { LaneExecutionCoordinator.releaseIfPrimary(ts.mint, "EXPRESS", "BUY_NOT_OPENED") } catch (_: Throwable) {}\n'
        '                                    try { FinalExecutionPermit.releaseExecution(ts.mint) } catch (_: Throwable) {}\n'
        '                                    try { TradeAuthorizer.releasePosition(ts.mint, "BUY_NOT_OPENED", TradeAuthorizer.ExecutionBook.SHITCOIN)',
        "EXPRESS_RELEASE",
    )
    forbid(
        errors,
        BOT,
        'TradeAuthorizer.releasePosition(ts.mint, "BUY_NOT_OPENED", TradeAuthorizer.ExecutionBook.SHITCOIN) } catch (_: Throwable) {}\n'
        '                                    return\n'
        '                                }\n\n'
        '                                com.lifecyclebot.v3.scoring.ProjectSniperAI.engageMission',
        "PROJECT_SNIPER_RELEASE",
    )

    if errors:
        print("Authority contradiction scan FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("Authority contradiction scan passed (creation/FDG/book/transport/release/position)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

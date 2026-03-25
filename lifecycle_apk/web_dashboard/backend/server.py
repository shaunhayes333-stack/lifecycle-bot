"""
Lifecycle Bot Web Dashboard - FastAPI Backend
Real-time trading bot monitoring and control interface
"""
import os
from datetime import datetime, timezone
from typing import List, Optional
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from pymongo import MongoClient
from dotenv import load_dotenv
import random

load_dotenv()

app = FastAPI(
    title="Lifecycle Bot Dashboard API",
    description="Real-time monitoring for Solana meme coin trading bot",
    version="1.0.0"
)

# CORS
origins = os.getenv("CORS_ORIGINS", "*").split(",")
app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# MongoDB
MONGO_URL = os.getenv("MONGO_URL", "mongodb://localhost:27017")
DB_NAME = os.getenv("DB_NAME", "lifecycle_bot")
client = MongoClient(MONGO_URL)
db = client[DB_NAME]

# ══════════════════════════════════════════════════════════════════════════════
# MODELS
# ══════════════════════════════════════════════════════════════════════════════

class Position(BaseModel):
    token_mint: str
    token_symbol: str
    entry_price: float
    current_price: float
    quantity: float
    entry_time: datetime
    pnl_pct: float
    pnl_usd: float
    status: str = "OPEN"

class Trade(BaseModel):
    id: str
    token_mint: str
    token_symbol: str
    side: str  # BUY or SELL
    price: float
    quantity: float
    value_sol: float
    value_usd: float
    timestamp: datetime
    pnl_pct: Optional[float] = None
    signal_score: Optional[float] = None

class BotStats(BaseModel):
    treasury_sol: float
    treasury_usd: float
    locked_profit_sol: float
    locked_profit_usd: float
    total_trades: int
    win_rate: float
    sharpe_ratio: float
    sortino_ratio: float
    max_drawdown: float
    best_trade_pct: float
    worst_trade_pct: float
    avg_hold_time_mins: float
    current_tier: str
    bot_running: bool
    paper_mode: bool
    auto_trade: bool
    last_updated: datetime

class DecisionLog(BaseModel):
    timestamp: datetime
    token_symbol: str
    action: str
    entry_score: float
    exit_score: float
    reason: str

class TokenWatchlist(BaseModel):
    mint: str
    symbol: str
    price: float
    mcap: float
    volume_24h: float
    change_24h: float
    entry_score: float
    exit_score: float
    whale_pct: float
    safety_score: float
    added_at: datetime

class ConfigUpdate(BaseModel):
    paper_mode: Optional[bool] = None
    auto_trade: Optional[bool] = None
    stop_loss_pct: Optional[float] = None
    exit_score_threshold: Optional[float] = None
    small_buy_sol: Optional[float] = None
    large_buy_sol: Optional[float] = None
    slippage_bps: Optional[int] = None
    notifications_enabled: Optional[bool] = None
    sound_enabled: Optional[bool] = None

# ══════════════════════════════════════════════════════════════════════════════
# SIMULATED DATA (Will be replaced with real Android app sync)
# ══════════════════════════════════════════════════════════════════════════════

def get_simulated_stats() -> dict:
    """Generate realistic bot stats for dashboard demo"""
    return {
        "treasury_sol": round(random.uniform(2.5, 8.0), 4),
        "treasury_usd": round(random.uniform(400, 1500), 2),
        "locked_profit_sol": round(random.uniform(0.5, 3.0), 4),
        "locked_profit_usd": round(random.uniform(80, 500), 2),
        "total_trades": random.randint(45, 120),
        "win_rate": round(random.uniform(58, 72), 1),
        "sharpe_ratio": round(random.uniform(1.2, 2.8), 2),
        "sortino_ratio": round(random.uniform(1.5, 3.5), 2),
        "max_drawdown": round(random.uniform(8, 18), 1),
        "best_trade_pct": round(random.uniform(150, 800), 1),
        "worst_trade_pct": round(random.uniform(-25, -8), 1),
        "avg_hold_time_mins": round(random.uniform(12, 45), 1),
        "current_tier": random.choice(["Micro", "Small", "Growth", "Established"]),
        "bot_running": True,
        "paper_mode": False,
        "auto_trade": True,
        "last_updated": datetime.now(timezone.utc).isoformat()
    }

def get_simulated_positions() -> list:
    """Generate sample open positions"""
    tokens = [
        ("BONK", "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"),
        ("WIF", "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm"),
        ("POPCAT", "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"),
        ("MYRO", "HhJpBhRRn4g56VsyLuT8DL5Bv31HkXqsrahTTUCZeZg4"),
    ]
    positions = []
    for symbol, mint in random.sample(tokens, random.randint(1, 3)):
        entry = random.uniform(0.00001, 0.001)
        current = entry * random.uniform(0.8, 2.5)
        qty = random.uniform(100000, 5000000)
        pnl_pct = ((current - entry) / entry) * 100
        positions.append({
            "token_mint": mint,
            "token_symbol": symbol,
            "entry_price": entry,
            "current_price": current,
            "quantity": qty,
            "entry_time": datetime.now(timezone.utc).isoformat(),
            "pnl_pct": round(pnl_pct, 2),
            "pnl_usd": round(pnl_pct * random.uniform(0.5, 2), 2),
            "status": "OPEN"
        })
    return positions

def get_simulated_trades() -> list:
    """Generate sample recent trades"""
    tokens = ["BONK", "WIF", "POPCAT", "MYRO", "SLERF", "BOME", "MEW", "TREMP"]
    trades = []
    for i in range(15):
        side = random.choice(["BUY", "SELL"])
        symbol = random.choice(tokens)
        price = random.uniform(0.00001, 0.01)
        qty = random.uniform(50000, 2000000)
        value_sol = random.uniform(0.02, 0.15)
        trades.append({
            "id": f"tx_{i}_{random.randint(1000,9999)}",
            "token_mint": f"mint_{symbol.lower()}",
            "token_symbol": symbol,
            "side": side,
            "price": price,
            "quantity": qty,
            "value_sol": round(value_sol, 4),
            "value_usd": round(value_sol * 180, 2),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "pnl_pct": round(random.uniform(-15, 150), 1) if side == "SELL" else None,
            "signal_score": round(random.uniform(45, 85), 1)
        })
    return trades

def get_simulated_watchlist() -> list:
    """Generate sample watchlist tokens"""
    tokens = [
        ("BONK", "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"),
        ("WIF", "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm"),
        ("POPCAT", "7GCihgDB8fe6KNjn2MYtkzZcRjQy3t9GHdC8uHYmW2hr"),
        ("MYRO", "HhJpBhRRn4g56VsyLuT8DL5Bv31HkXqsrahTTUCZeZg4"),
        ("SLERF", "7BgBvyjrZX1YKz4oh9mjb8ZScatkkwb8DzFx7LoiVkM3"),
        ("BOME", "ukHH6c7mMyiWCf1b9pnWe25TSpkDDt3H5pQZgZ74J82"),
    ]
    watchlist = []
    for symbol, mint in tokens:
        watchlist.append({
            "mint": mint,
            "symbol": symbol,
            "price": random.uniform(0.00001, 0.01),
            "mcap": random.uniform(5000000, 500000000),
            "volume_24h": random.uniform(100000, 10000000),
            "change_24h": random.uniform(-25, 80),
            "entry_score": round(random.uniform(30, 75), 1),
            "exit_score": round(random.uniform(20, 60), 1),
            "whale_pct": round(random.uniform(5, 35), 1),
            "safety_score": round(random.uniform(60, 95), 1),
            "added_at": datetime.now(timezone.utc).isoformat()
        })
    return watchlist

def get_simulated_decision_log() -> list:
    """Generate sample decision log entries"""
    actions = ["HOLD", "BUY_SIGNAL", "SELL_SIGNAL", "SKIP", "WATCHING"]
    reasons = [
        "Entry score 72 > threshold 52",
        "Exit score 65 > threshold 58, profit lock triggered",
        "Whale accumulation detected, holding",
        "Volume divergence, skipping entry",
        "EMA fan bullish, momentum strong",
        "Bonding curve 85%, approaching graduation",
        "Stop loss triggered at -12%",
        "Partial sell at 200% gain",
    ]
    tokens = ["BONK", "WIF", "POPCAT", "MYRO", "SLERF"]
    logs = []
    for i in range(20):
        logs.append({
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "token_symbol": random.choice(tokens),
            "action": random.choice(actions),
            "entry_score": round(random.uniform(30, 80), 1),
            "exit_score": round(random.uniform(20, 70), 1),
            "reason": random.choice(reasons)
        })
    return logs

# ══════════════════════════════════════════════════════════════════════════════
# API ENDPOINTS
# ══════════════════════════════════════════════════════════════════════════════

@app.get("/api/health")
async def health_check():
    return {"status": "healthy", "timestamp": datetime.now(timezone.utc).isoformat()}

@app.get("/api/stats")
async def get_bot_stats():
    """Get current bot statistics and treasury info"""
    # Try to get from DB first, fall back to simulated
    stats = db.stats.find_one({"_id": "current"}, {"_id": 0})
    if not stats:
        stats = get_simulated_stats()
    return stats

@app.get("/api/positions")
async def get_positions():
    """Get all open positions"""
    positions = list(db.positions.find({"status": "OPEN"}, {"_id": 0}))
    if not positions:
        positions = get_simulated_positions()
    return {"positions": positions, "count": len(positions)}

@app.get("/api/trades")
async def get_trades(limit: int = 50):
    """Get recent trade history"""
    trades = list(db.trades.find({}, {"_id": 0}).sort("timestamp", -1).limit(limit))
    if not trades:
        trades = get_simulated_trades()
    return {"trades": trades, "count": len(trades)}

@app.get("/api/watchlist")
async def get_watchlist():
    """Get current token watchlist with scores"""
    watchlist = list(db.watchlist.find({}, {"_id": 0}))
    if not watchlist:
        watchlist = get_simulated_watchlist()
    return {"tokens": watchlist, "count": len(watchlist)}

@app.get("/api/decisions")
async def get_decision_log(limit: int = 50):
    """Get recent decision log entries"""
    logs = list(db.decisions.find({}, {"_id": 0}).sort("timestamp", -1).limit(limit))
    if not logs:
        logs = get_simulated_decision_log()
    return {"logs": logs, "count": len(logs)}

@app.get("/api/config")
async def get_config():
    """Get current bot configuration"""
    config = db.config.find_one({"_id": "current"}, {"_id": 0})
    if not config:
        config = {
            "paper_mode": False,
            "auto_trade": True,
            "stop_loss_pct": 10.0,
            "exit_score_threshold": 58.0,
            "small_buy_sol": 0.05,
            "large_buy_sol": 0.10,
            "slippage_bps": 200,
            "notifications_enabled": True,
            "sound_enabled": True
        }
    return config

@app.patch("/api/config")
async def update_config(updates: ConfigUpdate):
    """Update bot configuration"""
    update_dict = {k: v for k, v in updates.dict().items() if v is not None}
    if not update_dict:
        raise HTTPException(status_code=400, detail="No valid updates provided")
    
    db.config.update_one(
        {"_id": "current"},
        {"$set": update_dict},
        upsert=True
    )
    return {"status": "updated", "changes": update_dict}

@app.post("/api/bot/start")
async def start_bot():
    """Start the trading bot"""
    db.stats.update_one(
        {"_id": "current"},
        {"$set": {"bot_running": True}},
        upsert=True
    )
    return {"status": "started", "message": "Bot started successfully"}

@app.post("/api/bot/stop")
async def stop_bot():
    """Stop the trading bot"""
    db.stats.update_one(
        {"_id": "current"},
        {"$set": {"bot_running": False}},
        upsert=True
    )
    return {"status": "stopped", "message": "Bot stopped successfully"}

@app.post("/api/sync")
async def sync_from_android(data: dict):
    """Receive sync data from Android app"""
    # Store stats
    if "stats" in data:
        db.stats.update_one(
            {"_id": "current"},
            {"$set": data["stats"]},
            upsert=True
        )
    
    # Store positions
    if "positions" in data:
        for pos in data["positions"]:
            db.positions.update_one(
                {"token_mint": pos["token_mint"]},
                {"$set": pos},
                upsert=True
            )
    
    # Store trades
    if "trades" in data:
        for trade in data["trades"]:
            db.trades.update_one(
                {"id": trade["id"]},
                {"$set": trade},
                upsert=True
            )
    
    return {"status": "synced", "timestamp": datetime.now(timezone.utc).isoformat()}

@app.get("/api/performance/daily")
async def get_daily_performance():
    """Get daily P&L performance data for charts"""
    # Generate sample data for chart
    data = []
    for i in range(30):
        data.append({
            "date": f"2024-12-{i+1:02d}",
            "pnl_sol": round(random.uniform(-0.5, 1.5), 4),
            "pnl_usd": round(random.uniform(-80, 250), 2),
            "trades": random.randint(3, 15),
            "win_rate": round(random.uniform(50, 80), 1)
        })
    return {"data": data}

@app.get("/api/performance/tokens")
async def get_token_performance():
    """Get performance breakdown by token"""
    tokens = ["BONK", "WIF", "POPCAT", "MYRO", "SLERF", "BOME"]
    data = []
    for token in tokens:
        data.append({
            "symbol": token,
            "trades": random.randint(5, 25),
            "wins": random.randint(3, 20),
            "total_pnl_pct": round(random.uniform(-20, 150), 1),
            "avg_hold_mins": round(random.uniform(8, 60), 1),
            "best_trade_pct": round(random.uniform(50, 400), 1)
        })
    return {"data": data}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8001)

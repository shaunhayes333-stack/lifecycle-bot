# Lifecycle Bot Web Dashboard

Real-time monitoring dashboard for the Lifecycle Bot Solana trading application.

## Features

- **Treasury Overview**: Real-time SOL balance, locked profits, and USD equivalents
- **Performance Metrics**: Win rate, Sharpe ratio, Sortino ratio, max drawdown
- **Open Positions**: Live P&L tracking for all active positions
- **Trade History**: Complete trade log with entry/exit details
- **Token Watchlist**: Scored tokens with entry/exit signals, whale %, safety scores
- **Decision Log**: Bot reasoning for every trade decision
- **Bot Controls**: Start/stop, paper mode, auto-trade toggles
- **Settings**: Notifications, sounds, and trading parameters

## Architecture

```
┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
│  Android App    │─────▶│  FastAPI Server │◀─────│  React Dashboard│
│  (Lifecycle Bot)│      │  (Backend)      │      │  (Frontend)     │
└─────────────────┘      └─────────────────┘      └─────────────────┘
         │                       │                        │
         │   POST /api/sync      │                        │
         └──────────────────────▶│                        │
                                 │  GET /api/stats        │
                                 │◀───────────────────────┘
                                 │  GET /api/positions    │
                                 │◀───────────────────────┘
                                 │  GET /api/trades       │
                                 │◀───────────────────────┘
```

## Setup

### Backend (FastAPI)

```bash
cd backend
pip install -r requirements.txt
uvicorn server:app --host 0.0.0.0 --port 8001
```

### Frontend (React)

```bash
cd frontend
npm install
# Set REACT_APP_BACKEND_URL in .env
npm start
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/health` | GET | Health check |
| `/api/stats` | GET | Bot statistics |
| `/api/positions` | GET | Open positions |
| `/api/trades` | GET | Trade history |
| `/api/watchlist` | GET | Token watchlist |
| `/api/decisions` | GET | Decision log |
| `/api/config` | GET/PATCH | Bot configuration |
| `/api/bot/start` | POST | Start bot |
| `/api/bot/stop` | POST | Stop bot |
| `/api/sync` | POST | Sync from Android |

## Android App Integration

The Android app can sync data to the dashboard via:

```kotlin
// In your sync service
val syncData = mapOf(
    "stats" to currentStats,
    "positions" to openPositions,
    "trades" to recentTrades
)
api.post("${dashboardUrl}/api/sync", syncData)
```

## Environment Variables

### Backend (.env)
```
MONGO_URL=mongodb://localhost:27017
DB_NAME=lifecycle_bot
CORS_ORIGINS=*
```

### Frontend (.env)
```
REACT_APP_BACKEND_URL=https://your-domain.com
```

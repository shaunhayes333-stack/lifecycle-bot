import React, { useState, useEffect } from 'react';
import { 
  Activity, Wallet, TrendingUp, TrendingDown, 
  BarChart3, Settings, Bell, Volume2, 
  Play, Pause, RefreshCw, Clock, Target,
  Shield, Zap, DollarSign, Percent
} from 'lucide-react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, AreaChart, Area } from 'recharts';

const API_URL = process.env.REACT_APP_BACKEND_URL || '';

function App() {
  const [stats, setStats] = useState(null);
  const [positions, setPositions] = useState([]);
  const [trades, setTrades] = useState([]);
  const [watchlist, setWatchlist] = useState([]);
  const [decisions, setDecisions] = useState([]);
  const [dailyPerformance, setDailyPerformance] = useState([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState('overview');

  useEffect(() => {
    fetchAllData();
    const interval = setInterval(fetchAllData, 10000); // Refresh every 10s
    return () => clearInterval(interval);
  }, []);

  const fetchAllData = async () => {
    try {
      const [statsRes, posRes, tradesRes, watchRes, decisionsRes, perfRes] = await Promise.all([
        fetch(`${API_URL}/api/stats`),
        fetch(`${API_URL}/api/positions`),
        fetch(`${API_URL}/api/trades`),
        fetch(`${API_URL}/api/watchlist`),
        fetch(`${API_URL}/api/decisions`),
        fetch(`${API_URL}/api/performance/daily`)
      ]);

      setStats(await statsRes.json());
      setPositions((await posRes.json()).positions || []);
      setTrades((await tradesRes.json()).trades || []);
      setWatchlist((await watchRes.json()).tokens || []);
      setDecisions((await decisionsRes.json()).logs || []);
      setDailyPerformance((await perfRes.json()).data || []);
      setLoading(false);
    } catch (error) {
      console.error('Failed to fetch data:', error);
      setLoading(false);
    }
  };

  const toggleBot = async (action) => {
    try {
      await fetch(`${API_URL}/api/bot/${action}`, { method: 'POST' });
      fetchAllData();
    } catch (error) {
      console.error('Failed to toggle bot:', error);
    }
  };

  const formatNumber = (num, decimals = 2) => {
    if (num === undefined || num === null) return '—';
    if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`;
    if (num >= 1000) return `${(num / 1000).toFixed(1)}K`;
    return num.toFixed(decimals);
  };

  const formatPrice = (price) => {
    if (!price) return '—';
    if (price < 0.0001) return price.toExponential(2);
    return `$${price.toFixed(6)}`;
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-center">
          <div className="w-12 h-12 border-4 border-purple-500 border-t-transparent rounded-full animate-spin mx-auto mb-4"></div>
          <p className="text-gray-400 font-mono">Loading Lifecycle Bot...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen p-4 md:p-6">
      {/* Header */}
      <header className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-4">
          <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-green-400 rounded-xl flex items-center justify-center">
            <Zap className="w-6 h-6 text-white" />
          </div>
          <div>
            <h1 className="text-xl font-bold">Lifecycle Bot</h1>
            <p className="text-xs text-gray-500 font-mono">AI-Powered Solana Trading</p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-gray-800/50 border border-gray-700">
            <div className={`status-dot ${stats?.bot_running ? 'active' : 'inactive'}`}></div>
            <span className="text-sm font-medium">{stats?.bot_running ? 'Running' : 'Stopped'}</span>
          </div>
          
          <button
            onClick={() => toggleBot(stats?.bot_running ? 'stop' : 'start')}
            className={`btn ${stats?.bot_running ? 'btn-danger' : 'btn-success'}`}
          >
            {stats?.bot_running ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4" />}
            {stats?.bot_running ? 'Stop' : 'Start'}
          </button>

          <button onClick={fetchAllData} className="btn btn-ghost">
            <RefreshCw className="w-4 h-4" />
          </button>
        </div>
      </header>

      {/* Navigation */}
      <nav className="flex gap-2 mb-6 border-b border-gray-800 pb-4">
        {['overview', 'positions', 'trades', 'watchlist', 'settings'].map(tab => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`px-4 py-2 rounded-lg font-medium text-sm transition-all ${
              activeTab === tab 
                ? 'bg-purple-500/20 text-purple-400 border border-purple-500/30' 
                : 'text-gray-400 hover:text-white hover:bg-gray-800'
            }`}
          >
            {tab.charAt(0).toUpperCase() + tab.slice(1)}
          </button>
        ))}
      </nav>

      {/* Main Content */}
      {activeTab === 'overview' && (
        <div className="space-y-6 fade-in">
          {/* Stats Grid */}
          <div className="stats-grid">
            <StatCard
              icon={<Wallet className="w-5 h-5" />}
              label="Treasury"
              value={`${formatNumber(stats?.treasury_sol, 4)} SOL`}
              subValue={`$${formatNumber(stats?.treasury_usd)}`}
              color="purple"
            />
            <StatCard
              icon={<Shield className="w-5 h-5" />}
              label="Locked Profit"
              value={`${formatNumber(stats?.locked_profit_sol, 4)} SOL`}
              subValue={`$${formatNumber(stats?.locked_profit_usd)}`}
              color="green"
            />
            <StatCard
              icon={<Target className="w-5 h-5" />}
              label="Win Rate"
              value={`${formatNumber(stats?.win_rate, 1)}%`}
              subValue={`${stats?.total_trades || 0} trades`}
              color="amber"
            />
            <StatCard
              icon={<BarChart3 className="w-5 h-5" />}
              label="Sharpe Ratio"
              value={formatNumber(stats?.sharpe_ratio, 2)}
              subValue={`Sortino: ${formatNumber(stats?.sortino_ratio, 2)}`}
              color="blue"
            />
          </div>

          {/* Performance Chart */}
          <div className="card">
            <div className="flex items-center justify-between mb-4">
              <h2 className="font-semibold">Daily P&L Performance</h2>
              <div className="flex items-center gap-2">
                <span className={`chip ${stats?.paper_mode ? 'chip-amber' : 'chip-green'}`}>
                  {stats?.paper_mode ? 'Paper' : 'Live'}
                </span>
                <span className="chip chip-purple">{stats?.current_tier || 'Unknown'} Tier</span>
              </div>
            </div>
            <div className="h-64">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={dailyPerformance}>
                  <defs>
                    <linearGradient id="colorPnl" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#9945FF" stopOpacity={0.3}/>
                      <stop offset="95%" stopColor="#9945FF" stopOpacity={0}/>
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1F2937" />
                  <XAxis dataKey="date" stroke="#6B7280" fontSize={10} />
                  <YAxis stroke="#6B7280" fontSize={10} />
                  <Tooltip 
                    contentStyle={{ background: '#111118', border: '1px solid #1F2937', borderRadius: '8px' }}
                    labelStyle={{ color: '#9CA3AF' }}
                  />
                  <Area type="monotone" dataKey="pnl_usd" stroke="#9945FF" fillOpacity={1} fill="url(#colorPnl)" />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Two Column Layout */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            {/* Open Positions */}
            <div className="card">
              <div className="flex items-center justify-between mb-4">
                <h2 className="font-semibold">Open Positions</h2>
                <span className="text-sm text-gray-500">{positions.length} active</span>
              </div>
              {positions.length > 0 ? (
                <div className="space-y-3">
                  {positions.map((pos, i) => (
                    <PositionRow key={i} position={pos} />
                  ))}
                </div>
              ) : (
                <p className="text-gray-500 text-center py-8">No open positions</p>
              )}
            </div>

            {/* Decision Log */}
            <div className="card">
              <div className="flex items-center justify-between mb-4">
                <h2 className="font-semibold">Decision Log</h2>
                <span className="text-sm text-gray-500">Last 10</span>
              </div>
              <div className="space-y-2 max-h-80 overflow-y-auto">
                {decisions.slice(0, 10).map((log, i) => (
                  <DecisionRow key={i} log={log} />
                ))}
              </div>
            </div>
          </div>

          {/* Recent Trades */}
          <div className="card">
            <div className="flex items-center justify-between mb-4">
              <h2 className="font-semibold">Recent Trades</h2>
              <span className="text-sm text-gray-500">{trades.length} total</span>
            </div>
            <div className="overflow-x-auto">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Token</th>
                    <th>Side</th>
                    <th>Value</th>
                    <th>Price</th>
                    <th>P&L</th>
                    <th>Signal</th>
                  </tr>
                </thead>
                <tbody>
                  {trades.slice(0, 10).map((trade, i) => (
                    <tr key={i}>
                      <td className="font-mono font-semibold">{trade.token_symbol}</td>
                      <td>
                        <span className={`chip ${trade.side === 'BUY' ? 'chip-green' : 'chip-red'}`}>
                          {trade.side}
                        </span>
                      </td>
                      <td className="font-mono">{trade.value_sol?.toFixed(4)} SOL</td>
                      <td className="font-mono text-gray-400">{formatPrice(trade.price)}</td>
                      <td className={`font-mono ${trade.pnl_pct > 0 ? 'text-green-400' : trade.pnl_pct < 0 ? 'text-red-400' : 'text-gray-400'}`}>
                        {trade.pnl_pct ? `${trade.pnl_pct > 0 ? '+' : ''}${trade.pnl_pct.toFixed(1)}%` : '—'}
                      </td>
                      <td className="font-mono text-purple-400">{trade.signal_score?.toFixed(0) || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'positions' && (
        <div className="card fade-in">
          <h2 className="font-semibold mb-4">All Positions</h2>
          {positions.length > 0 ? (
            <div className="space-y-4">
              {positions.map((pos, i) => (
                <div key={i} className="p-4 bg-gray-800/50 rounded-lg border border-gray-700">
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-3">
                      <span className="text-lg font-bold">{pos.token_symbol}</span>
                      <span className={`chip ${pos.pnl_pct >= 0 ? 'chip-green' : 'chip-red'}`}>
                        {pos.pnl_pct >= 0 ? '+' : ''}{pos.pnl_pct?.toFixed(1)}%
                      </span>
                    </div>
                    <span className="text-sm text-gray-500 font-mono">{pos.status}</span>
                  </div>
                  <div className="grid grid-cols-4 gap-4 text-sm">
                    <div>
                      <span className="text-gray-500 block">Entry</span>
                      <span className="font-mono">{formatPrice(pos.entry_price)}</span>
                    </div>
                    <div>
                      <span className="text-gray-500 block">Current</span>
                      <span className="font-mono">{formatPrice(pos.current_price)}</span>
                    </div>
                    <div>
                      <span className="text-gray-500 block">Quantity</span>
                      <span className="font-mono">{formatNumber(pos.quantity, 0)}</span>
                    </div>
                    <div>
                      <span className="text-gray-500 block">P&L USD</span>
                      <span className={`font-mono ${pos.pnl_usd >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                        ${formatNumber(pos.pnl_usd)}
                      </span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-gray-500 text-center py-12">No positions to display</p>
          )}
        </div>
      )}

      {activeTab === 'trades' && (
        <div className="card fade-in">
          <h2 className="font-semibold mb-4">Trade History</h2>
          <div className="overflow-x-auto">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Token</th>
                  <th>Side</th>
                  <th>Quantity</th>
                  <th>Price</th>
                  <th>Value SOL</th>
                  <th>Value USD</th>
                  <th>P&L</th>
                  <th>Signal</th>
                </tr>
              </thead>
              <tbody>
                {trades.map((trade, i) => (
                  <tr key={i}>
                    <td className="font-mono font-semibold">{trade.token_symbol}</td>
                    <td>
                      <span className={`chip ${trade.side === 'BUY' ? 'chip-green' : 'chip-red'}`}>
                        {trade.side}
                      </span>
                    </td>
                    <td className="font-mono">{formatNumber(trade.quantity, 0)}</td>
                    <td className="font-mono text-gray-400">{formatPrice(trade.price)}</td>
                    <td className="font-mono">{trade.value_sol?.toFixed(4)}</td>
                    <td className="font-mono">${trade.value_usd?.toFixed(2)}</td>
                    <td className={`font-mono ${trade.pnl_pct > 0 ? 'text-green-400' : trade.pnl_pct < 0 ? 'text-red-400' : 'text-gray-400'}`}>
                      {trade.pnl_pct ? `${trade.pnl_pct > 0 ? '+' : ''}${trade.pnl_pct.toFixed(1)}%` : '—'}
                    </td>
                    <td className="font-mono text-purple-400">{trade.signal_score?.toFixed(0) || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {activeTab === 'watchlist' && (
        <div className="card fade-in">
          <h2 className="font-semibold mb-4">Token Watchlist</h2>
          <div className="overflow-x-auto">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Token</th>
                  <th>Price</th>
                  <th>MCap</th>
                  <th>24h Change</th>
                  <th>Entry Score</th>
                  <th>Exit Score</th>
                  <th>Whale %</th>
                  <th>Safety</th>
                </tr>
              </thead>
              <tbody>
                {watchlist.map((token, i) => (
                  <tr key={i}>
                    <td className="font-mono font-semibold">{token.symbol}</td>
                    <td className="font-mono">{formatPrice(token.price)}</td>
                    <td className="font-mono">${formatNumber(token.mcap)}</td>
                    <td className={`font-mono ${token.change_24h >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                      {token.change_24h >= 0 ? '+' : ''}{token.change_24h?.toFixed(1)}%
                    </td>
                    <td>
                      <div className="flex items-center gap-2">
                        <div className="progress-bar w-16">
                          <div 
                            className="progress-bar-fill bg-purple-500" 
                            style={{ width: `${token.entry_score}%` }}
                          ></div>
                        </div>
                        <span className="font-mono text-xs">{token.entry_score?.toFixed(0)}</span>
                      </div>
                    </td>
                    <td>
                      <div className="flex items-center gap-2">
                        <div className="progress-bar w-16">
                          <div 
                            className="progress-bar-fill bg-red-500" 
                            style={{ width: `${token.exit_score}%` }}
                          ></div>
                        </div>
                        <span className="font-mono text-xs">{token.exit_score?.toFixed(0)}</span>
                      </div>
                    </td>
                    <td className="font-mono text-amber-400">{token.whale_pct?.toFixed(1)}%</td>
                    <td>
                      <span className={`chip ${token.safety_score >= 80 ? 'chip-green' : token.safety_score >= 60 ? 'chip-amber' : 'chip-red'}`}>
                        {token.safety_score?.toFixed(0)}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {activeTab === 'settings' && (
        <SettingsPanel stats={stats} onRefresh={fetchAllData} />
      )}
    </div>
  );
}

// Components
function StatCard({ icon, label, value, subValue, color }) {
  const colorClasses = {
    purple: 'text-purple-400 bg-purple-500/10 border-purple-500/20',
    green: 'text-green-400 bg-green-500/10 border-green-500/20',
    amber: 'text-amber-400 bg-amber-500/10 border-amber-500/20',
    blue: 'text-blue-400 bg-blue-500/10 border-blue-500/20',
    red: 'text-red-400 bg-red-500/10 border-red-500/20',
  };

  return (
    <div className={`card border ${colorClasses[color]}`}>
      <div className="flex items-center gap-3 mb-3">
        <div className={colorClasses[color].split(' ')[0]}>{icon}</div>
        <span className="text-xs text-gray-500 uppercase tracking-wider">{label}</span>
      </div>
      <div className="text-2xl font-bold font-mono">{value}</div>
      <div className="text-sm text-gray-500 font-mono mt-1">{subValue}</div>
    </div>
  );
}

function PositionRow({ position }) {
  const pnlPositive = position.pnl_pct >= 0;
  
  return (
    <div className="flex items-center justify-between p-3 bg-gray-800/30 rounded-lg">
      <div className="flex items-center gap-3">
        <span className="font-semibold">{position.token_symbol}</span>
        <span className="text-xs text-gray-500 font-mono">
          {position.quantity?.toLocaleString()} tokens
        </span>
      </div>
      <div className="flex items-center gap-4">
        <span className="font-mono text-sm text-gray-400">
          ${position.current_price?.toFixed(8)}
        </span>
        <span className={`font-mono font-semibold ${pnlPositive ? 'text-green-400' : 'text-red-400'}`}>
          {pnlPositive ? '+' : ''}{position.pnl_pct?.toFixed(1)}%
        </span>
      </div>
    </div>
  );
}

function DecisionRow({ log }) {
  const actionColors = {
    BUY_SIGNAL: 'chip-green',
    SELL_SIGNAL: 'chip-red',
    HOLD: 'chip-purple',
    SKIP: 'chip-amber',
    WATCHING: 'chip-amber',
  };

  return (
    <div className="flex items-start gap-3 p-2 hover:bg-gray-800/30 rounded">
      <span className={`chip text-xs ${actionColors[log.action] || 'chip-purple'}`}>
        {log.action}
      </span>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="font-semibold text-sm">{log.token_symbol}</span>
          <span className="text-xs text-gray-500 font-mono">
            E:{log.entry_score?.toFixed(0)} / X:{log.exit_score?.toFixed(0)}
          </span>
        </div>
        <p className="text-xs text-gray-500 truncate">{log.reason}</p>
      </div>
    </div>
  );
}

function SettingsPanel({ stats, onRefresh }) {
  const [config, setConfig] = useState({
    paper_mode: stats?.paper_mode || false,
    auto_trade: stats?.auto_trade || true,
    notifications_enabled: true,
    sound_enabled: true,
    stop_loss_pct: 10.0,
    exit_score_threshold: 58.0,
    small_buy_sol: 0.05,
    large_buy_sol: 0.10,
    slippage_bps: 200,
  });

  const handleToggle = (key) => {
    setConfig(prev => ({ ...prev, [key]: !prev[key] }));
  };

  const handleChange = (key, value) => {
    setConfig(prev => ({ ...prev, [key]: value }));
  };

  const saveConfig = async () => {
    try {
      await fetch(`${API_URL}/api/config`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config)
      });
      onRefresh();
    } catch (error) {
      console.error('Failed to save config:', error);
    }
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 fade-in">
      {/* General Settings */}
      <div className="card">
        <h2 className="font-semibold mb-6 flex items-center gap-2">
          <Settings className="w-5 h-5 text-purple-400" /> General Settings
        </h2>
        
        <div className="space-y-4">
          <SettingToggle
            label="Paper Mode"
            description="Trade with simulated funds"
            enabled={config.paper_mode}
            onToggle={() => handleToggle('paper_mode')}
          />
          
          <SettingToggle
            label="Auto Trade"
            description="Execute trades automatically based on signals"
            enabled={config.auto_trade}
            onToggle={() => handleToggle('auto_trade')}
          />

          <div className="border-t border-gray-800 pt-4">
            <h3 className="text-sm font-semibold text-purple-400 mb-4 flex items-center gap-2">
              <Bell className="w-4 h-4" /> Alerts & Sounds
            </h3>
            
            <SettingToggle
              label="Push Notifications"
              description="Trade alerts, signals, position updates"
              enabled={config.notifications_enabled}
              onToggle={() => handleToggle('notifications_enabled')}
            />
            
            <SettingToggle
              label="Sound Effects"
              description="Audio feedback for buy/sell executions"
              enabled={config.sound_enabled}
              onToggle={() => handleToggle('sound_enabled')}
            />
          </div>
        </div>
      </div>

      {/* Strategy Configurator */}
      <div className="card">
        <h2 className="font-semibold mb-6 flex items-center gap-2">
          <Target className="w-5 h-5 text-green-400" /> Strategy Configurator
        </h2>
        
        <div className="space-y-5">
          <SettingInput
            label="Stop Loss %"
            description="Exit position if loss exceeds this percentage"
            value={config.stop_loss_pct}
            onChange={(v) => handleChange('stop_loss_pct', parseFloat(v) || 0)}
            suffix="%"
          />
          
          <SettingInput
            label="Exit Score Threshold"
            description="Trigger exit when exit score exceeds this value"
            value={config.exit_score_threshold}
            onChange={(v) => handleChange('exit_score_threshold', parseFloat(v) || 0)}
          />
          
          <div className="grid grid-cols-2 gap-4">
            <SettingInput
              label="Small Buy (SOL)"
              description="Standard position size"
              value={config.small_buy_sol}
              onChange={(v) => handleChange('small_buy_sol', parseFloat(v) || 0)}
              suffix="SOL"
            />
            
            <SettingInput
              label="Large Buy (SOL)"
              description="High conviction size"
              value={config.large_buy_sol}
              onChange={(v) => handleChange('large_buy_sol', parseFloat(v) || 0)}
              suffix="SOL"
            />
          </div>
          
          <SettingInput
            label="Slippage (BPS)"
            description="Max slippage tolerance in basis points"
            value={config.slippage_bps}
            onChange={(v) => handleChange('slippage_bps', parseInt(v) || 0)}
            suffix="bps"
          />
        </div>
      </div>

      {/* Save Button - Full Width */}
      <div className="lg:col-span-2">
        <button onClick={saveConfig} className="btn btn-primary w-full">
          Save All Settings
        </button>
      </div>
    </div>
  );
}

function SettingToggle({ label, description, enabled, onToggle }) {
  return (
    <div className="flex items-center justify-between py-3">
      <div>
        <span className="font-medium">{label}</span>
        <p className="text-sm text-gray-500">{description}</p>
      </div>
      <button
        onClick={onToggle}
        className={`toggle ${enabled ? 'active' : ''}`}
      />
    </div>
  );
}

function SettingInput({ label, description, value, onChange, suffix }) {
  return (
    <div>
      <label className="block text-sm font-medium mb-1">{label}</label>
      <p className="text-xs text-gray-500 mb-2">{description}</p>
      <div className="relative">
        <input
          type="number"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-white font-mono focus:border-purple-500 focus:outline-none"
        />
        {suffix && (
          <span className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-500 text-sm">
            {suffix}
          </span>
        )}
      </div>
    </div>
  );
}

export default App;

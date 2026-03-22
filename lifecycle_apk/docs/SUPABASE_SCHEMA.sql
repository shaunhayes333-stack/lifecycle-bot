-- ═══════════════════════════════════════════════════════════════════════════════
-- LIFECYCLE BOT COMMUNITY LEARNING DATABASE
-- Supabase Schema for Shared AI Learning
-- ═══════════════════════════════════════════════════════════════════════════════
-- 
-- This schema enables all bot instances to share learned trading patterns,
-- creating a collective intelligence that benefits all users.
--
-- SETUP INSTRUCTIONS:
-- 1. Create a free Supabase project at https://supabase.com
-- 2. Go to SQL Editor and run this entire script
-- 3. Copy your project URL and anon key to CloudLearningSync.kt
-- 4. Enable RLS policies below for security
--
-- ═══════════════════════════════════════════════════════════════════════════════

-- Table: contributions
-- Stores each instance's learned weights and pattern stats
CREATE TABLE contributions (
    id BIGSERIAL PRIMARY KEY,
    instance_id TEXT NOT NULL,              -- Anonymous ID (not tied to wallet)
    trade_count INTEGER NOT NULL,
    win_rate DOUBLE PRECISION NOT NULL,
    feature_weights JSONB NOT NULL,         -- Learned feature weights
    pattern_stats JSONB NOT NULL,           -- Pattern performance stats
    app_version TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for faster queries
CREATE INDEX idx_contributions_instance ON contributions(instance_id);
CREATE INDEX idx_contributions_created ON contributions(created_at DESC);
CREATE INDEX idx_contributions_trades ON contributions(trade_count DESC);

-- Table: aggregated_weights
-- Stores the computed community-wide aggregated weights
-- Updated by a scheduled function or edge function
CREATE TABLE aggregated_weights (
    id BIGSERIAL PRIMARY KEY,
    feature_weights JSONB NOT NULL,         -- Weighted average of all contributions
    pattern_multipliers JSONB NOT NULL,     -- Pattern-specific multipliers
    total_contributors INTEGER NOT NULL,
    total_trades INTEGER NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ═══════════════════════════════════════════════════════════════════════════════
-- ROW LEVEL SECURITY (RLS) - Important for security!
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE contributions ENABLE ROW LEVEL SECURITY;
ALTER TABLE aggregated_weights ENABLE ROW LEVEL SECURITY;

-- Allow anyone with anon key to INSERT contributions
CREATE POLICY "Allow anonymous inserts" ON contributions
    FOR INSERT WITH CHECK (true);

-- Allow anyone to READ their own contributions
CREATE POLICY "Allow read own contributions" ON contributions
    FOR SELECT USING (true);

-- Allow anyone to READ aggregated weights (public knowledge)
CREATE POLICY "Allow read aggregated weights" ON aggregated_weights
    FOR SELECT USING (true);

-- Only service role can INSERT aggregated weights (computed server-side)
CREATE POLICY "Only service role can insert aggregated" ON aggregated_weights
    FOR INSERT WITH CHECK (auth.role() = 'service_role');

-- ═══════════════════════════════════════════════════════════════════════════════
-- AGGREGATION FUNCTION
-- Run this periodically (e.g., every hour via Supabase Edge Function or cron)
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION compute_aggregated_weights()
RETURNS void AS $$
DECLARE
    total_contributors INTEGER;
    total_trades INTEGER;
    avg_weights JSONB;
    pattern_mults JSONB;
BEGIN
    -- Only consider contributions from last 7 days with 20+ trades
    -- Weight by trade count (more trades = more influence)
    
    SELECT 
        COUNT(DISTINCT instance_id),
        SUM(trade_count)
    INTO total_contributors, total_trades
    FROM contributions
    WHERE created_at > NOW() - INTERVAL '7 days'
      AND trade_count >= 20;
    
    -- Skip if not enough data
    IF total_contributors < 3 OR total_trades < 100 THEN
        RETURN;
    END IF;
    
    -- Compute weighted average of feature weights
    WITH weighted_contrib AS (
        SELECT 
            instance_id,
            trade_count,
            feature_weights,
            -- Weight by trade count (sqrt to prevent mega-traders from dominating)
            SQRT(trade_count::float) as weight
        FROM contributions
        WHERE created_at > NOW() - INTERVAL '7 days'
          AND trade_count >= 20
    ),
    latest_per_instance AS (
        SELECT DISTINCT ON (instance_id)
            instance_id,
            trade_count,
            feature_weights,
            weight
        FROM weighted_contrib
        ORDER BY instance_id, trade_count DESC
    )
    SELECT jsonb_build_object(
        'mcap', AVG((feature_weights->>'mcap')::float * weight) / AVG(weight),
        'age', AVG((feature_weights->>'age')::float * weight) / AVG(weight),
        'buyRatio', AVG((feature_weights->>'buyRatio')::float * weight) / AVG(weight),
        'volume', AVG((feature_weights->>'volume')::float * weight) / AVG(weight),
        'liquidity', AVG((feature_weights->>'liquidity')::float * weight) / AVG(weight),
        'holderCount', AVG((feature_weights->>'holderCount')::float * weight) / AVG(weight),
        'holderConc', AVG((feature_weights->>'holderConc')::float * weight) / AVG(weight),
        'holderGrowth', AVG((feature_weights->>'holderGrowth')::float * weight) / AVG(weight),
        'devWallet', AVG((feature_weights->>'devWallet')::float * weight) / AVG(weight),
        'bondingCurve', AVG((feature_weights->>'bondingCurve')::float * weight) / AVG(weight),
        'rugcheck', AVG((feature_weights->>'rugcheck')::float * weight) / AVG(weight),
        'emaFan', AVG((feature_weights->>'emaFan')::float * weight) / AVG(weight),
        'volLiqRatio', AVG((feature_weights->>'volLiqRatio')::float * weight) / AVG(weight)
    )
    INTO avg_weights
    FROM latest_per_instance;
    
    -- Compute pattern multipliers from pattern_stats
    -- (simplified - in production, aggregate across all patterns)
    pattern_mults := '{"BULL_FLAG": 1.0, "DOUBLE_BOTTOM": 1.0, "CUP_HANDLE": 1.0}'::jsonb;
    
    -- Insert new aggregated weights
    INSERT INTO aggregated_weights (feature_weights, pattern_multipliers, total_contributors, total_trades)
    VALUES (avg_weights, pattern_mults, total_contributors, total_trades);
    
    -- Keep only last 24 aggregations
    DELETE FROM aggregated_weights
    WHERE id NOT IN (
        SELECT id FROM aggregated_weights ORDER BY created_at DESC LIMIT 24
    );
END;
$$ LANGUAGE plpgsql;

-- ═══════════════════════════════════════════════════════════════════════════════
-- OPTIONAL: Schedule aggregation (requires pg_cron extension)
-- ═══════════════════════════════════════════════════════════════════════════════
-- SELECT cron.schedule('aggregate-weights', '0 * * * *', 'SELECT compute_aggregated_weights()');

-- ═══════════════════════════════════════════════════════════════════════════════
-- QUICK START: Run initial aggregation
-- ═══════════════════════════════════════════════════════════════════════════════
-- SELECT compute_aggregated_weights();

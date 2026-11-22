-- Beenewed Supabase (PostgreSQL) Schema with Row Level Security
-- Run this script in the Supabase SQL Editor before launching the app.

-- =====================================================================
-- Helper trigger to maintain updated_at timestamps
-- =====================================================================
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

-- =====================================================================
-- Tables
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    category TEXT NOT NULL CHECK (length(trim(category)) > 0),
    icon_key TEXT CHECK (icon_key IS NULL OR length(trim(icon_key)) > 0),
    current_price NUMERIC(12, 2) NOT NULL CHECK (current_price > 0),
    billing_cycle TEXT NOT NULL CHECK (billing_cycle IN ('MONTHLY', 'YEARLY')),
    next_renewal_date DATE NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.payments (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    subscription_id BIGINT NOT NULL REFERENCES public.subscriptions(id) ON DELETE RESTRICT,
    payment_date DATE NOT NULL,
    amount_paid NUMERIC(12, 2) NOT NULL CHECK (amount_paid > 0),
    renewal_period TEXT NOT NULL CHECK (length(trim(renewal_period)) > 0),
    payment_method TEXT,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.members (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    subscription_id BIGINT NOT NULL REFERENCES public.subscriptions(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    role_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.contributions (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    member_id BIGINT NOT NULL REFERENCES public.members(id) ON DELETE RESTRICT,
    payment_id BIGINT NOT NULL REFERENCES public.payments(id) ON DELETE RESTRICT,
    amount_received NUMERIC(12, 2) NOT NULL CHECK (amount_received > 0),
    date_received DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.usage_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    subscription_id BIGINT NOT NULL REFERENCES public.subscriptions(id) ON DELETE CASCADE,
    usage_date DATE NOT NULL,
    quantity DOUBLE PRECISION,
    unit TEXT,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (
        quantity IS NULL
        OR (quantity > 0 AND unit IS NOT NULL AND length(trim(unit)) > 0)
    )
);

CREATE TABLE IF NOT EXISTS public.price_history (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    subscription_id BIGINT NOT NULL REFERENCES public.subscriptions(id) ON DELETE CASCADE,
    previous_price NUMERIC(12, 2) NOT NULL CHECK (previous_price > 0),
    new_price NUMERIC(12, 2) NOT NULL CHECK (new_price > 0),
    effective_date DATE NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.app_preferences (
    user_id UUID PRIMARY KEY DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    currency TEXT NOT NULL DEFAULT 'PHP' CHECK (length(trim(currency)) > 0),
    theme TEXT NOT NULL DEFAULT 'LIGHT' CHECK (theme IN ('LIGHT', 'DARK')),
    reminder_lead_days INTEGER NOT NULL DEFAULT 7 CHECK (reminder_lead_days >= 0),
    show_overdue_reminders BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =====================================================================
-- Triggers
-- =====================================================================

DROP TRIGGER IF EXISTS trg_subscriptions_updated_at ON public.subscriptions;
CREATE TRIGGER trg_subscriptions_updated_at
    BEFORE UPDATE ON public.subscriptions
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS trg_members_updated_at ON public.members;
CREATE TRIGGER trg_members_updated_at
    BEFORE UPDATE ON public.members
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS trg_app_preferences_updated_at ON public.app_preferences;
CREATE TRIGGER trg_app_preferences_updated_at
    BEFORE UPDATE ON public.app_preferences
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- =====================================================================
-- Indexes
-- =====================================================================

CREATE INDEX IF NOT EXISTS idx_subscriptions_user_status_renewal
    ON public.subscriptions(user_id, status, next_renewal_date);
CREATE INDEX IF NOT EXISTS idx_subscriptions_user_category
    ON public.subscriptions(user_id, category);
CREATE INDEX IF NOT EXISTS idx_payments_user_subscription_date
    ON public.payments(user_id, subscription_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_payments_user_date
    ON public.payments(user_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_members_user_subscription
    ON public.members(user_id, subscription_id);
CREATE INDEX IF NOT EXISTS idx_contributions_user_payment
    ON public.contributions(user_id, payment_id);
CREATE INDEX IF NOT EXISTS idx_contributions_user_member
    ON public.contributions(user_id, member_id);
CREATE INDEX IF NOT EXISTS idx_usage_logs_user_subscription_date
    ON public.usage_logs(user_id, subscription_id, usage_date);
CREATE INDEX IF NOT EXISTS idx_price_history_user_subscription_effective
    ON public.price_history(user_id, subscription_id, effective_date);

-- =====================================================================
-- Row Level Security (RLS)
-- =====================================================================

ALTER TABLE public.subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.contributions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.usage_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.price_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_preferences ENABLE ROW LEVEL SECURITY;

-- subscriptions policies
DROP POLICY IF EXISTS "subscriptions_select_own" ON public.subscriptions;
CREATE POLICY "subscriptions_select_own" ON public.subscriptions
    FOR SELECT TO authenticated USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "subscriptions_insert_own" ON public.subscriptions;
CREATE POLICY "subscriptions_insert_own" ON public.subscriptions
    FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "subscriptions_update_own" ON public.subscriptions;
CREATE POLICY "subscriptions_update_own" ON public.subscriptions
    FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "subscriptions_delete_own" ON public.subscriptions;
CREATE POLICY "subscriptions_delete_own" ON public.subscriptions
    FOR DELETE TO authenticated USING (auth.uid() = user_id);

-- payments policies
DROP POLICY IF EXISTS "payments_select_own" ON public.payments;
CREATE POLICY "payments_select_own" ON public.payments
    FOR SELECT TO authenticated USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "payments_insert_own" ON public.payments;
CREATE POLICY "payments_insert_own" ON public.payments
    FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "payments_update_own" ON public.payments;
CREATE POLICY "payments_update_own" ON public.payments
    FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "payments_delete_own" ON public.payments;
CREATE POLICY "payments_delete_own" ON public.payments
    FOR DELETE TO authenticated USING (auth.uid() = user_id);

-- members policies
DROP POLICY IF EXISTS "members_select_own" ON public.members;
CREATE POLICY "members_select_own" ON public.members
    FOR SELECT TO authenticated USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "members_insert_own" ON public.members;
CREATE POLICY "members_insert_own" ON public.members
    FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "members_update_own" ON public.members;
CREATE POLICY "members_update_own" ON public.members
    FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "members_delete_own" ON public.members;
CREATE POLICY "members_delete_own" ON public.members
    FOR DELETE TO authenticated USING (auth.uid() = user_id);

-- contributions policies
DROP POLICY IF EXISTS "contributions_select_own" ON public.contributions;
CREATE POLICY "contributions_select_own" ON public.contributions
    FOR SELECT TO authenticated USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "contributions_insert_own" ON public.contributions;
CREATE POLICY "contributions_insert_own" ON public.contributions
    FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "contributions_update_own" ON public.contributions;
CREATE POLICY "contributions_update_own" ON public.contributions
    FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "contributions_delete_own" ON public.contributions;
CREATE POLICY "contributions_delete_own" ON public.contributions
    FOR DELETE TO authenticated USING (auth.uid() = user_id);

-- usage_logs policies
DROP POLICY IF EXISTS "usage_logs_select_own" ON public.usage_logs;
CREATE POLICY "usage_logs_select_own" ON public.usage_logs
    FOR SELECT TO authenticated USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "usage_logs_insert_own" ON public.usage_logs;
CREATE POLICY "usage_logs_insert_own" ON public.usage_logs
    FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "usage_logs_update_own" ON public.usage_logs;
CREATE POLICY "usage_logs_update_own" ON public.usage_logs
    FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "usage_logs_delete_own" ON public.usage_logs;
CREATE POLICY "usage_logs_delete_own" ON public.usage_logs
    FOR DELETE TO authenticated USING (auth.uid() = user_id);

-- price_history policies
DROP POLICY IF EXISTS "price_history_select_own" ON public.price_history;
CREATE POLICY "price_history_select_own" ON public.price_history
    FOR SELECT TO authenticated USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "price_history_insert_own" ON public.price_history;
CREATE POLICY "price_history_insert_own" ON public.price_history
    FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "price_history_update_own" ON public.price_history;
CREATE POLICY "price_history_update_own" ON public.price_history
    FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "price_history_delete_own" ON public.price_history;
CREATE POLICY "price_history_delete_own" ON public.price_history
    FOR DELETE TO authenticated USING (auth.uid() = user_id);

-- app_preferences policies
DROP POLICY IF EXISTS "app_preferences_select_own" ON public.app_preferences;
CREATE POLICY "app_preferences_select_own" ON public.app_preferences
    FOR SELECT TO authenticated USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "app_preferences_insert_own" ON public.app_preferences;
CREATE POLICY "app_preferences_insert_own" ON public.app_preferences
    FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "app_preferences_update_own" ON public.app_preferences;
CREATE POLICY "app_preferences_update_own" ON public.app_preferences
    FOR UPDATE TO authenticated USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "app_preferences_delete_own" ON public.app_preferences;
CREATE POLICY "app_preferences_delete_own" ON public.app_preferences
    FOR DELETE TO authenticated USING (auth.uid() = user_id);

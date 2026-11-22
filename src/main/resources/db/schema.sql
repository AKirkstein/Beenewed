CREATE TABLE IF NOT EXISTS subscriptions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    category TEXT NOT NULL CHECK (length(trim(category)) > 0),
    icon_key TEXT CHECK (icon_key IS NULL OR length(trim(icon_key)) > 0),
    current_price TEXT NOT NULL
        CHECK (length(trim(current_price)) > 0 AND CAST(current_price AS NUMERIC) > 0),
    billing_cycle TEXT NOT NULL CHECK (billing_cycle IN ('MONTHLY', 'YEARLY')),
    next_renewal_date TEXT NOT NULL
        CHECK (
            length(next_renewal_date) = 10
            AND next_renewal_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        ),
    status TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED')),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    subscription_id INTEGER NOT NULL,
    payment_date TEXT NOT NULL
        CHECK (
            length(payment_date) = 10
            AND payment_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        ),
    amount_paid TEXT NOT NULL
        CHECK (length(trim(amount_paid)) > 0 AND CAST(amount_paid AS NUMERIC) > 0),
    renewal_period TEXT NOT NULL CHECK (length(trim(renewal_period)) > 0),
    payment_method TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS members (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    subscription_id INTEGER NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    role_notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS contributions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id INTEGER NOT NULL,
    payment_id INTEGER NOT NULL,
    amount_received TEXT NOT NULL
        CHECK (length(trim(amount_received)) > 0 AND CAST(amount_received AS NUMERIC) > 0),
    date_received TEXT NOT NULL
        CHECK (
            length(date_received) = 10
            AND date_received GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        ),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (member_id) REFERENCES members(id) ON DELETE RESTRICT,
    FOREIGN KEY (payment_id) REFERENCES payments(id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS usage_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    subscription_id INTEGER NOT NULL,
    usage_date TEXT NOT NULL
        CHECK (
            length(usage_date) = 10
            AND usage_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        ),
    quantity REAL,
    unit TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (
        quantity IS NULL
        OR (quantity > 0 AND unit IS NOT NULL AND length(trim(unit)) > 0)
    ),
    FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS price_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    subscription_id INTEGER NOT NULL,
    previous_price TEXT NOT NULL
        CHECK (length(trim(previous_price)) > 0 AND CAST(previous_price AS NUMERIC) > 0),
    new_price TEXT NOT NULL
        CHECK (length(trim(new_price)) > 0 AND CAST(new_price AS NUMERIC) > 0),
    effective_date TEXT NOT NULL
        CHECK (
            length(effective_date) = 10
            AND effective_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        ),
    reason TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS app_preferences (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    currency TEXT NOT NULL DEFAULT 'PHP' CHECK (length(trim(currency)) > 0),
    theme TEXT NOT NULL DEFAULT 'LIGHT' CHECK (theme IN ('LIGHT', 'DARK')),
    reminder_lead_days INTEGER NOT NULL DEFAULT 7 CHECK (reminder_lead_days >= 0),
    show_overdue_reminders INTEGER NOT NULL DEFAULT 1
        CHECK (show_overdue_reminders IN (0, 1))
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_status_renewal
    ON subscriptions(status, next_renewal_date);
CREATE INDEX IF NOT EXISTS idx_subscriptions_category
    ON subscriptions(category);
CREATE INDEX IF NOT EXISTS idx_payments_subscription_date
    ON payments(subscription_id, payment_date);
CREATE INDEX IF NOT EXISTS idx_payments_date
    ON payments(payment_date);
CREATE INDEX IF NOT EXISTS idx_members_subscription
    ON members(subscription_id);
CREATE INDEX IF NOT EXISTS idx_contributions_payment
    ON contributions(payment_id);
CREATE INDEX IF NOT EXISTS idx_contributions_member
    ON contributions(member_id);
CREATE INDEX IF NOT EXISTS idx_usage_logs_subscription_date
    ON usage_logs(subscription_id, usage_date);
CREATE INDEX IF NOT EXISTS idx_price_history_subscription_effective
    ON price_history(subscription_id, effective_date);

INSERT OR IGNORE INTO app_preferences (id) VALUES (1);

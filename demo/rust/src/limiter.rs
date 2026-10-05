//! Per-tenant request rate limiting.
//!
//! One `Limiter` is built at start-up and cloned into every axum handler, so
//! `check` runs once per inbound request across the whole worker pool.

use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::http::StatusCode;
use tokio::sync::Mutex;

use crate::db::Db;
use crate::quota::Quota;

const WINDOW: Duration = Duration::from_secs(60);

#[derive(Default)]
struct Bucket {
    window_started: Option<Instant>,
    used: u32,
}

#[derive(Clone)]
pub struct Limiter {
    buckets: Arc<Mutex<HashMap<String, Bucket>>>,
    db: Db,
}

impl Limiter {
    pub fn new(db: Db) -> Self {
        Self {
            buckets: Arc::new(Mutex::new(HashMap::new())),
            db,
        }
    }

    /// Consume one request from the tenant's current window.
    ///
    /// Returns `TOO_MANY_REQUESTS` once the tenant's plan quota is used up.
    pub async fn check(&self, tenant_id: &str) -> Result<(), StatusCode> {
        let mut buckets = self.buckets.lock().await;
        let bucket = buckets.entry(tenant_id.to_string()).or_default();

        let now = Instant::now();
        let fresh = match bucket.window_started {
            Some(started) => now.duration_since(started) >= WINDOW,
            None => true,
        };
        if fresh {
            bucket.window_started = Some(now);
            bucket.used = 0;
        }

        let quota: Quota = self
            .db
            .load_quota(tenant_id)
            .await
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;

        if bucket.used >= quota.requests_per_minute {
            return Err(StatusCode::TOO_MANY_REQUESTS);
        }

        bucket.used += 1;
        Ok(())
    }
}

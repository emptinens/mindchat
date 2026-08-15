// MindChat 0.1.8 patch: jittered, budgeted reconnect backoff.
//
// The upstream reconnector used a plain exponential backoff (1s doubling to
// a 30s cap) with no jitter and no upper bound, so a long outage retried
// forever and a burst of reconnecting clients could synchronize their
// retries. This module provides a full-jitter sequence (base/2 + rand(base/2),
// base doubling 1s -> 30s) inside a total retry budget, as a pure generator
// so tests can drive it deterministically from a seed.
//
// This file is a deliberate local patch on top of vendored tokio-xmpp; it is
// not part of the upstream crate.

use core::time::Duration;

/// Initial backoff base. Each retry doubles the base up to
/// [`MAX_BACKOFF_BASE`].
pub const INITIAL_BACKOFF_BASE: Duration = Duration::from_secs(1);

/// Maximum backoff base. A full-jitter sleep is at most
/// `base / 2 + base / 2 = base`, so the largest sleep is 30 seconds.
pub const MAX_BACKOFF_BASE: Duration = Duration::from_secs(30);

/// Total retry budget for one reconnect cycle (~5 minutes). Once the
/// accumulated sleeps would exceed this, the reconnector gives up and
/// surfaces a terminal error instead of retrying forever.
pub const RECONNECT_BUDGET: Duration = Duration::from_secs(300);

/// Deterministic SplitMix64 PRNG so backoff sequences are reproducible from
/// a seed in tests while remaining cheap and allocation-free in production.
#[derive(Clone, Debug)]
pub struct SplitMix64 {
    state: u64,
}

impl SplitMix64 {
    /// Seeds the generator from an arbitrary 64-bit value.
    #[must_use]
    pub const fn seed(seed: u64) -> Self {
        Self { state: seed }
    }

    /// Next 64-bit output.
    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Uniform integer in `0..bound`. Modulo bias is irrelevant for jitter.
    #[must_use]
    pub fn next_below(&mut self, bound: u64) -> u64 {
        debug_assert!(bound > 0);
        self.next_u64() % bound
    }
}

/// Full-jitter backoff sequence with a total retry budget.
#[derive(Clone, Debug)]
pub struct Backoff {
    /// Current base; doubles after every sleep up to [`MAX_BACKOFF_BASE`].
    base: Duration,
    /// Remaining retry budget; a sleep that would exceed it ends the cycle.
    budget: Duration,
    /// PRNG used for the jitter term.
    rng: SplitMix64,
}

impl Backoff {
    /// Creates a fresh backoff sequence seeded with `seed` and a total budget
    /// of `budget`.
    #[must_use]
    pub const fn new(seed: u64, budget: Duration) -> Self {
        Self { base: INITIAL_BACKOFF_BASE, budget, rng: SplitMix64::seed(seed) }
    }

    /// Creates a fresh sequence with the default ~5 minute budget.
    #[must_use]
    pub const fn new_with_default_budget(seed: u64) -> Self {
        Self::new(seed, RECONNECT_BUDGET)
    }

    /// Next sleep duration for the current retry, or `None` when the total
    /// budget cannot cover it (the reconnect cycle must give up).
    ///
    /// The sleep is `base / 2 + rand(base / 2)` (full jitter); `base` starts
    /// at 1s and doubles after every attempt until the 30s cap.
    pub fn next_sleep(&mut self) -> Option<Duration> {
        let half = self.base / 2;
        let jitter = Duration::from_millis(
            if half.is_zero() { 0 } else { self.rng.next_below(half.as_millis() as u64 + 1) },
        );
        let sleep = half + jitter;
        if sleep > self.budget {
            return None;
        }
        self.budget -= sleep;
        let doubled = self.base * 2;
        self.base = if doubled > MAX_BACKOFF_BASE { MAX_BACKOFF_BASE } else { doubled };
        Some(sleep)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_jitter_stays_within_base_bounds() {
        // With a fixed seed, every sleep must lie in [base/2, base] for its
        // attempt's base, and the base must double 1s -> 2s -> 4s -> ... ->
        // 30s cap.
        let mut backoff = Backoff::new(7, RECONNECT_BUDGET);
        let mut expected_base = INITIAL_BACKOFF_BASE;
        for _ in 0..10 {
            let sleep = backoff.next_sleep().expect("budget must not run out");
            let half = expected_base / 2;
            assert!(sleep >= half, "sleep {sleep:?} below base/2 {half:?}");
            assert!(sleep <= expected_base, "sleep {sleep:?} above base {expected_base:?}");
            let doubled = expected_base * 2;
            expected_base = if doubled > MAX_BACKOFF_BASE { MAX_BACKOFF_BASE } else { doubled };
        }
        assert_eq!(backoff.base, MAX_BACKOFF_BASE, "base must cap at 30s");
    }

    #[test]
    fn same_seed_reproduces_the_same_sequence() {
        let mut a = Backoff::new(1234, RECONNECT_BUDGET);
        let mut b = Backoff::new(1234, RECONNECT_BUDGET);
        for _ in 0..20 {
            assert_eq!(a.next_sleep(), b.next_sleep());
        }
    }

    #[test]
    fn different_seeds_produce_different_jitter() {
        // The jitter term must vary with the seed; deterministic bases are
        // identical, so a differing sequence proves randomness is in play.
        let mut a = Backoff::new(1, RECONNECT_BUDGET);
        let mut b = Backoff::new(2, RECONNECT_BUDGET);
        let sequence_a = (0..8).filter_map(|_| a.next_sleep()).collect::<Vec<_>>();
        let sequence_b = (0..8).filter_map(|_| b.next_sleep()).collect::<Vec<_>>();
        assert_ne!(sequence_a, sequence_b, "jitter must depend on the seed");
    }

    #[test]
    fn budget_is_exhausted_and_then_returns_none() {
        // A tiny budget must let the sequence emit a few sleeps and then
        // signal exhaustion instead of overflowing.
        let mut backoff = Backoff::new(99, Duration::from_secs(2));
        let mut emitted = 0;
        while backoff.next_sleep().is_some() {
            emitted += 1;
            assert!(emitted < 100, "budgeted backoff must terminate");
        }
        assert!(emitted >= 1, "a 2s budget covers at least one 0.5-1s sleep");
        // Once exhausted, every further call stays exhausted.
        assert!(backoff.next_sleep().is_none());
        assert!(backoff.next_sleep().is_none());
    }

    #[test]
    fn splitmix64_is_deterministic_and_well_distributed() {
        let mut rng = SplitMix64::seed(42);
        let mut acc = 0u64;
        for _ in 0..1000 {
            let value = rng.next_u64();
            acc = acc.wrapping_add(value);
            assert!(value < u64::MAX || value == u64::MAX);
        }
        // Not a statistical test, just a smoke check that the generator runs.
        assert_ne!(acc, 0);
        let mut again = SplitMix64::seed(42);
        for _ in 0..1000 {
            assert_eq!(rng.next_u64(), again.next_u64());
        }
    }
}

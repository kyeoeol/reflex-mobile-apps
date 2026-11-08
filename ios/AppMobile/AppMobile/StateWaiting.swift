import Observation

/// Suspends until `condition` (a read of observable Core state) holds. For SwiftUI APIs that want to await
/// the work they started, such as `.refreshable`: the view sends an action and waits on state the
/// ViewModel already exposes (`isLoading`), so it still decides nothing. Android needs no equivalent:
/// `PullToRefreshBox(isRefreshing = state.isLoading)` is state-driven.
@MainActor
func waitUntil(_ condition: @escaping @MainActor () -> Bool) async {
    while !condition() {
        // Check and registration run in one main-actor turn, and Core mutates state on the main actor,
        // so no change can slip in between them.
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            withObservationTracking { _ = condition() } onChange: { continuation.resume() }
        }
    }
}

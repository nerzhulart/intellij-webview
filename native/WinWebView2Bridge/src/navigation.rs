// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

#[derive(Default)]
pub(crate) struct NavigationState {
    active_navigation_id: Option<u64>,
}

impl NavigationState {
    pub(crate) fn start(&mut self, navigation_id: Option<u64>) {
        self.active_navigation_id = navigation_id;
    }

    pub(crate) fn complete(&mut self, navigation_id: Option<u64>) -> bool {
        if navigation_id.is_none() || self.active_navigation_id != navigation_id {
            return false;
        }
        self.active_navigation_id = None;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::NavigationState;

    #[test]
    fn superseded_cancellation_does_not_complete_new_navigation() {
        let mut state = NavigationState::default();
        state.start(Some(1));
        state.start(Some(2));
        assert!(!state.complete(Some(1)));
        assert!(state.complete(Some(2)));
    }

    #[test]
    fn late_completion_after_current_navigation_finishes_is_ignored() {
        let mut state = NavigationState::default();
        state.start(Some(1));
        state.start(Some(2));
        assert!(state.complete(Some(2)));
        assert!(!state.complete(Some(1)));
        assert!(!state.complete(Some(2)));
    }

    #[test]
    fn redirect_keeps_the_same_navigation_id() {
        let mut state = NavigationState::default();
        state.start(Some(7));
        state.start(Some(7));
        assert!(state.complete(Some(7)));
    }

    #[test]
    fn stopped_navigation_can_complete_once() {
        let mut state = NavigationState::default();
        state.start(Some(3));
        assert!(state.complete(Some(3)));
        assert!(!state.complete(Some(3)));
        state.start(Some(4));
        assert!(state.complete(Some(4)));
    }

    #[test]
    fn unidentified_completion_cannot_override_current_navigation() {
        let mut state = NavigationState::default();
        state.start(Some(1));
        assert!(!state.complete(None));
        assert!(state.complete(Some(1)));
    }

    #[test]
    fn unidentified_start_invalidates_the_previous_navigation() {
        let mut state = NavigationState::default();
        state.start(Some(1));
        state.start(None);
        assert!(!state.complete(Some(1)));
        assert!(!state.complete(None));
        state.start(Some(2));
        assert!(state.complete(Some(2)));
    }

    #[test]
    fn completion_without_a_start_is_ignored() {
        let mut state = NavigationState::default();
        assert!(!state.complete(Some(1)));
        assert!(!state.complete(None));
    }
}

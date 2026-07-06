package com.vlessclient.ui.view;

/**
 * Implemented by view controllers that want a callback every time their view
 * is (re)shown. {@link MainViewController} caches loaded views, so a
 * controller's {@code initialize()} runs only once per app run — this hook is
 * how a view refreshes state that may have gone stale between visits.
 */
public interface ViewShownAware {

    /** Called on the FX thread each time the view becomes the visible one. */
    void onViewShown();
}

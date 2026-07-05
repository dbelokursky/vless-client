package com.vlessclient.ui.view;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * Central factory for the monochrome flat icons used in the sidebar and
 * elsewhere. Every icon is a single {@link SVGPath} drawn inside a fixed
 * 24×24 viewBox — the same format Material Design Icons and Heroicons use —
 * so we get crisp, CSS-colorable glyphs without a font dependency.
 *
 * <p>The path data is copied from Material Design Icons
 * (<a href="https://materialdesignicons.com">materialdesignicons.com</a>,
 * Apache-2.0). Each {@code create*} method returns a fresh {@link Node}
 * sized to {@code size}×{@code size} points so it can be dropped into any
 * layout container.</p>
 */
public final class Icons {

    // ---- Material Design Icons path data (24x24 viewBox) ----
    // mdi-speedometer: a gauge with a needle — reads as "dashboard" better
    // than the old four-tile grid glyph.
    private static final String DASHBOARD =
            "M12,16A3,3 0 0,1 9,13C9,11.88 9.61,10.9 10.5,10.39L20.21,4.77L14.68,"
                    + "14.35C14.18,15.33 13.17,16 12,16M12,3C13.81,3 15.5,3.5 16.97,4.32L14."
                    + "87,5.53C14,5.19 13,5 12,5A8,8 0 0,0 4,13C4,15.21 4.89,17.21 6.34,18.65"
                    + "H6.35C6.74,19.04 6.74,19.67 6.35,20.06C5.96,20.45 5.32,20.45 4.93,20."
                    + "07V20.07C3.12,18.26 2,15.76 2,13A10,10 0 0,1 12,3M22,13C22,15.76 20."
                    + "88,18.26 19.07,20.07V20.07C18.68,20.45 18.05,20.45 17.66,20.06C17.27,"
                    + "19.67 17.27,19.04 17.66,18.65V18.65C19.11,17.2 20,15.21 20,13C20,12 "
                    + "19.81,11 19.46,10.1L20.67,8C21.5,9.5 22,11.19 22,13Z";
    // mdi-server-network: a server stack over data-transfer arrows — carries
    // the "servers moving traffic" idea better than a bare stack.
    private static final String SERVER =
            "M13,19H14A1,1 0 0,1 15,20H22V22H15A1,1 0 0,1 14,23H10A1,1 0 0,1 9,22H2"
                    + "V20H9A1,1 0 0,1 10,19H11V17H4A1,1 0 0,1 3,16V12A1,1 0 0,1 4,11H20A1,1 "
                    + "0 0,1 21,12V16A1,1 0 0,1 20,17H13V19M4,3H20A1,1 0 0,1 21,4V8A1,1 0 0,1"
                    + " 20,9H4A1,1 0 0,1 3,8V4A1,1 0 0,1 4,3M9,7H10V5H9V7M9,15H10V13H9V15M5,5"
                    + "V7H7V5H5M5,13V15H7V13H5Z";
    private static final String LINK =
            "M10.59,13.41C11,13.8 11,14.44 10.59,14.83C10.2,15.22 9.56,15.22 9.17,14.83"
                    + "C7.22,12.88 7.22,9.71 9.17,7.76V7.76L12.71,4.22C14.66,2.27 17.83,2"
                    + ".27 19.78,4.22C21.73,6.17 21.73,9.34 19.78,11.29L18.29,12.78C18.3,"
                    + "11.96 18.17,11.14 17.89,10.36L18.36,9.88C19.54,8.71 19.54,6.81 18."
                    + "36,5.64C17.19,4.46 15.29,4.46 14.12,5.64L10.59,9.17C9.41,10.34 9.4"
                    + "1,12.24 10.59,13.41M13.41,9.17C13.8,8.78 14.44,8.78 14.83,9.17C16."
                    + "78,11.12 16.78,14.29 14.83,16.24V16.24L11.29,19.78C9.34,21.73 6.17"
                    + ",21.73 4.22,19.78C2.27,17.83 2.27,14.66 4.22,12.71L5.71,11.22C5.7,"
                    + "12.04 5.83,12.86 6.11,13.65L5.64,14.12C4.46,15.29 4.46,17.19 5.64,"
                    + "18.36C6.81,19.54 8.71,19.54 9.88,18.36L13.41,14.83C14.59,13.66 14."
                    + "59,11.76 13.41,10.59C13.03,10.2 13.03,9.56 13.41,9.17Z";
    private static final String ROUTING =
            "M14.59,14.59L17.17,12L14.59,9.41L16,8L20,12L16,16L14.59,14.59M9.41,9.41L"
                    + "6.83,12L9.41,14.58L8,16L4,12L8,8L9.41,9.41Z";
    private static final String LIST =
            "M9,5V9H21V5M9,19H21V15H9M9,14H21V10H9M4,9H8V5H4M4,19H8V15H4M4,14H8V10H4V14Z";
    private static final String SETTINGS =
            "M12,15.5A3.5,3.5 0 0,1 8.5,12A3.5,3.5 0 0,1 12,8.5A3.5,3.5 0 0,1 15.5,12A3"
                    + ".5,3.5 0 0,1 12,15.5M19.43,12.97C19.47,12.65 19.5,12.33 19.5,12C1"
                    + "9.5,11.67 19.47,11.34 19.43,11L21.54,9.37C21.73,9.22 21.78,8.95 2"
                    + "1.66,8.73L19.66,5.27C19.54,5.05 19.27,4.96 19.05,5.05L16.56,6.05"
                    + "C16.04,5.66 15.5,5.32 14.87,5.07L14.5,2.42C14.46,2.18 14.25,2 14"
                    + ",2H10C9.75,2 9.54,2.18 9.5,2.42L9.13,5.07C8.5,5.32 7.96,5.66 7.4"
                    + "4,6.05L4.95,5.05C4.73,4.96 4.46,5.05 4.34,5.27L2.34,8.73C2.21,8."
                    + "95 2.27,9.22 2.46,9.37L4.57,11C4.53,11.34 4.5,11.67 4.5,12C4.5,1"
                    + "2.33 4.53,12.65 4.57,12.97L2.46,14.63C2.27,14.78 2.21,15.05 2.34"
                    + ",15.27L4.34,18.73C4.46,18.95 4.73,19.03 4.95,18.95L7.44,17.94C7."
                    + "96,18.34 8.5,18.68 9.13,18.93L9.5,21.58C9.54,21.82 9.75,22 10,22"
                    + "H14C14.25,22 14.46,21.82 14.5,21.58L14.87,18.93C15.5,18.67 16.04"
                    + ",18.34 16.56,17.94L19.05,18.95C19.27,19.03 19.54,18.95 19.66,18."
                    + "73L21.66,15.27C21.78,15.05 21.73,14.78 21.54,14.63L19.43,12.97Z";

    private static final String DOWNLOAD =
            "M5,20H19V18H5M19,9H15V3H9V9H5L12,16L19,9Z";
    private static final String TRASH =
            "M19,4H15.5L14.5,3H9.5L8.5,4H5V6H19M6,19A2,2 0 0,0 8,21H16A2,2 0 0,0 18,"
                    + "19V7H6V19Z";

    private Icons() {
    }

    public static Node dashboard(double size)     { return make(DASHBOARD, size); }
    public static Node server(double size)        { return make(SERVER,    size); }
    public static Node link(double size)          { return make(LINK,      size); }
    public static Node routing(double size)       { return make(ROUTING,   size); }
    public static Node list(double size)          { return make(LIST,      size); }
    public static Node settings(double size)      { return make(SETTINGS,  size); }
    public static Node download(double size)      { return make(DOWNLOAD,  size); }
    public static Node clear(double size)         { return make(TRASH,     size); }

    /**
     * Builds a sized {@link StackPane} containing an {@link SVGPath} scaled
     * from its native 24×24 viewBox to {@code size}×{@code size} points.
     * The glyph inherits its fill from the {@code .nav-icon} CSS rule so
     * theme switches don't need per-icon updates.
     */
    private static Node make(String path, double size) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add("nav-icon-glyph");
        // Fall-through fill so the icon is visible before CSS loads (and in
        // tests that don't apply a stylesheet).
        svg.setFill(Color.web("#6b7078"));

        double scale = size / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);

        // Wrap in a Group so the scaled bounds are authoritative, and a
        // StackPane so the icon is centred within a square "cell".
        Group wrapper = new Group(svg);
        StackPane pane = new StackPane(wrapper);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.getStyleClass().add("nav-icon");
        return pane;
    }
}

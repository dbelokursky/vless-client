package com.vlessclient.ui;

import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the -c-* design-token system: verifies the tokens resolve at
 * runtime in both themes. A looked-up-color typo renders as a silent
 * fallback that JavaFX does not otherwise surface as a test failure, so
 * this asserts a token-driven property (the .card background) actually
 * resolves to the expected value in light and dark.
 */
public class ThemeTokenResolutionTest extends ApplicationTest {

    private Region card;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        card = new Region();
        card.getStyleClass().add("card");
        scene = new Scene(new VBox(card), 200, 120);
        stage.setScene(scene);
        stage.show();
    }

    @Test
    void cardBackgroundResolvesInBothThemes() {
        assertCardBg("/css/light.css", Color.web("#ffffff"));
        assertCardBg("/css/dark.css", Color.web("#26262a"));
    }

    private void assertCardBg(String stylesheet, Color expected) {
        interact(() -> {
            scene.getStylesheets().setAll(
                    getClass().getResource(stylesheet).toExternalForm());
            card.applyCss();
        });
        Color actual = (Color) card.getBackground().getFills().get(0).getFill();
        assertThat(actual)
                .withFailMessage("%s: -c-surface did not resolve (got %s, want %s)",
                        stylesheet, actual, expected)
                .isEqualTo(expected);
    }
}

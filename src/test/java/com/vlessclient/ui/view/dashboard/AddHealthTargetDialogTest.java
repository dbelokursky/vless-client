package com.vlessclient.ui.view.dashboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the target-normalization logic behind the add-service dialog.
 * (The dialog itself is thin FXML-free construction; the parsing contract is
 * what regressions would break.)
 */
class AddHealthTargetDialogTest {

    @Test
    void nullBlankAndWhitespaceAreRejected() {
        assertThat(AddHealthTargetDialog.normalizeTarget(null)).isNull();
        assertThat(AddHealthTargetDialog.normalizeTarget("")).isNull();
        assertThat(AddHealthTargetDialog.normalizeTarget("   ")).isNull();
    }

    @Test
    void absoluteHttpsUrlIsStoredVerbatimWithHostAsDisplayName() {
        var parsed = AddHealthTargetDialog.normalizeTarget("https://example.com/health?x=1");
        assertThat(parsed).isNotNull();
        assertThat(parsed.stored()).isEqualTo("https://example.com/health?x=1");
        assertThat(parsed.displayHost()).isEqualTo("example.com");
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        var parsed = AddHealthTargetDialog.normalizeTarget("  https://example.com  ");
        assertThat(parsed).isNotNull();
        assertThat(parsed.stored()).isEqualTo("https://example.com");
    }

    @Test
    void httpUrlWithPortKeepsHostAsDisplayName() {
        var parsed = AddHealthTargetDialog.normalizeTarget("http://example.com:8080/x");
        assertThat(parsed).isNotNull();
        assertThat(parsed.displayHost()).isEqualTo("example.com");
    }

    @Test
    void schemeWithoutHostIsRejected() {
        assertThat(AddHealthTargetDialog.normalizeTarget("http://")).isNull();
        assertThat(AddHealthTargetDialog.normalizeTarget("https://")).isNull();
    }

    @Test
    void malformedUrlIsRejected() {
        assertThat(AddHealthTargetDialog.normalizeTarget("https://exa mple.com")).isNull();
    }

    @Test
    void bareIpDelegatesToHostPortParsing() {
        var parsed = AddHealthTargetDialog.normalizeTarget("1.1.1.1");
        assertThat(parsed).isNotNull();
        assertThat(parsed.stored()).isEqualTo("1.1.1.1");
        assertThat(parsed.displayHost()).isEqualTo("1.1.1.1");
    }

    @Test
    void ipWithPortKeepsFullStringButHostOnlyDisplayName() {
        var parsed = AddHealthTargetDialog.normalizeTarget("1.1.1.1:53");
        assertThat(parsed).isNotNull();
        assertThat(parsed.stored()).isEqualTo("1.1.1.1:53");
        assertThat(parsed.displayHost()).isEqualTo("1.1.1.1");
    }

    @Test
    void garbageIsRejected() {
        assertThat(AddHealthTargetDialog.normalizeTarget("not a target at all")).isNull();
    }
}

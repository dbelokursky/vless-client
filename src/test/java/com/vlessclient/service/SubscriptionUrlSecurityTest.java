package com.vlessclient.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classifying subscription URLs by transport security — drives the
 * non-blocking "plaintext http" warning in the Add Subscription dialog.
 */
class SubscriptionUrlSecurityTest {

    @Test
    void httpIsFlaggedInsecure() {
        assertThat(SubscriptionService.isInsecureHttpUrl("http://example.com/sub")).isTrue();
        assertThat(SubscriptionService.isInsecureHttpUrl("HTTP://Example.com")).isTrue();
        assertThat(SubscriptionService.isInsecureHttpUrl("  http://example.com/sub  ")).isTrue();
    }

    @Test
    void httpsAndOthersAreNotFlagged() {
        assertThat(SubscriptionService.isInsecureHttpUrl("https://example.com/sub")).isFalse();
        assertThat(SubscriptionService.isInsecureHttpUrl("HTTPS://example.com")).isFalse();
        // A scheme that merely starts with "http" but isn't plain http.
        assertThat(SubscriptionService.isInsecureHttpUrl("httpsx://example.com")).isFalse();
    }

    @Test
    void blankOrUnparseableIsNotFlagged() {
        assertThat(SubscriptionService.isInsecureHttpUrl(null)).isFalse();
        assertThat(SubscriptionService.isInsecureHttpUrl("")).isFalse();
        assertThat(SubscriptionService.isInsecureHttpUrl("   ")).isFalse();
        assertThat(SubscriptionService.isInsecureHttpUrl("not a url")).isFalse();
        // A bare host with no scheme isn't classified as http.
        assertThat(SubscriptionService.isInsecureHttpUrl("example.com/sub")).isFalse();
    }
}

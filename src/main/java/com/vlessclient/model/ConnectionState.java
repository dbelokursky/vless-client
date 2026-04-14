package com.vlessclient.model;

/**
 * Represents the current state of the sing-box proxy connection.
 */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

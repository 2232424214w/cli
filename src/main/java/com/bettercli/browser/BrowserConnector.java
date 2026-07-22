package com.bettercli.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}

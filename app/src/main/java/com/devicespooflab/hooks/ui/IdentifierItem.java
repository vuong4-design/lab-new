package com.devicespooflab.hooks.ui;

public class IdentifierItem {
    public final String id;
    public final String displayName;
    public String currentValue;
    public boolean enabled;

    public IdentifierItem(String id, String displayName, String currentValue, boolean enabled) {
        this.id = id;
        this.displayName = displayName;
        this.currentValue = currentValue;
        this.enabled = enabled;
    }
}

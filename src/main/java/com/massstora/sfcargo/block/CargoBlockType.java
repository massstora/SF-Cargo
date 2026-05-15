package com.massstora.sfcargo.block;

import org.bukkit.Material;

public enum CargoBlockType {
    MANAGER("manager", "Cargo Manager", "e510bc85362a130a6ff9d91ff11d6fa46d7d1912a3431f751558ef3c4d9c2"),
    CONNECTOR("connector", "Cargo Connector", "07b7ef6fd7864865c31c1dc87bed24ab5973579f5c6638fecb8dedeb443ff0"),
    INPUT("input", "Cargo Input Node", "16d1c1a69a3de9fec962a77bf3b2e376dd25c873a3d8f14f1dd345dae4c4"),
    OUTPUT("output", "Cargo Output Node", "55b21fd480c1c43bf3b9f842c869bdc3bc5acc2599bf2eb6b8a1c95dce978f");

    private final String id;
    private final String displayName;
    private final String textureHash;

    CargoBlockType(String id, String displayName, String textureHash) {
        this.id = id;
        this.displayName = displayName;
        this.textureHash = textureHash;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Material material() {
        return Material.PLAYER_HEAD;
    }

    public String textureHash() {
        return textureHash;
    }

    public static CargoBlockType fromId(String id) {
        for (CargoBlockType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }
}

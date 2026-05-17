package com.massstora.sfcargo.net;

public record CargoNetworkSummary(int inputs, int outputs, int connectors, int managers, int usedNodes, int maxNodes, boolean multipleManagers, boolean activeManager, int[] inputsByChannel, int[] outputsByChannel) {
    public int remainingNodes() {
        return Math.max(0, maxNodes - usedNodes);
    }

    public int matchedChannels() {
        int matches = 0;
        for (int channel = 0; channel < 16; channel++) {
            if (inputsByChannel[channel] > 0 && outputsByChannel[channel] > 0) {
                matches++;
            }
        }
        return matches;
    }
}

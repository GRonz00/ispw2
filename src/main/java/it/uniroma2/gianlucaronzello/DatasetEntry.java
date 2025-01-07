package it.uniroma2.gianlucaronzello;

import it.uniroma2.gianlucaronzello.utils.Metric;

import java.util.EnumMap;
import java.util.Map;

public final class DatasetEntry {
    private final Map<Metric, String> metrics;
    private boolean buggy;

    public DatasetEntry() {
        this.metrics = new EnumMap<>(Metric.class);
        this.buggy = false;
    }

    public Map<Metric, String> metrics() {
        return metrics;
    }

    public boolean isBuggy() {
        return buggy;
    }

    public void setBuggy(boolean buggy) {
        this.buggy = buggy;
    }
}


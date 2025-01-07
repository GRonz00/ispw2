package it.uniroma2.gianlucaronzello.utils;

import java.nio.file.Path;
import java.nio.file.Paths;

public class DatasetPaths {
    private DatasetPaths() {
        throw new IllegalStateException("Utility class");
    }

    public static Path fromProject(String project) {
        return Paths.get("dataset", project);
    }
}

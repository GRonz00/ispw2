package it.uniroma2.gianlucaronzello;

import it.uniroma2.gianlucaronzello.utils.DatasetPaths;
import it.uniroma2.gianlucaronzello.utils.Metric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

public class CSVManagement {
    private static final Logger logger = Logger.getLogger("CSV management");
    private final String project;
    private final Map<Integer, List<CsvEntry>> oracleEntries;
    public CSVManagement(String project){
        this.project = project;
        this.oracleEntries = loadCsv(project,"oracle.csv");
    }
    public Map<Integer, List<CsvEntry>> loadCsv(String project, String name) {
        Map<Integer, List<CsvEntry>> entries = new HashMap<>();
        try {

            Path path = DatasetPaths.fromProject(project).resolve("datasets").resolve(name);
            List<String> lines = Files.readAllLines(path);
            int version = 1;
            List<CsvEntry> versionEntries = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                String[] values = lines.get(i).split(",");
                // Read the version from the current line
                int newVersion = Integer.parseInt(values[0]);
                // There is another version
                if (version != newVersion) {
                    // Save entries for current version
                    entries.put(version, versionEntries);
                    // Create new list for the entries of the new version
                    versionEntries = new ArrayList<>();
                    // Update version value
                    version = newVersion;
                }
                // Read current entry
                versionEntries.add(readEntry(lines.get(i)));
            }
            // Add the last version to the map
            entries.put(version, versionEntries);

        } catch (IOException e) {
            logger.info("Errore gestione CSV durante caricamento");
        }
        return entries;
    }
    private CsvEntry readEntry(String line) {
        String[] values = line.split(",");
        String version = values[0];
        String fileName = values[1];
        boolean buggy = Objects.equals(values[values.length - 1].toLowerCase(), "true");
        Map<Metric, String> fields = new EnumMap<>(Metric.class);
        // Skipping for two values (Version, File Name) and last value (Buggy)
        for (int j = 2; j < values.length - 1; j++)
            fields.put(Metric.values()[j - 2], values[j]);
        return new CsvEntry(version, fileName, fields, buggy);
    }
    public int getNumberReleases()  {
        Optional<Integer> total = oracleEntries.keySet().stream().max(Comparator.naturalOrder());
        if (total.isEmpty()) logger.info("errore calcolo numero release");
        return total.get();
    }
    public void generationArff(){
        for (int i = 2; i < getNumberReleases(); i++) {
            Map<Integer, List<CsvEntry>> entries = loadCsv(project, "%d.csv".formatted(i));
            //converterController.writeToArff(project, oracleEntries, entries, i);
            List<String> attributes = Arrays.stream(Metric.values()).map(m -> "@attribute %s numeric".formatted(m.name())).toList();
            
            List<String> testingData = oracleEntries.get(i).stream().map(this::entryFieldsToArff).toList();
            List<String> trainingData = new ArrayList<>();
            for (int j = 1; j < i; j++)
                trainingData.addAll(entries.get(j).stream().map(this::entryFieldsToArff).toList());
            String testingFile = "testing-%d.arff".formatted(i);
            String trainingFile = "training-%d.arff".formatted(i);
            try {
                writeFile(testingFile, project, attributes, testingData);
                writeFile(trainingFile, project, attributes, trainingData);
            } catch (IOException e) {
                logger.info("errore scrittura arff file");
            }
        }
    }
    private void writeFile(String filename, String project, List<String> attributes, List<String> entries) throws IOException {
        if (!Files.exists(Paths.get("dataset")) || !Files.exists(DatasetPaths.fromProject(project)))
            throw new IOException("dataset folder does not exists");
        Path arffFolder = DatasetPaths.fromProject(project).resolve("arff");
        Files.createDirectories(arffFolder);
        Path path = arffFolder.resolve(filename);
        String text = "@relation %s%n".formatted(project) +
                String.join("\n", attributes) + "\n" +
                "@attribute Buggy {true,false}\n" +
                "@data\n" +
                String.join("\n", entries);
        Files.write(path, text.getBytes());
    }
    private String entryFieldsToArff(CsvEntry entry) {
        List<String> orderedValues = new ArrayList<>();
        for (Metric field : Metric.values()) {
            String value = entry.fields().get(field);
            orderedValues.add(value);
        }
        return String.join(",", orderedValues) + ",%s".formatted(entry.buggy());
    }
    public record CsvEntry(String version, String name, Map<Metric, String> fields, boolean buggy) {
    }
}

package it.uniroma2.gianlucaronzello;

import it.uniroma2.gianlucaronzello.git.GitClass;
import it.uniroma2.gianlucaronzello.git.GitCommitEntry;
import it.uniroma2.gianlucaronzello.git.GitException;
import it.uniroma2.gianlucaronzello.git.JiraGitIntegration;
import it.uniroma2.gianlucaronzello.jira.model.JiraIssue;
import it.uniroma2.gianlucaronzello.jira.model.JiraVersion;
import it.uniroma2.gianlucaronzello.utils.DatasetPaths;
import it.uniroma2.gianlucaronzello.utils.Metric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ApplyMetrics {
    private static final Logger logger = Logger.getLogger("ApplyMetrics");
    // Sorted list of Jira releases
    private final List<Pair<JiraVersion, GitCommitEntry>> versions;
    private final Map<JiraIssue, GitCommitEntry> issues;

    // Provides functionality to explore the git repository to calculate metrics
    private final GitClass git;

    // Maps class name to its metrics. i-th element in the list is the entry for the i-th version
    private final Map<String, List<DatasetEntry>> entries;
    public ApplyMetrics(JiraGitIntegration integration, GitClass git) {
        this.git = git;
        this.entries = new HashMap<>();
        this.issues = integration.issues();
        this.versions = integration.versions();


        // Initialize `revisions`
        for (Pair<JiraVersion, GitCommitEntry> version : this.versions) {
            GitCommitEntry revision = version.second();
            // Initialize `entries` map
            for (String aClass : revision.classList()) {
                List<DatasetEntry> datasetEntries = new ArrayList<>();
                for (int i = 0; i < this.versions.size(); i++)
                    datasetEntries.add(i, new DatasetEntry());
                entries.put(aClass, datasetEntries);
            }
        }
    }

    private Void applyMetric(DatasetGenerator.MetricValue metric) {
        entries.get(metric.aClass())
                .get(metric.version())
                .metrics()
                .put(metric.metric(), String.valueOf(metric.value()));
        return null;
    }
    public void applyMetrics()  {
        applyLOCMetric(git, versions, this::applyMetric);
        applyDifferenceMetric(git, versions, this::applyMetric);
        applyCumulativeMetric(git, versions, this::applyMetric);
        applyListMetric(git, versions, issues, this::applyMetric);
    }
    public  void applyLOCMetric(GitClass git, List<Pair<JiraVersion, GitCommitEntry>> versions,
                                      Function<DatasetGenerator.MetricValue, Void> func) {
        try {
            // For every revision
            for (int i = 0; i < versions.size(); i++) {
                GitCommitEntry revision = versions.get(i).second();

                // For every class
                for (String aClass : revision.classList()) {
                    // Calculate the LOC of a file calculating the number of lines
                    String contents = git.getContentsOfClass(revision, aClass);
                    int loc = contents.split("\n").length;
                    DatasetGenerator.MetricValue value = new DatasetGenerator.MetricValue(aClass, i, Metric.LOC, loc);
                    func.apply(value);
                }
            }
        } catch (GitException e) {
            logger.info("git error");
        }
    }

    public  void applyDifferenceMetric(GitClass git, List<Pair<JiraVersion, GitCommitEntry>> versions,
                                             Function<DatasetGenerator.MetricValue, Void> func)  {
        try {
            // For every pair of consecutive releases
            GitCommitEntry previous = git.getFirstCommit();
            // For every consecutive pair of classes
            for (int i = 0; i < versions.size(); i++) {
                GitCommitEntry current = versions.get(i).second();
                // Get the differences between commits
                Map<String, GitClass.GitDiffEntry> diffs = git.getDifferences(previous, current);

                // For every class in the current release
                for (String aClass : current.classList()) {
                    // Get the diff of this class
                    GitClass.GitDiffEntry diff = diffs.get(aClass);
                    // Calculate the LOC touched and the churn
                    int locTouched = 0;
                    int churn = 0;
                    if (diff != null) {
                        locTouched = diff.touched();
                        churn = diff.churn();
                    }
                    DatasetGenerator.MetricValue locTouchedMetric = new DatasetGenerator.MetricValue(aClass, i, Metric.LOC_TOUCHED, locTouched);
                    DatasetGenerator.MetricValue churnMetric = new DatasetGenerator.MetricValue(aClass, i, Metric.CHURN, churn);
                    func.apply(locTouchedMetric);
                    func.apply(churnMetric);
                }

                // Set previous version as the current for the next iteration
                previous = current;
            }
        } catch (GitException e) {
            logger.info("git exception");
        }
    }

    public  void applyCumulativeMetric(GitClass git, List<Pair<JiraVersion, GitCommitEntry>> versions,
                                             Function<DatasetGenerator.MetricValue, Void> func)  {
        try {
            GitCommitEntry previous = git.getFirstCommit();

            // For every consecutive pair of versions
            for (int i = 0; i < versions.size(); i++) {
                GitCommitEntry current = versions.get(i).second();
                // For every class
                for (String aClass : current.classList()) {
                    // Get all the incremental differences of the class between the releases
                    List<GitClass.GitDiffEntry> diffs = git.getAllDifferencesOfClass(previous, current, aClass);
                    // Size of the `diffs` list (set as 1 if it's empty, so there's not dividing-by-zero error)
                    int size = diffs.size();
                    if (diffs.isEmpty()) size = 1;
                    // Calculating the max LOC added
                    int maxLocAdded = diffs.stream().map(GitClass.GitDiffEntry::added).max(Comparator.naturalOrder()).orElse(0);
                    DatasetGenerator.MetricValue maxLocAddedMetric = new DatasetGenerator.MetricValue(aClass, i, Metric.MAX_LOC_ADDED, maxLocAdded);
                    func.apply(maxLocAddedMetric);
                    // Calculating the max Churn
                    int maxChurn = diffs.stream().map(GitClass.GitDiffEntry::churn).max(Comparator.naturalOrder()).orElse(0);
                    DatasetGenerator.MetricValue maxChurnMetric = new DatasetGenerator.MetricValue(aClass, i, Metric.MAX_CHURN, maxChurn);
                    func.apply(maxChurnMetric);
                    // Calculating the sum of all the LOC added
                    int sumLocAdded = diffs.stream().map(GitClass.GitDiffEntry::added).reduce(Integer::sum).orElse(0);
                    DatasetGenerator.MetricValue averageLocAddedMetric = new DatasetGenerator.MetricValue(aClass, i, Metric.AVERAGE_LOC_ADDED, sumLocAdded / size);
                    func.apply(averageLocAddedMetric);
                    // Calculating the sum of all Churn
                    int sumChurn = diffs.stream().map(GitClass.GitDiffEntry::churn).reduce(Integer::sum).orElse(0);
                    DatasetGenerator.MetricValue averageChurnMetric = new DatasetGenerator.MetricValue(aClass, i, Metric.AVERAGE_CHURN, sumChurn / size);
                    func.apply(averageChurnMetric);
                }
                // Set previous version as the current for the next iteration
                previous = current;
            }
        } catch (GitException e) {
            logger.info("apply cumulative metric error");
        }
    }

    public  void applyListMetric(GitClass git, List<Pair<JiraVersion, GitCommitEntry>> versions,
                                       Map<JiraIssue, GitCommitEntry> issues,
                                       Function<DatasetGenerator.MetricValue, Void> func)  {
        try {
            GitCommitEntry previous = git.getFirstCommit();
            // For every consecutive pair of versions
            for (int i = 0; i < versions.size(); i++) {
                Pair<JiraVersion, GitCommitEntry> current = versions.get(i);
                // For every class
                for (String aClass : current.second().classList()) {
                    // Get every commit between two releases
                    List<GitCommitEntry> commits = git.getAllCommitsOfClass(previous, current.second(), aClass);
                    // NR
                    DatasetGenerator.MetricValue nrMetric = new DatasetGenerator.MetricValue(aClass, i, Metric.NR, commits.size());
                    func.apply(nrMetric);

                    // NAuth
                    int numberOfAuthors = commits.stream().map(GitCommitEntry::author).collect(Collectors.toSet()).size();
                    DatasetGenerator.MetricValue nAuthMetric = new DatasetGenerator.MetricValue(aClass, i, Metric.N_AUTH, numberOfAuthors);
                    func.apply(nAuthMetric);

                    // NFix
                    List<String> hashes = new ArrayList<>(commits.stream().map(GitCommitEntry::hash).toList());
                    hashes.addAll(List.of(previous.hash(), current.second().hash()));
                    long nFix = current.first().fixed().stream()
                            .filter(issue -> hashes.contains(issues.get(issue).hash())) // Fixed issues contained in this commit range
                            .count();
                    DatasetGenerator.MetricValue nFixMetric = new DatasetGenerator.MetricValue(aClass, i, Metric.N_FIX, nFix);
                    func.apply(nFixMetric);
                }
                // Set previous version as the current for the next iteration
                previous = current.second();
            }
        } catch (GitException e) {
            logger.info("apply list metric error");
        }
    }
    public void setBuggy(int lastVersion) throws GitException {
        List<Pair<JiraVersion, GitCommitEntry>> subList = versions.subList(0, lastVersion);
        calculateBuggy(git, subList, issues, buggy -> {
            buggy.first().stream().filter(entries::containsKey).forEach(aClass -> {
                for (int version : buggy.second())
                    entries.get(aClass).get(version).setBuggy(true);
            });
            return null;
        });
    }
    public void calculateBuggy(GitClass git, List<Pair<JiraVersion, GitCommitEntry>> versions,
                               Map<JiraIssue, GitCommitEntry> issues,
                               Function<Pair<List<String>, int[]>, Void> func) throws GitException {
        try {
            // For every version (after the first)
            for (int i = 1; i < versions.size(); i++) {
                Pair<JiraVersion, GitCommitEntry> current = versions.get(i);
                // For every issue fixed in this version
                for (JiraIssue fixedIssue : current.first().fixed()) {
                    GitCommitEntry fixedCommit = issues.get(fixedIssue);
                    List<String> modifiedClasses = git.getModifiedClassesOfCommit(fixedCommit);
                    int[] range = IntStream.range(fixedIssue.getIvIndex(), fixedIssue.getFvIndex()).toArray();
                    func.apply(new Pair<>(modifiedClasses, range));
                }
            }
        } catch (GitException e) {
            throw new GitException("Could not load differences", e);
        }
    }
    public void writeToFile(String project, int numberOfVersions) throws IOException {

        String text = writeToText(versions, entries, numberOfVersions);
        try {
            Path datasetFolder = DatasetPaths.fromProject(project).resolve("datasets");
            Files.createDirectories(datasetFolder);
            Path output = datasetFolder.resolve("%s.csv".formatted(String.valueOf(numberOfVersions)));
            Files.write(output, text.getBytes());
        } catch (IOException e) {
            throw new IOException("Could not write file", e);
        }
    }
    private String writeHeader() {
        List<String> metrics = Arrays.stream(Metric.values()).map(Metric::name).toList();
        return "Version,File_Name,%s,Buggy".formatted(String.join(",", metrics));
    }

    private String writeEntry(int version, String className, DatasetEntry entry) {
        List<String> metrics = new ArrayList<>();
        for (Metric value : Metric.values())
            metrics.add(entry.metrics().get(value));
        return "%d,%s,%s,%s".formatted(version + 1, className, String.join(",", metrics), entry.isBuggy());
    }
    public void writeOracle(String project, int numberOfVersions) throws IOException {


        String text = writeToText(versions, entries, numberOfVersions);
        writeToFile(project, text, "oracle");
    }
    public String writeToText(List<Pair<JiraVersion, GitCommitEntry>> revisions,
                              Map<String, List<DatasetEntry>> entries, int numberOfVersions) {
        String header = writeHeader();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < numberOfVersions; i++) {
            GitCommitEntry revision = revisions.get(i).second();
            for (String aClass : revision.classList()) {
                String value = writeEntry(i, aClass, entries.get(aClass).get(i));
                values.add(value);
            }
        }
        return "%s%n%s".formatted(header, String.join("\n", values));
    }
    public void writeToFile(String project, String text, String name) throws IOException {
        try {
            Path datasetFolder = DatasetPaths.fromProject(project).resolve("datasets");
            Files.createDirectories(datasetFolder);
            Path output = datasetFolder.resolve("%s.csv".formatted(name));
            Files.write(output, text.getBytes());
        } catch (IOException e) {
            throw new IOException("Could not write file", e);
        }
    }

}

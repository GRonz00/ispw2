package it.uniroma2.gianlucaronzello;

import it.uniroma2.gianlucaronzello.git.GitClass;
import it.uniroma2.gianlucaronzello.git.GitCommitEntry;
import it.uniroma2.gianlucaronzello.git.GitException;
import it.uniroma2.gianlucaronzello.git.JiraGitIntegration;
import it.uniroma2.gianlucaronzello.jira.Jira;
import it.uniroma2.gianlucaronzello.jira.model.JiraVersion;
import it.uniroma2.gianlucaronzello.utils.DatasetPaths;
import it.uniroma2.gianlucaronzello.utils.Metric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.*;
import java.util.logging.Logger;

public class Main {

    private static final Logger logger = Logger.getLogger("DatasetGenerator");
    public static void main(String[] args) {
        try {
            processProjects();
        } catch (GitException e) {
            logger.info("Git error: " + e);
        } catch (Exception e) {
            logger.info("Integration error: " + e);
        }
        analyzeProjects();
    }

    private static void processProjects() throws IOException,GitException {
        for (int i = 0; i < ProjectList.names().length; i++) {
            String project = ProjectList.names()[i];
            String other = getOppositeProject(i);

            List<String> coldStartsProjects = prepareColdStartsProjects(other);
            Jira jira = initializeJira(project, i);

            List<Double> coldStarts = calculateColdStarts(coldStartsProjects);
            double medianColdStart = getMedian(coldStarts);
            jira.applyProportionIncrement(medianColdStart);

            GitClass git = setupGitRepository(project, i);

            JiraGitIntegration integration = integrateJiraAndGit(jira, git);
            Dataset dataset = prepareDataset( git, integration);
            writeDatasetToFile(project, jira, dataset);
        }
    }

    private static String getOppositeProject(int i) {
        return ProjectList.names()[ProjectList.names().length - i - 1];
    }

    private static List<String> prepareColdStartsProjects(String other) {
        List<String> coldStartsProjects = new ArrayList<>(Arrays.asList(ProjectList.coldStartsProjects()));
        coldStartsProjects.add(other);
        return coldStartsProjects;
    }

    private static Jira initializeJira(String project, int index) {
        return new Jira(project, ProjectList.additionalParams()[index]);
    }

    private static List<Double> calculateColdStarts(List<String> coldStartsProjects) {
        List<Double> coldStarts = new ArrayList<>();
        for (String coldStartProject : coldStartsProjects) {
            Jira jiraColdStartProject = new Jira(coldStartProject, "");
            coldStarts.add(jiraColdStartProject.calculateProportionColdStart());
        }
        coldStarts.sort(Comparator.naturalOrder());
        return coldStarts;
    }

    private static double getMedian(List<Double> coldStarts) {
        return coldStarts.get(coldStarts.size() / 2);
    }

    private static GitClass setupGitRepository(String project, int index) throws GitException {
        Path projectPath = Paths.get(project);
        if (projectPath.toFile().exists() && projectPath.resolve(".git").toFile().exists()) {
            return new GitClass(project);
        } else {
            String gitUrl = "https://github.com/apache/%s".formatted(project);
            return new GitClass(project, gitUrl, ProjectList.branchProjects()[index]);
        }
    }

    private static JiraGitIntegration integrateJiraAndGit(Jira jira, GitClass git) throws GitException {
        JiraGitIntegration integration = new JiraGitIntegration(git.getCommits());
        integration.findRevisions(jira.getVersions());
        for (Pair<JiraVersion, GitCommitEntry> version : integration.versions()) {
            git.loadClassesOfRevision(version.second());
        }
        return integration;
    }

    private static Dataset prepareDataset(GitClass git, JiraGitIntegration integration) {
        Dataset dataset = new Dataset(integration, git);
        dataset.applyMetrics();
        return dataset;
    }

    private static void writeDatasetToFile(String project, Jira jira, Dataset dataset) throws IOException {
        for (int j = 2; j <= jira.getVersions().size(); j++) {
            try{
            dataset.setBuggy(j);}
            catch (GitException e){
                logger.info("Git execption in write dataset to file");
            }
            dataset.writeToFile(project, j);
        }
        dataset.writeOracle(project, jira.getVersions().size());
    }

    private static void analyzeProjects() {
        for (String project : ProjectList.names()) {
            List<Main.Result> results = analyzeProject(project);
            writeResultsToFile(project, results);
        }
    }

    private static List<Main.Result> analyzeProject(String project) {
        List<Main.Result> results = new ArrayList<>();
        CSVManagement cm = new CSVManagement(project);
        int nReleases = cm.getNumberReleases();
        cm.generationArff();

        for (int i = 2; i < nReleases; i++) {
            Analyses analysis = new Analyses(project, i);
            results.addAll(analysis.performAnalysis());
        }
        return results;
    }

    private static void writeResultsToFile(String project, List<Main.Result> results) {
        NumberFormat numberFormat = NumberFormat.getInstance(Locale.US);
        List<String> resultsString = results.stream().map(r -> r.toCsvString(project, numberFormat)).toList();
        String text = "Project,#TrainingRelease,Classifier,FeatureSelection,Sampling,Precision,Recall,Kappa,AUC%n%s"
                .formatted(String.join("\n", resultsString));

        Path path = DatasetPaths.fromProject(project).resolve("result.csv");
        try {
            Files.write(path, text.getBytes());
        } catch (IOException e) {
            logger.info("Failed to write results for project: " + project);
        }
    }




    public record Result(int releases, AnalysisVariables.Classifiers classifier,
                         AnalysisVariables.FeatureSelection featureSelection,
                         AnalysisVariables.Sampling sampling,
                         double precision, double recall, double auc, double kappa) {
        public String toCsvString(String project, NumberFormat numberFormat) {
            return "%s,%d,%s,%s,%s,%s,%s,%s,%s".formatted(
                    project,
                    releases,
                    classifier,
                    featureSelection,
                    sampling,
                    numberFormat.format(precision),
                    numberFormat.format(recall),
                    numberFormat.format(kappa),
                    numberFormat.format(auc)
            );
        }

    }


    public record MetricValue(String aClass, int version, Metric metric, Object value) {
    }



}
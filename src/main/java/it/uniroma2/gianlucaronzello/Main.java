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
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;
import java.util.logging.Logger;

public class Main {

    private static final Logger logger = Logger.getLogger("DatasetGenerator");
    public static void main(String[] args) {
        try {
            for (int i = 0; i < ProjectList.names().length; i++) {
                String project = ProjectList.names()[i];
                String other = ProjectList.names()[ProjectList.names().length - i - 1];
                List<String> coldStartsProjects = new ArrayList<>(Arrays.asList(ProjectList.coldStartsProjects()));
                coldStartsProjects.add(other);
                //Passo 1 prendere versioni e ticket da jira
                //Su jira trovi tutte le versioni (ne prendi la meta per ridurre snoring) e tutte le issue, anche il relativo stato della versione risptto alla issue
                Jira jira = new Jira(project, ProjectList.additionalParams()[i]);

                //Passo 2 applichi proportion
                //Calcolo cold strart per applicare proportion
                List<Double> coldStarts = new ArrayList<>();
                for (String coldStartProject : coldStartsProjects) {
                    Jira jiraColdStartProject = new Jira(coldStartProject, "");
                    double coldStart = jiraColdStartProject.calculateProportionColdStart();
                    coldStarts.add(coldStart);
                }
                coldStarts.sort(Comparator.naturalOrder());
                // Get Median
                jira.applyProportionIncrement(coldStarts.get(coldStarts.size() / 2));
                //Passo 3 creo il repository
                GitClass git;
                Path projectPath = Paths.get(project);
                if (projectPath.toFile().exists() && projectPath.resolve(".git").toFile().exists())
                    git = new GitClass(project);
                else
                    git = new GitClass(project, "https://github.com/apache/%s".formatted(project), ProjectList.branchProjects()[i]);

                
                JiraGitIntegration integration = new JiraGitIntegration(git.getCommits());
                integration.findRevisions(jira.getVersions());
                for (Pair<JiraVersion, GitCommitEntry> version : integration.versions()){
                    git.loadClassesOfRevision(version.second());}
                

                Dataset dataset = new Dataset(integration,git);
                dataset.applyMetrics();

                for (int j = 2; j <= jira.getVersions().size(); j++) {
                    dataset.setBuggy(j);
                    dataset.writeToFile(project, j);
                }
                dataset.writeOracle(project, jira.getVersions().size());
            }
        }
        catch (GitException e) {
            logger.info("Git error"+e);
        } catch (Exception e) {
            logger.info("Integration error"+e);
        }
        for (String project : ProjectList.names()) {
            List<Main.Result> results = new ArrayList<>();
            CSVManagement cm = new CSVManagement(project);
            int nReleases = cm.getNumberReleases();
            cm.generationArff();
            for(int i = 2;i<nReleases;i++){
                Analyses analysis = new Analyses(project, i);
                results.addAll(analysis.performAnalysis());

            }
            NumberFormat numberFormat = DecimalFormat.getInstance(Locale.US);

            List<String> resultsString = results.stream().map(r -> r.toCsvString(project, numberFormat)).toList();
            String text = "Project,#TrainingRelease,Classifier,FeatureSelection,Sampling,Precision,Recall,Kappa,AUC%n%s"
                    .formatted(String.join("\n", resultsString));
            Path path = DatasetPaths.fromProject(project).resolve("result.csv");
            try {
                Files.write(path, text.getBytes());
            } catch (IOException e) {
                System.out.print("www");
            }


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
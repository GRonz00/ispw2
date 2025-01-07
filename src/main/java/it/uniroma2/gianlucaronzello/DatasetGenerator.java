package it.uniroma2.gianlucaronzello;

import it.uniroma2.gianlucaronzello.git.GitClass;
import it.uniroma2.gianlucaronzello.git.GitCommitEntry;
import it.uniroma2.gianlucaronzello.git.GitException;
import it.uniroma2.gianlucaronzello.git.JiraGitIntegration;
import it.uniroma2.gianlucaronzello.jira.Jira;
import it.uniroma2.gianlucaronzello.jira.JiraException;
import it.uniroma2.gianlucaronzello.jira.Model.JiraVersion;
import it.uniroma2.gianlucaronzello.utils.Metric;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class DatasetGenerator {

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

                //Jira jiraProject = new Jira(project, ProjectList.additionalParams()[i]);
                JiraGitIntegration integration = new JiraGitIntegration(git.getCommits());
                integration.findRevisions(jira.getVersions());
                for (Pair<JiraVersion, GitCommitEntry> version : integration.versions()){
                    git.loadClassesOfRevision(version.second());}
                System.out.print("Creando il dataset");

                ApplyMetrics dataset = new ApplyMetrics(integration,git);
                dataset.applyMetrics();

                for (int j = 2; j <= jira.getVersions().size(); j++) {
                    dataset.setBuggy(j);
                    dataset.writeToFile(project, j);
                }
                dataset.writeOracle(project, jira.getVersions().size());
            }
        }
        catch (JiraException e) {
            System.out.println("Jira error"+e);

        } catch (GitException e) {
            System.out.println("Git error"+e);
        } catch (Exception e) {
            System.out.println("Integration error"+e);
        }
    }

    public record MetricValue(String aClass, int version, Metric metric, Object value) {
    }



}
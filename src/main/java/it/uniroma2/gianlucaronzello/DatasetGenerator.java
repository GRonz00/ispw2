package it.uniroma2.gianlucaronzello;

import it.uniroma2.gianlucaronzello.git.GitClass;
import it.uniroma2.gianlucaronzello.git.GitException;
import it.uniroma2.gianlucaronzello.jira.Jira;
import it.uniroma2.gianlucaronzello.jira.JiraException;

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

                coldStarts.sort(Comparator.naturalOrder());
                // Get Median
                jira.applyProportionIncrement(coldStarts.get(coldStarts.size() / 2));
                //Passo 3 creo il repository
                GitClass git = null;
                Path projectPath = Paths.get(project);
                if (projectPath.toFile().exists() && projectPath.resolve(".git").toFile().exists())
                    git = new GitClass(project);
                else
                    git = new GitClass(project, "https://github.com/apache/%s".formatted(project), ProjectList.branchProjects()[i]);

            }
        }
        catch (JiraException e) {
            throw new RuntimeException(e);
        } catch (GitException e) {
            throw new RuntimeException(e);
        }
    }
}
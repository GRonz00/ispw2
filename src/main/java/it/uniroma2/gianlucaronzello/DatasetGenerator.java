package it.uniroma2.gianlucaronzello;

import it.uniroma2.gianlucaronzello.jira.Jira;
import it.uniroma2.gianlucaronzello.jira.JiraException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarException;

public class DatasetGenerator {
    public static void main(String[] args) {
        try {
            for (int i = 0; i < ProjectList.names().length; i++) {
                String project = ProjectList.names()[i];
                String other = ProjectList.names()[ProjectList.names().length - i - 1];
                //List<String> coldStarts = new ArrayList<>(Arrays.asList(ProjectList.coldStarts()));
                //coldStarts.add(other);

                new Jira(project, ProjectList.additionalParams()[i]);
            }
        }
        catch (JiraException e) {
            throw new RuntimeException(e);
        }
    }
}
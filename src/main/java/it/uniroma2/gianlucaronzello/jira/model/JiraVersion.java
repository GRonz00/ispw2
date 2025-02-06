package it.uniroma2.gianlucaronzello.jira.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class JiraVersion {
    public static final String NAME_FIELD = "name";
    public static final String RELEASE_DATE_FIELD = "releaseDate";
    public static final String RELEASED_FIELD = "released";
    private final String name;
    private final LocalDate releaseDate;
    private final List<JiraIssue> injected;
    private final List<JiraIssue> opened;
    private final List<JiraIssue> fixed;

    public JiraVersion(String name, LocalDate releaseDate) {
        this.name = name;
        this.releaseDate = releaseDate;
        this.injected = new ArrayList<>();
        this.opened = new ArrayList<>();
        this.fixed = new ArrayList<>();
    }

    public String name() {
        return name;
    }

    public LocalDate releaseDate() {
        return releaseDate;
    }

    public List<JiraIssue> injected() {
        return injected;
    }

    public List<JiraIssue> opened() {
        return opened;
    }

    public List<JiraIssue> fixed() {
        return fixed;
    }
}

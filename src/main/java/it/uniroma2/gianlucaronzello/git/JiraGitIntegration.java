package it.uniroma2.gianlucaronzello.git;

import it.uniroma2.gianlucaronzello.Pair;
import it.uniroma2.gianlucaronzello.jira.Model.JiraIssue;
import it.uniroma2.gianlucaronzello.jira.Model.JiraVersion;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;
class NotFoundException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public NotFoundException(String version) {
        super("Commit for version %s was not found".formatted(version));
    }
}
public class JiraGitIntegration {
    private final List<GitCommitEntry> commits;
    private final List<Pair<JiraVersion, GitCommitEntry>> versions;
    private final Map<JiraIssue, GitCommitEntry> issues;

    public JiraGitIntegration(List<GitCommitEntry> commits) {
        this.commits = commits;
        this.versions = new ArrayList<>();
        this.issues = new HashMap<>();
    }

    public void findRevisions(List<JiraVersion> versions)  {
        try {
            for (JiraVersion version : versions) {
                GitCommitEntry revisionVersion = findRevisionOfVersion(version);
                this.versions.add(new Pair<>(version, revisionVersion));

                for (JiraIssue issue : version.fixed()) {
                    GitCommitEntry revisionIssue = findRevisionOfIssue(issue);
                    this.issues.put(issue, revisionIssue);
                }
            }
        }catch (NotFoundException e){
            System.out.println("Not found");
        }
    }

    private GitCommitEntry findRevisionOfVersion(JiraVersion version) throws NotFoundException {

        GitCommitEntry candidate;
        try {
            candidate = useSemanticFilter(version.name(), commits);
        } catch (NotFoundException e) {
            candidate = useDateFilter(version.releaseDate(), commits);
        }
        return candidate;
    }

    private GitCommitEntry findRevisionOfIssue(JiraIssue issue) throws NotFoundException {
        GitCommitEntry candidate;
        try {
            candidate = useSemanticKeyFilter(issue.getKey(), commits);
        } catch (Exception e) {
            candidate = useDateFilter(issue.getResolution(), commits);
        }
        return candidate;
    }
    private GitCommitEntry useSemanticFilter(String name, List<GitCommitEntry> commits) throws NotFoundException {
        Pattern avroPattern = Pattern.compile("Tag.* %s(| release)".formatted(name));
        List<GitCommitEntry> semanticFilter = commits.stream()
                .filter(c -> avroPattern.matcher(c.message()).find()
                        || c.message().contains("BookKeeper %s release".formatted(name)))
                .toList();
        if (semanticFilter.isEmpty()) throw new NotFoundException("Semantic filter failed for %s".formatted(name));
        return semanticFilter.get(semanticFilter.size() - 1);
    }

    // Returns the first commit after the version release date (end of day)
    private GitCommitEntry useDateFilter(LocalDate releaseDate, List<GitCommitEntry> commits) throws NotFoundException {
        return commits.stream()
                .filter(c -> !c.commitDate().isBefore(releaseDate.atTime(LocalTime.MAX)))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Date filter failed"));
    }

    // Returns the first commits starting with `key`
    private GitCommitEntry useSemanticKeyFilter(String key, List<GitCommitEntry> commits) throws NotFoundException {
        return commits.stream()
                .filter(c -> c.message().startsWith(key))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Semantic key filter failed for %s".formatted(key)));
    }

    public List<Pair<JiraVersion, GitCommitEntry>> versions() {
        return versions;
    }

    public Map<JiraIssue, GitCommitEntry> issues() {
        return issues;
    }
}

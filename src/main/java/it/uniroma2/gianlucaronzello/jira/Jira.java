package it.uniroma2.gianlucaronzello.jira;

import it.uniroma2.gianlucaronzello.Pair;
import it.uniroma2.gianlucaronzello.jira.model.JiraIssue;
import it.uniroma2.gianlucaronzello.jira.model.JiraVersion;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Logger;

public class Jira {
    private final List<JiraVersion> versions;
    private final List<JiraIssue> issues;
    private static final Logger logger = Logger.getLogger("Jira");

    public Jira(String project, String params)   {
        // Load versions from Jira API
        versions = loadVersions(project);
        JiraVersion first = versions.get(0);
        JiraVersion last = versions.get(versions.size() - 1);
        // Load issues from Jira API from the first and last version considered
        issues = loadIssues(project,params,  first.releaseDate(), last.releaseDate());
        // Initial version classification
        classifyIssues(versions, issues);

    }
    public List<JiraVersion> loadVersions(String project)   {
        List<JiraVersion> versionList = new ArrayList<>();
        String url = "https://issues.apache.org/jira/rest/api/2/project/%s/versions".formatted(project.toUpperCase());
        String json = getJsonFromUrl(url);
        JSONArray jsonVersions = new JSONArray(json);
        for (int i = 0; i < jsonVersions.length(); i++) {
            JSONObject jsonVersion = jsonVersions.getJSONObject(i);
            boolean released = jsonVersion.getBoolean(JiraVersion.RELEASED_FIELD);
            // Skipping versions that do not have a release date (only required field) or are set as not released
            if (!jsonVersion.has(JiraVersion.RELEASE_DATE_FIELD) || !released) continue;
            String name = jsonVersion.getString(JiraVersion.NAME_FIELD);
            String releaseDateString = jsonVersion.getString(JiraVersion.RELEASE_DATE_FIELD);
            LocalDate date = LocalDate.parse(releaseDateString);
            JiraVersion version = new JiraVersion(name, date);
            versionList.add(version);
        }

        versionList.sort(Comparator.comparing(JiraVersion::releaseDate));

        // Loading only 50% of releases (for better dataset accuracy ~ snoring problem)
        int numberOfVersions = (versionList.size() + 1) / 2;
        return versionList.subList(0, numberOfVersions);
    }

    public String getJsonFromUrl(String url)   {
        byte[] bytes = new byte[20];
        try (InputStream stream = URI.create(url).toURL().openStream()) {
             bytes = stream.readAllBytes();

        } catch (MalformedURLException e) {
             logger.info("Incorrect url: %s".formatted(url)+ e);
        } catch (IOException e) {
             logger.info("Could not load page: %s".formatted(url)+ e);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
    public List<JiraIssue> loadIssues(String project, String params,  LocalDate firstVersion, LocalDate lastVersion)   {
        List<JiraIssue> issueList = new ArrayList<>();
        int total;
        int totalDecrement = 0; // total skipped issues (missing required fields)
        int startAt = 0;
        do {
            String url = "https://issues.apache.org/jira/rest/api/2/search" +
                    "?jql=project=" + project + // selecting the project
                    " AND issueType=Bug AND(status=closed OR status=resolved)AND resolution=fixed" + // query to get all bug fix issues
                    // select issues resolved in [firstVersion, lastVersion]
                    " AND resolved>=%s AND resolved<=%s".formatted(firstVersion.toString(), lastVersion.toString()) +
                    " %s".formatted(params) +
                    "&fields=" + String.join(",", JiraIssue.getFields()) + // fields
                    "&startAt=" + startAt + // pagination offset
                    "&maxResults=1000"; // max results loaded
            // Correctly format URL
            String correctedUrl = url.replace(" ", "%20").replace(">=", "%3E%3D").replace("<=", "%3C%3D");
            // Load JSON
            String json = getJsonFromUrl(correctedUrl);
            JSONObject result = new JSONObject(json);
            total = result.getInt("total"); // total number of issues
            JSONArray jsonIssues = result.getJSONArray("issues");
            // Iterate through all the issues
            for (int i = 0; i < jsonIssues.length(); i++) {
                JSONObject jsonIssue = jsonIssues.getJSONObject(i);
                JSONObject fields = jsonIssue.getJSONObject("fields");
                // The issue does not have the required information, so it can be skipped
                if (!jsonIssue.has(JiraIssue.KEY_FIELD) || !fields.has(JiraIssue.RESOLUTION_DATE_FIELD) || !fields.has(JiraIssue.CREATED_FIELD)) {
                    totalDecrement += 1;
                    continue;
                }
                String key = jsonIssue.getString(JiraIssue.KEY_FIELD); // e.s. BOOKKEEPER-1
                String resolutionString = fields.getString(JiraIssue.RESOLUTION_DATE_FIELD);
                String createdString = fields.getString(JiraIssue.CREATED_FIELD);
                // Parse the dates
                LocalDate resolution = LocalDate.parse(resolutionString.substring(0, 10));
                LocalDate created = LocalDate.parse(createdString.substring(0, 10));

                // Get affected versions from Jira and sort them
                List<LocalDate> affectedVersions = new ArrayList<>();
                for (int j = 0; j < fields.getJSONArray(JiraIssue.VERSIONS_FIELD).length(); j++) {
                    JSONObject o = fields.getJSONArray(JiraIssue.VERSIONS_FIELD).getJSONObject(j);
                    if (!o.has(JiraVersion.RELEASE_DATE_FIELD)) continue;
                    String dateString = o.getString(JiraVersion.RELEASE_DATE_FIELD);
                    LocalDate date = LocalDate.parse(dateString);
                    affectedVersions.add(date);
                }
                affectedVersions.sort(Comparator.naturalOrder());

                JiraIssue issue = new JiraIssue(key, resolution, created, affectedVersions);
                issueList.add(issue);
            }
            startAt += result.getInt("maxResults");
        } while (total - totalDecrement != issueList.size());
        Collections.reverse(issueList); // sorted by key
        return issueList;
    }
    public void classifyIssues(List<JiraVersion> versions, List<JiraIssue> issues) {
        for (JiraIssue issue : issues) {
            LocalDate firstReleaseDate = versions.get(0).releaseDate();

            // Skipping the issues created and resolved before the first Jira release
            // IV, OV and FV should be the first release (it can cause problem when calculating proportion)
            if (issue.getCreated().isBefore(firstReleaseDate) && issue.getResolution().isBefore(firstReleaseDate))
                continue;

            // Find IV, OV and FV from Jira API (based on `created`, `resolution` and the first affectedVersion

            List<Pair<JiraVersion, Integer>> foundVersions = getVersions(issue, versions);
            Pair<JiraVersion, Integer> injected = foundVersions.get(0);
            Pair<JiraVersion, Integer> opening = foundVersions.get(1);
            Pair<JiraVersion, Integer> fix = foundVersions.get(2);


                // Case: affected version in Jira is after the fix version (based on resolutiondate) | i.e. BOOKKEEPER-374
                // Affected versions in Jira is incorrect, so the injected version is invalid
                if (!issue.getAffectedVersionsDates().isEmpty() && issue.getAffectedVersionsDates().get(0).isAfter(fix.first().releaseDate()) ) {
                        issue.getAffectedVersionsDates().clear();
                        injected = null;

                }

                // No injected version was found but the opening version is the first release
                // So the injected must be the first release as well
                if (injected == null && opening.first() == versions.get(0)) {injected = opening;
                }

                // Injected version is present (from affectedVersion, or derived as the first release)
                if (injected != null) {
                    issue.setIvIndex(injected.second());
                    injected.first().injected().add(issue);
                }
                issue.setOvIndex(opening.second());
                issue.setFvIndex(fix.second());

                opening.first().opened().add(issue);
                fix.first().fixed().add(issue);


        }
    }
    private List<Pair<JiraVersion, Integer>> getVersions(JiraIssue issue, List<JiraVersion> versions) {
        Pair<JiraVersion, Integer> injected = null;
        Pair<JiraVersion, Integer> opening = null;
        Pair<JiraVersion, Integer> fix = null;

        for (int i = 0; i < versions.size(); i++) {
            JiraVersion version = versions.get(i);
            // Injected version is the first affected version, if present
            if (injected == null && !issue.getAffectedVersionsDates().isEmpty() && issue.getAffectedVersionsDates().get(0).isEqual(version.releaseDate()))
                injected = new Pair<>(version, i);
            // Opening version is set as the first release after the jira ticket was created
            if (opening == null && version.releaseDate().isAfter(issue.getCreated())) opening = new Pair<>(version, i);
            // Fix version is set as the first release after the jira ticket was set as resolved
            if (fix == null && !version.releaseDate().isBefore(issue.getResolution()))
                fix = new Pair<>(version, i);
            // All variables are set, it is not necessary to search the whole list
            if (injected != null && opening != null && fix != null) break;
        }

        return Arrays.asList(injected, opening, fix);
    }


    public double calculateProportionColdStart() {
        // Get issues with valid IV (OV and FV should always be present)
        List<JiraIssue> filter = issues.stream().filter(i -> i.getIvIndex() != -1).toList();
        List<Double> proportions = filter.stream().map(JiraIssue::calculateProportion).toList();
        double sum = 0f;
        for (double value : proportions) sum += value;
        return sum / proportions.size();
    }

    public void applyProportionIncrement(double proportionColdStart) {
        // Sum from release 1 to R-1
        double lastSum = 0f;
        // Issues from release 1 to R-1
        int totalIssues = 0;
        // For each version R
        for (JiraVersion version : versions) {
            // Issues used to calculate proportion
            List<JiraIssue> valid = version.fixed().stream().filter(i -> i.getIvIndex() != -1).toList();
            double proportion = proportionColdStart; // use coldStart if there are less than 5 issues
            if (valid.size() >= 5) {
                List<Double> proportions = valid.stream().map(JiraIssue::calculateProportion).toList();
                double currentSum = proportions.stream().reduce(0.0, Double::sum);
                /*
                 * Incremental Proportion
                 *   lastSum: sum of proportions from release 1 to R-1
                 *   (lastProportion * totalIssues) + currentSum: sum of proportions from release 1 to R
                 *   ((lastProportion * totalIssues) + currentSum) / (totalIssues + valid.size): proportion mean
                 * */
                proportion = (lastSum + currentSum) / (totalIssues + valid.size());
                lastSum += currentSum;
            }
            // Get issue opened in this release without IV
            List<JiraIssue> invalid = new ArrayList<>(version.fixed());
            invalid.removeAll(valid);
            for (JiraIssue invalidIssue : invalid) {
                // Calculate IV = FV - (FV - OV) * P
                int iv = (int) (invalidIssue.getFvIndex() - invalidIssue.getFvMinusOv() * proportion);
                // Save IV
                invalidIssue.setIvIndex(iv);
                // Add current issue to the list of proportions
                lastSum += invalidIssue.calculateProportion();
                // Labeling: add issue to version corresponding to IV
                versions.get(iv).injected().add(invalidIssue);
            }
            totalIssues += version.opened().size();
        }
    }
    public List<JiraVersion> getVersions() {
        return versions;
    }

}

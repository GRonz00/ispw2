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
        versions = loadVersions(project);
        JiraVersion first = versions.get(0);
        JiraVersion last = versions.get(versions.size() - 1);
        issues = loadIssues(project,params,  first.releaseDate(), last.releaseDate());
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
            // Si escludono le versioni che non hanno la release date o non sono state rilasciate
            if (!jsonVersion.has(JiraVersion.RELEASE_DATE_FIELD) || !released) continue;
            String name = jsonVersion.getString(JiraVersion.NAME_FIELD);
            String releaseDateString = jsonVersion.getString(JiraVersion.RELEASE_DATE_FIELD);
            LocalDate date = LocalDate.parse(releaseDateString);
            JiraVersion version = new JiraVersion(name, date);
            versionList.add(version);
        }
        versionList.sort(Comparator.comparing(JiraVersion::releaseDate));
        // si utilizza solo la meta delle versioni
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
        int totalDecrement = 0;
        int startAt = 0;
        do {
            String url = "https://issues.apache.org/jira/rest/api/2/search" + "?jql=project=" + project +
                    " AND issueType=Bug AND(status=closed OR status=resolved)AND resolution=fixed" +
                    " AND resolved>=%s AND resolved<=%s".formatted(firstVersion.toString(), lastVersion.toString()) +
                    " %s".formatted(params) + "&fields=" + String.join(",", JiraIssue.getFields()) +
                    "&startAt=" + startAt +
                    "&maxResults=1000";
            String correctedUrl = url.replace(" ", "%20").replace(">=", "%3E%3D").replace("<=", "%3C%3D");
            String json = getJsonFromUrl(correctedUrl);
            JSONObject result = new JSONObject(json);
            total = result.getInt("total");
            JSONArray jsonIssues = result.getJSONArray("issues");
            for (int i = 0; i < jsonIssues.length(); i++) {
                JSONObject jsonIssue = jsonIssues.getJSONObject(i);
                JSONObject fields = jsonIssue.getJSONObject("fields");
                // issue da evitare
                if (!jsonIssue.has(JiraIssue.KEY_FIELD) || !fields.has(JiraIssue.RESOLUTION_DATE_FIELD) || !fields.has(JiraIssue.CREATED_FIELD)) {
                    totalDecrement += 1;
                    continue;
                }
                String key = jsonIssue.getString(JiraIssue.KEY_FIELD);
                String resolutionString = fields.getString(JiraIssue.RESOLUTION_DATE_FIELD);
                String createdString = fields.getString(JiraIssue.CREATED_FIELD);
                LocalDate resolution = LocalDate.parse(resolutionString.substring(0, 10));
                LocalDate created = LocalDate.parse(createdString.substring(0, 10));

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
        Collections.reverse(issueList);
        return issueList;
    }
    public void classifyIssues(List<JiraVersion> versions, List<JiraIssue> issues) {
        for (JiraIssue issue : issues) {
            LocalDate firstReleaseDate = versions.get(0).releaseDate();
            // si saltano gli issue risolti prima della prima release di Jira
            if (issue.getCreated().isBefore(firstReleaseDate) && issue.getResolution().isBefore(firstReleaseDate))
                continue;
            List<Pair<JiraVersion, Integer>> foundVersions = getVersions(issue, versions);
            Pair<JiraVersion, Integer> injected = foundVersions.get(0);
            Pair<JiraVersion, Integer> opening = foundVersions.get(1);
            Pair<JiraVersion, Integer> fix = foundVersions.get(2);
                //Se la FV è prima delle AV c'è un errore, quindi IV errata
                if (!issue.getAffectedVersionsDates().isEmpty() && issue.getAffectedVersionsDates().get(0).isAfter(fix.first().releaseDate()) ) {
                        issue.getAffectedVersionsDates().clear();
                        injected = null;
                }
                // Se OV dalla prima release, allora sarà IV dalla prima
                if (injected == null && opening.first() == versions.get(0)) {injected = opening;
                }
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
            // Se ci sono AV, l'IV è la prima
            if (injected == null && !issue.getAffectedVersionsDates().isEmpty() && issue.getAffectedVersionsDates().get(0).isEqual(version.releaseDate()))
                injected = new Pair<>(version, i);
            // OV è la prima dopo la crazione del ticket
            if (opening == null && version.releaseDate().isAfter(issue.getCreated())) opening = new Pair<>(version, i);
            // FV la prima dopo la risoluzione del ticket
            if (fix == null && !version.releaseDate().isBefore(issue.getResolution()))
                fix = new Pair<>(version, i);
            if (injected != null && opening != null && fix != null) break;
        }
        return Arrays.asList(injected, opening, fix);
    }


    public double calculateProportionColdStart() {
        // Si considerano gli issue che hanno già IV
        List<JiraIssue> filter = issues.stream().filter(i -> i.getIvIndex() != -1).toList();
        List<Double> proportions = filter.stream().map(JiraIssue::calculateProportion).toList();
        double sum = 0f;
        for (double value : proportions) sum += value;
        return sum / proportions.size();
    }

    public void applyProportionIncrement(double proportionColdStart) {
        double lastSum = 0f;
        int totalIssues = 0;
        for (JiraVersion version : versions) {
            List<JiraIssue> valid = version.fixed().stream().filter(i -> i.getIvIndex() != -1).toList();
            double proportion = proportionColdStart; // si usa cold start se si hanno meno di 5 ticket
            if (valid.size() >= 5) {
                List<Double> proportions = valid.stream().map(JiraIssue::calculateProportion).toList();
                double currentSum = proportions.stream().reduce(0.0, Double::sum);
                proportion = (lastSum + currentSum) / (totalIssues + valid.size());
                lastSum += currentSum;
            }
            // Prendi i fixed in questa versione che non hanno IV
            List<JiraIssue> invalid = new ArrayList<>(version.fixed());
            invalid.removeAll(valid);
            for (JiraIssue invalidIssue : invalid) {
                int iv = (int) (invalidIssue.getFvIndex() - invalidIssue.getFvMinusOv() * proportion);
                invalidIssue.setIvIndex(iv);
                lastSum += invalidIssue.calculateProportion();
                versions.get(iv).injected().add(invalidIssue);
            }
            totalIssues += version.opened().size();
        }
    }
    public List<JiraVersion> getVersions() {
        return versions;
    }

}

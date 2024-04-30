package it.uniroma2.gianlucaronzello;

public class ProjectList {
    private ProjectList() {
        throw new IllegalStateException("Utility class");
    }

    public static String[] names() {
        return new String[]{"bookkeeper", "avro"};
    }

    public static String[] additionalParams() {
        return new String[]{"", "AND component  in (Java, JAVA, java)"};
    }

    public static String[] coldStartsProjects() {
        return new String[]{"openjpa", "storm", "zookeeper", "syncope", "tajo"};
    }
    public static String[] branchProjects() {
        return  new String[]{"master","main"};
    }
}

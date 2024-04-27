package it.uniroma2.gianlucaronzello.jira;

import java.io.Serial;

public class JiraException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public JiraException(String reason, Throwable cause) {
        super(reason, cause);
    }
}

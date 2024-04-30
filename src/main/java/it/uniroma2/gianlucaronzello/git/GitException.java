package it.uniroma2.gianlucaronzello.git;

import java.io.Serial;

public class GitException extends  Exception{
    @Serial
    private static final long serialVersionUID = 1L;

    public GitException(String reason) {
        super("[REPO] %s".formatted(reason));
    }

    public GitException(String reason, Throwable cause) {
        super("[REPO] %s".formatted(reason), cause);
    }
}

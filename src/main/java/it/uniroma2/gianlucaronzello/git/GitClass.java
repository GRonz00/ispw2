package it.uniroma2.gianlucaronzello.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GitClass {

    private final File folder;
    private final Repository repository;
    private final List<GitCommitEntry> commits;
    
    // Remote Repository
    public GitClass(String project, String url, String branch) throws GitException {
        System.out.println("provo a fare il remote repository del progetto "+project);

        this.folder = new File(project);
        try {
            if (folder.exists()) throw new GitException("Local folder already exists");
            Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(folder)
                    .setBranch(branch)
                    .call()
                    .close();
            this.repository= loadLocal(folder);
        } catch (GitAPIException e) {
            throw new GitException("Could not clone repository", e);
        } catch (GitException e) {
            throw new RuntimeException(e);
        }
        this.commits = getCommits(repository);
    }

    // Local Repository
    public GitClass(String folderPath) throws GitException {
        System.out.println("provo il locale");
        this.folder = new File(folderPath);
        this.repository = loadLocal(folder);
        this.commits = getCommits(repository);
    }

    public Repository loadLocal(File folder) throws GitException {
        if (!folder.exists()) throw new GitException("Local folder does not exists");
        if (folder.isFile()) throw new GitException("The path points to a file");
        try {
            Path gitPath = folder.toPath().resolve(".git");
            return new RepositoryBuilder().setGitDir(gitPath.toFile()).build();
        } catch (IOException e) {
            throw new GitException("Could not load repository", e);
        }
    }

    public List<GitCommitEntry> getCommits(Repository repository) throws GitException {
        List<GitCommitEntry> entries = new ArrayList<>();
        try (Git git = new Git(repository)) {
            for (Ref branch : git.branchList().call()) {
                // all: used to get the commits from all branches (even the branches not synced with GitHub, but only in SVN ~ pre-2017)
                for (RevCommit commit : git.log().all().add(repository.resolve(branch.getName())).call())
                    entries.add(commitFromRevCommit(commit));
            }
        } catch (GitAPIException e) {
            throw new GitException("Unable to get the log", e);
        } catch (AmbiguousObjectException | IncorrectObjectTypeException e) {
            throw new GitException("Not a commit", e);
        } catch (IOException e) {
            throw new GitException("IO failure. Could not access refs", e);
        }

        // Ascending order of commit date
        Collections.reverse(entries);
        return entries;
    }

    private GitCommitEntry commitFromRevCommit(RevCommit commit) {
        String hash = commit.getName();
        String message = commit.getShortMessage();
        LocalDateTime date = LocalDateTime.ofInstant(commit.getCommitterIdent().getWhenAsInstant(), commit.getCommitterIdent().getZoneId());
        String author = commit.getAuthorIdent().getName();
        List<RevTree> parents = Arrays.stream(commit.getParents()).map(RevCommit::getTree).toList();
        RevTree tree = commit.getTree();
        return new GitCommitEntry(hash, message, date, author, tree, parents);
    }
}

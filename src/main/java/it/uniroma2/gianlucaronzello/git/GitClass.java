package it.uniroma2.gianlucaronzello.git;

import it.uniroma2.gianlucaronzello.Pair;
import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Logger;

public class GitClass {

    private static final Logger logger = Logger.getLogger("GitClass");
    private final File folder;
    private final Repository repository;
    private final List<GitCommitEntry> commits;
    public GitClass(String project, String url, String branch) throws GitException {

        this.folder = new File(project);
        try {
            if (folder.exists()) throw new GitException("Local folder already exists");
            Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(folder)
                    .setBranch(branch)
                    .call()
                    .close();

        } catch (GitAPIException e) {
            throw new GitException("Could not clone repository", e);
        } catch (GitException e) {
            logger.info("git classs error");
        }
        this.repository= loadLocal(folder);
        this.commits = getCommits(repository);

    }
    public GitClass(String folderPath) throws GitException {
        this.folder = new File(folderPath);
        this.repository = loadLocal(folder);
        this.commits = getCommits(repository);
    }
    public record GitDiffEntry(DiffEntry entry, int added, int deleted) {
        public int touched() {
            return this.added + this.deleted;
        }

        public int churn() {
            return this.added - this.deleted;
        }
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
        //Ordine crescente per data
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
    public List<GitCommitEntry> getCommits() {
        return commits;
    }
    public void loadClassesOfRevision(GitCommitEntry version) throws GitException {
        try (TreeWalk walk = new TreeWalk(repository)) {
            List<String> classes = new ArrayList<>();
            // Set base commit
            walk.addTree(version.tree());
            // Explore sub-folders
            walk.setRecursive(true);
            // Exclude non-java files
            walk.setFilter(PathSuffixFilter.create(".java"));
            // Iterate until there are files
            while (walk.next()) classes.add(walk.getPathString());
            version.setClassList(classes);
        } catch (IOException e) {
            throw new GitException("IO failure.", e);
        }
    }
    public String getContentsOfClass(GitCommitEntry commit, String fileName) throws GitException {
        try (TreeWalk walk = TreeWalk.forPath(repository, fileName, commit.tree()); ObjectReader reader = repository.newObjectReader()) {
            ObjectId blobId = walk.getObjectId(0);
            ObjectLoader loader = reader.open(blobId);
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new GitException("Corrupt git object", e);
        }
    }
    public GitCommitEntry getFirstCommit() throws GitException {
        try {
            RevWalk walk = new RevWalk(repository);
            ObjectId head = repository.resolve(Constants.HEAD);
            RevCommit root = walk.parseCommit(head);
            walk.sort(RevSort.REVERSE);
            walk.markStart(root);
            return commitFromRevCommit(walk.next());
        } catch (AmbiguousObjectException | IncorrectObjectTypeException e) {
            throw new GitException("Not a commit", e);
        } catch (IOException e) {
            throw new GitException("IO exception", e);
        }
    }
    public Map<String, GitDiffEntry> getDifferences(GitCommitEntry first, GitCommitEntry second) throws GitException {
        // Create a formatter disabling output
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            // Set current repository
            diffFormatter.setRepository(repository);
            // Exclude non-java files
            diffFormatter.setPathFilter(PathSuffixFilter.create(".java"));
            // Get diffs between `first` and `second` commits
            List<DiffEntry> diffs = diffFormatter.scan(first.tree(), second.tree());
            // List of computed differences
            Map<String, GitDiffEntry> differences = new HashMap<>();
            for (DiffEntry diff : diffs) {
                FileHeader header = diffFormatter.toFileHeader(diff);
                Pair<Integer, Integer> addedAndDeleted = calculateAddedAndDeleted(header.toEditList());
                String path = diff.getNewPath();
                GitDiffEntry entry = new GitDiffEntry(diff, addedAndDeleted.first(), addedAndDeleted.second());
                differences.put(path, entry);
            }
            return differences;
        } catch (CorruptObjectException e) {
            throw new GitException("Corrupt entry", e);
        } catch (MissingObjectException e) {
            throw new GitException("Missing entry", e);
        } catch (IOException e) {
            throw new GitException("Could not load commit", e);
        }
    }
    private Pair<Integer, Integer> calculateAddedAndDeleted(EditList list) {
        int added = 0;
        int deleted = 0;
        for (Edit edit : list) {
            int lengthDifference = edit.getLengthB() - edit.getLengthA();
            if (edit.getType() == Edit.Type.INSERT)
                added += lengthDifference;
            else if (edit.getType() == Edit.Type.DELETE)
                deleted -= lengthDifference;
            else if (edit.getType() == Edit.Type.REPLACE) {
                if (lengthDifference > 0) added += lengthDifference;
                else if (lengthDifference < 0) deleted += lengthDifference;
            }
        }
        return new Pair<>(added, deleted);
    }
    public List<GitDiffEntry> getAllDifferencesOfClass(GitCommitEntry first, GitCommitEntry second, String aClass) throws GitException {

        List<GitCommitEntry> commitsInBetween = getAllCommitsOfClass( first, second, aClass);
        return getAllDifferencesOfClass( commitsInBetween, aClass);
    }
    public List<GitCommitEntry> getAllCommitsOfClass( GitCommitEntry first, GitCommitEntry second, String path) throws GitException {
        try (Git git = new Git(repository)) {
            ObjectId firstId = ObjectId.fromString(first.hash());
            ObjectId secondId = ObjectId.fromString(second.hash());
            List<GitCommitEntry> entries = new ArrayList<>();
            git.log().addRange(firstId, secondId).addPath(path).call().iterator().forEachRemaining(c -> entries.add(commitFromRevCommit(c)));
            return entries;
        } catch (MissingObjectException e) {
            throw new GitException("Missing entry", e);
        } catch (IOException e) {
            throw new GitException("IO", e);
        } catch (NoHeadException e) {
            throw new GitException("Could not find HEAD", e);
        } catch (GitAPIException e) {
            throw new GitException("Could not call git API", e);
        }
    }
    private List<GitDiffEntry> getAllDifferencesOfClass(List<GitCommitEntry> commitsInBetween, String path) throws GitException {
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            diffFormatter.setPathFilter(PathFilter.create(path));
            List<GitDiffEntry> diffEntries = new ArrayList<>();
            if (commitsInBetween.isEmpty()) return diffEntries;
            GitCommitEntry previous = commitsInBetween.get(0);
            for (int i = 1; i < commitsInBetween.size(); i++) {
                GitCommitEntry current = commitsInBetween.get(i);
                List<DiffEntry> diffs = diffFormatter.scan(previous.tree(), current.tree());
                for (DiffEntry diff : diffs) {
                    FileHeader header = diffFormatter.toFileHeader(diff);
                    Pair<Integer, Integer> addedAndDeleted = calculateAddedAndDeleted(header.toEditList());
                    GitDiffEntry entry = new GitDiffEntry(diff, addedAndDeleted.first(), addedAndDeleted.second());
                    diffEntries.add(entry);
                }
            }
            return diffEntries;
        } catch (CorruptObjectException e) {
            throw new GitException("Corrupt entry", e);
        } catch (MissingObjectException e) {
            throw new GitException("Missing Entry", e);
        } catch (IOException e) {
            throw new GitException("Could not load commits", e);
        }
    }
    public List<String> getModifiedClassesOfCommit(GitCommitEntry commit) throws GitException {
        try (Git git = new Git(repository)) {
            ObjectReader reader = repository.newObjectReader();
            CanonicalTreeParser tree = new CanonicalTreeParser();
            tree.reset(reader, commit.tree());
            DiffCommand command = git.diff().setNewTree(tree);
            List<String> modified = new ArrayList<>();
            for (RevTree parentTree : commit.parents()) {
                CanonicalTreeParser parent = new CanonicalTreeParser();
                parent.reset(reader, parentTree);
                command.setOldTree(parent);
                List<String> classes = command.call().stream().map(DiffEntry::getNewPath).toList();
                modified.addAll(classes);
            }
            return modified;
        } catch (IOException e) {
            throw new GitException("Tree is invalid", e);
        } catch (GitAPIException e) {
            throw new GitException("Could not execute diff command", e);
        }
    }

}

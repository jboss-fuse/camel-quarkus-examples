package com.redhat.camel.quarkus.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.l2x6.mvn.assured.Mvn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.restassured.RestAssured;

public class UpdateVersionsTest {
    static final Logger log = LoggerFactory.getLogger(GitHubTokenCredentials.class);

    private static final String LATEST_LEGACY_BRANCH = "3.27.0-product";
    private static final Pattern BRANCH_PATTERN = Pattern.compile("[0-9]+\\.[0-9]+\\.x-product");
    static final String quarkusRegistryBaseUrl = "https://registry.quarkus.redhat.com";
    static final ComparableVersion minimalCEQVersion = new ComparableVersion("3.27.0");
    private static final Pattern ERROR_PATTERN = Pattern.compile("error", Pattern.CASE_INSENSITIVE);
    @Test
    void update() throws IOException, InvalidRemoteException, TransportException, GitAPIException, URISyntaxException {

        /* Input parameters */
        final boolean localTest = Boolean.parseBoolean(System.getenv("LOCAL_TEST"));
        String remoteUrl = System.getenv("CEQ_EXAMPLES_GIT_REPOSITORY");
        if (remoteUrl == null || remoteUrl.isEmpty()) {
            remoteUrl = "git@github.com:jboss-fuse/camel-quarkus-examples.git";
        }
        log.info("Using remote " + remoteUrl);
        final String ghRepository = System.getenv("GITHUB_REPOSITORY");
        final String ghToken = System.getenv("GITHUB_TOKEN");
        final String issueId = System.getenv("GITHUB_ISSUE_ID");
        final String workflowRunUrl = System.getenv("WORKFLOW_RUN_URL");
        if (!localTest) {
            Objects.requireNonNull(ghRepository, "GITHUB_REPOSITORY");
            Objects.requireNonNull(ghToken, "GITHUB_TOKEN");
            Objects.requireNonNull(issueId, "GITHUB_ISSUE_ID");
            Objects.requireNonNull(workflowRunUrl, "WORKFLOW_RUN_URL");
        } else {
            log.warn("No changes will be pushed because LOCAL_TEST=true");
        }

        try {

            final String cqPluginVersion = Utils.getLatestCqPluginVersion();

            final String remoteAlias = "midstream";
            final CredentialsProvider creds = new GitHubTokenCredentials(ghToken);

            /* From branch name such as 3.27.x-product to RHBQ Platform version, such as 3.27.0.redhat-00001 */
            final Map<String, String> branchToRhbqPlatformVersion = Utils.collectVersions(
                    quarkusRegistryBaseUrl,
                    minimalCEQVersion);

            final String uuid = UUID.randomUUID().toString();
            final Path checkoutDir = Path.of("target/checkout-" + uuid).toAbsolutePath().normalize();
            Files.createDirectories(checkoutDir);
            try (Git git = Git.init()
                    .setDirectory(checkoutDir.toFile())
                    .call()) {
                git.remoteAdd().setName(remoteAlias).setUri(new URIish(remoteUrl)).call();

                /* remoteBranchMap is from branch name such as 3.27.x-product to commit hash so that we can properly reset it */
                final Map<String, String> remoteBranchMap = fetchBranches(git, remoteUrl, remoteAlias, creds);

                for (Entry<String, String> en : branchToRhbqPlatformVersion.entrySet()) {
                    final String branch = en.getKey();
                    final String platformVersion = en.getValue();

                    final String remoteHead;
                    if (remoteBranchMap.containsKey(branch)) {
                        /* The branch exists in the remote already */
                        remoteHead = remoteBranchMap.get(branch);
                        log.info("Updating branch " + branch + " to RHBQ Platform " + platformVersion);
                    } else {
                        /*
                         * The branch does not exist yet in the remote so we create it based on the latest existing
                         * major.minor.x branch
                         */
                        final String latestExistingBranch = remoteBranchMap.keySet().iterator().next();
                        log.info("Creating branch " + branch + " from  " + latestExistingBranch
                                + " and updating it to RHBQ Platform " + platformVersion);
                        remoteHead = remoteBranchMap.get(latestExistingBranch);
                    }

                    /* Checkout or create the local branch */
                    git.branchCreate().setName(branch).setForce(true).setStartPoint(remoteHead).call();
                    git.checkout().setName(branch).call();
                    git.reset().setMode(ResetType.HARD).setRef(remoteHead).call();
                    final Ref ref = git.getRepository().exactRef("HEAD");
                    log.info("Reset the working copy to {}@{}", branch, ref.getObjectId().getName());

                    /*
                     * # Select or adjust the Platform version
                     * ./mvnw org.l2x6.cq:cq-prod-maven-plugin:${CQ_PLUGIN_VERSION}:sync-examples-from-upstream \
                     *   -Pprod \
                     *   -Dcq.quarkus.platform.version=${PLATFORM_VERSION}
                     */
                    final Mvn mvnv = Mvn.fromMvnw(checkoutDir).installIfNeeded();
                    {
                        mvnv
                                .args(
                                    "org.l2x6.cq:cq-prod-maven-plugin:" + cqPluginVersion + ":sync-examples-from-upstream",
                                    "-Dcq.quarkus.platform.version=" + platformVersion,
                                    "-ntp",
                                    "-B")
                                .cd(checkoutDir)
                                .then()
                                    .stdout()
                                        .hasLinesContaining("BUILD SUCCESS")
                                        .log()
                                    .stderr()
                                        .doesNotHaveLinesMatching(ERROR_PATTERN)
                                        .log()
                                .execute(Duration.ofMinutes(10))
                                .assertSuccess()
                                ;
                    }

                    /* Run tests if there are changes */
                    if (localTest || Utils.hasChanges(git)) {

                        /* Commit */
                        git.add().addFilepattern(".").call();
                        final String msg = "Upgrade to RHBQ Platform " + platformVersion;
                        log.info("git: {}", msg);
                        git.commit()
                            .setAuthor("Camel Quarkus Examples Autoupdater", "autoupdater@localhost")
                            .setMessage(msg)
                            .call();

                        /* Test */
                        final List<Path> exampleDirs;
                        try (java.util.stream.Stream<Path> dirs = Files.list(checkoutDir)) {
                            exampleDirs = dirs.filter(Files::isDirectory)
                            .filter(dir -> Files.exists(dir.resolve("pom.xml")))
                            .map(checkoutDir::resolve)
                            .collect(Collectors.toList());
                        }

                        for (Path exampleDir : exampleDirs) {
                            Path logFile = Path.of("target/" + exampleDir.getFileName() + ".log").toAbsolutePath().normalize();
                            mvnv
                                .args(
                                        "clean",
                                        "verify",
                                        "-ntp",
                                        "-B")
                                .cd(exampleDir)
                                .stderrToStdout()
                                .then()
                                    .stdout()
                                        .hasLinesContaining("BUILD SUCCESS")
                                        .log()
                                        .redirect(logFile)
                                .execute(Duration.ofMinutes(10))
                                .assertSuccess();

                        }

                        if (Utils.hasChanges(git)) {
                            /* Warn about uncommitted changes */
                            Repository repo = git.getRepository();

                            /* Get HEAD commit's tree */
                            ObjectId headId = repo.resolve("HEAD^{tree}");
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            try (DiffFormatter formatter = new DiffFormatter(baos)) {
                                formatter.setRepository(repo);
                                // Compare HEAD tree -> working directory (null = working tree)
                                // oldTree = HEAD, newTree = null means "current working tree state"
                                formatter.format(headId, null);
                            }
                            log.warn("Ignoring uncommitted changes after running mvn clean verify: {}", new String(baos.toByteArray(), StandardCharsets.UTF_8));

                            git.reset()
                                    .setMode(ResetType.HARD)
                                    .call();
                            /* Remove untracked files and directories */
                            git.clean()
                                    .setCleanDirectories(true)
                                    .setForce(true)
                                    .call();
                        }

                        if (!localTest) {
                            /* Push */
                            git.push()
                                .setRemote(remoteAlias)
                                .add(branch)
                                .setCredentialsProvider(creds)
                                .call();
                        }
                    }
                }
            }
            /* Close if needed */
            if (!localTest) {
                RestAssured.given()
                .accept("application/vnd.github+json")
                .header("Authorization", "Bearer " + ghToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .body("""
                        {
                            "state":"closed"
                        }
                        """)
                .patch("https://api.github.com/repos/" + ghRepository + "/issues/" + issueId)
                .then()
                .statusCode(200);
            }
        } catch (Exception e) {
            reportFailure(e, ghRepository, issueId, workflowRunUrl, ghToken, localTest);
        }
    }

    static void reportFailure(Exception e, String ghRepository, String issueId, String workflowRunUrl, String ghToken, boolean localTest) {

        if (localTest) {
            throw new RuntimeException(e);
        }

        final Writer stackTrace = new StringWriter();
        try (PrintWriter pw = new PrintWriter(stackTrace)) {
            e.printStackTrace(pw);
        }
        log.error("Failed", e);

        /* Add comment */
        String st = stackTrace.toString()
            .replace("\"", "\\\"")
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
        if (st.length() > 65000) {
            st = st.substring(0, 65000);
        }
        final String body = """
                {
                    "body" : "`update-versions` failed in %s :\\n\\n```\\n%s\\n```"
                }
                """.formatted(workflowRunUrl, st);
        //log.info("Creating new comment " + body);
        RestAssured.given()
                .accept("application/vnd.github+json")
                .header("Authorization", "Bearer " + ghToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .body(body)
                .post("https://api.github.com/repos/" + ghRepository + "/issues/" + issueId + "/comments")
                .then()
                .statusCode(201);

        /* Open the issue if needed */
        RestAssured.given()
                .accept("application/vnd.github+json")
                .header("Authorization", "Bearer " + ghToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .body("""
                        {
                            "state":"open"
                        }
                        """)
                .patch("https://api.github.com/repos/" + ghRepository + "/issues/" + issueId)
                .then()
                .statusCode(200);

        throw new RuntimeException(e);
    }

    static Map<String, String> fetchBranches(Git git, String remoteUrl, String remoteAlias, CredentialsProvider creds)
            throws InvalidRemoteException, TransportException, GitAPIException {
        final Set<String> remoteBranches = Git.lsRemoteRepository()
                .setCredentialsProvider(creds)
                .setHeads(true)
                .setTags(false)
                .setRemote(remoteUrl)
                .call().stream()
                .map(ref -> ref.getName().substring("refs/heads/".length()))
                .filter(b -> BRANCH_PATTERN.matcher(b).matches() || LATEST_LEGACY_BRANCH.equals(b))
                .collect(Collectors.toCollection(() -> new TreeSet<>(new BranchComparator().reversed())));
        log.info("Available branches in {}: {}", remoteAlias, remoteBranches);

        Map<String, String> result = new LinkedHashMap<>();
        for (String branch : remoteBranches) {
            log.info("Fetching {} from {}", branch, remoteAlias);
            final String remoteRef = "refs/heads/" + branch;
            final FetchResult fetchResult = git.fetch()
                    .setRemote(remoteAlias)
                    .setRefSpecs(remoteRef)
                    .setCredentialsProvider(creds)
                    .call();
            final String sha1 = fetchResult.getAdvertisedRef(remoteRef).getObjectId().getName();
            result.put(branch, sha1);
        }
        return Collections.unmodifiableMap(result);
    }

    static String toUrl(String url, String groupId, String artifactId, String version, String type) {
        final StringBuilder sb = new StringBuilder();
        sb.append(url);
        if (!url.endsWith("/")) {
            sb.append('/');
        }
        sb.append(groupId.replace('.', '/'))
                .append('/').append(artifactId)
                .append('/').append(version)
                .append('/').append(artifactId).append('-').append(version).append(".").append(type);
        return sb.toString();
    }

    static class GitHubTokenCredentials extends CredentialsProvider {

        private String ghToken;

        public GitHubTokenCredentials(String ghToken) {
            this.ghToken = ghToken;
        }

        @Override
        public boolean isInteractive() {
            return false;
        }

        @Override
        public boolean supports(CredentialItem... items) {
            for (CredentialItem i : items) {
                if (i instanceof CredentialItem.InformationalMessage) {
                    continue;
                }
                if (i instanceof CredentialItem.Username) {
                    continue;
                }
                if (i instanceof CredentialItem.Password) {
                    continue;
                }
                if (i instanceof CredentialItem.StringType) {
                    if (i.getPromptText().equals("Password: ")) {
                        continue;
                    }
                }
                if (i instanceof CredentialItem.YesNoType) {
                    if (i.getPromptText().startsWith("The authenticity of host 'github.com' can't be established.")) {
                        continue;
                    }
                }
                return false;
            }
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
            String username = "x-access-token";
            for (CredentialItem i : items) {
                if (i instanceof CredentialItem.InformationalMessage) {
                    continue;
                }
                if (i instanceof CredentialItem.Username) {
                    ((CredentialItem.Username) i).setValue(username);
                    continue;
                }
                if (i instanceof CredentialItem.Password && ghToken != null) {
                    ((CredentialItem.Password) i).setValue(ghToken.toCharArray());
                    continue;
                }
                if (i instanceof CredentialItem.StringType && ghToken != null) {
                    if (i.getPromptText().equals("Password: ")) {
                        ((CredentialItem.StringType) i).setValue(ghToken);
                        continue;
                    }
                }
                if (i instanceof CredentialItem.YesNoType && ghToken != null) {
                    if (i.getPromptText().startsWith("The authenticity of host 'github.com' can't be established.")) {
                        ((CredentialItem.YesNoType) i).setValue(true);
                        continue;
                    }
                }
                throw new UnsupportedCredentialItem(uri, i.getClass().getName()
                        + ":" + i.getPromptText());
            }
            return true;
        }

    }

    static class BranchComparator implements Comparator<String> {

        @Override
        public int compare(String branch1, String branch2) {
            String v1 = branch1.replace(".x", ".9999");
            String v2 = branch2.replace(".x", ".9999");
            return new ComparableVersion(v1).compareTo(new ComparableVersion(v2));
        }

    }
}

package com.redhat.camel.quarkus.test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.NoWorkTreeException;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;

public class Utils {
    private static final Pattern RED_HAT_VERSION_PATTERN = Pattern.compile(".*[\\.\\-]redhat-[0-9]{5}$");
    public static String getLatestCqPluginVersion() {
        XmlPath body = RestAssured.get("https://repo1.maven.org/maven2/org/l2x6/cq/cq-prod-maven-plugin/maven-metadata.xml")
            .then()
            .statusCode(200)
            .extract().body().xmlPath();

        String latest = body.get("metadata.versioning.latest");
        return latest;
    }

    public static Map<String, String> collectVersions(String quarkusRegistryBaseUrl, ComparableVersion minimalCQVersion) {
        Map<String, String> result = new LinkedHashMap<>();

        final JsonPath jsonPath = RestAssured.get(quarkusRegistryBaseUrl + "/client/platforms")
                .then()
                .statusCode(200)
                .extract().jsonPath();

        final List<Map<String, Object>> plfs = jsonPath.get("platforms");

        for (Map<String, Object> plf : plfs) {
            if ("com.redhat.quarkus.platform".equals(plf.get("platform-key"))) {
                final List<Map<String, Object>> streams = (List<Map<String, Object>>) plf.get("streams");
                for (Map<String, Object> stream : streams) {
                    final String streamId = (String) stream.get("id");
                    final String branch = streamId + ".x-product";
                    final List<Map<String, Object>> releases = (List<Map<String, Object>>) stream.get("releases");
                    for (Map<String, Object> release : releases) {

                        final List<String> boms = (List<String>) release.get("member-boms");
                        boms.stream()
                                .filter(gav -> gav.startsWith("com.redhat.quarkus.platform:quarkus-camel-bom:"))
                                .findFirst()
                                .ifPresent(ceqBomGav -> {
                                    final String[] ceqBomGavSegments = ceqBomGav.split(":");
                                    final String bomVersion = ceqBomGavSegments[4];
                                    final ComparableVersion comparableBomVersion = new ComparableVersion(bomVersion);
                                    if (minimalCQVersion.compareTo(comparableBomVersion) > 0) {
                                        UpdateVersionsTest.log.info("Skipping Platform version " + bomVersion + " because it is older than " + minimalCQVersion);
                                        return;
                                    }
                                    if (RED_HAT_VERSION_PATTERN.matcher(bomVersion).matches()) {
                                        UpdateVersionsTest.log.info("Found Platform BOM " + bomVersion + " in " + ceqBomGav);
                                        result.put(branch, bomVersion);
                                    } else {
                                        UpdateVersionsTest.log.info("Ignoring non-redhat Platform BOM " + ceqBomGav);
                                    }
                                });
                    }
                }
            }
        }

        return Collections.unmodifiableMap(result);
    }

    public static boolean hasChanges(Git git) throws NoWorkTreeException, GitAPIException {
        Status status = git.status().call();
        boolean hasChanges =
                !status.getModified().isEmpty() ||
                !status.getAdded().isEmpty() ||
                !status.getRemoved().isEmpty() ||
                !status.getMissing().isEmpty() ||
                !status.getChanged().isEmpty() ||
                !status.getConflicting().isEmpty() ||
                !status.getUntracked().isEmpty();
        return hasChanges;
    }
}

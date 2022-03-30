package org.apache.camel.example;

import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.test.scenarios.OpenShiftDeploymentStrategy;
import io.quarkus.test.scenarios.OpenShiftScenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;

@OpenShiftScenario(deployment = OpenShiftDeploymentStrategy.UsingOpenShiftExtensionAndDockerBuildStrategy)
public class FileToFtpOpenShiftIT extends FileToFtpCommonTest {
    private static LocalPortForward portForward;

    @BeforeAll
    public static void setup() {
        final OpenShiftClient ocpClient = new DefaultOpenShiftClient();
        System.setProperty("ftp.host", "localhost");
        System.setProperty("ftp.port", "2222");
        portForward = ocpClient.services().withName("ftp-server").portForward(2222, 2222);
    }

    @AfterAll
    public static void tearDown() throws IOException {
        if (portForward != null) {
            portForward.close();
        }
    }
}

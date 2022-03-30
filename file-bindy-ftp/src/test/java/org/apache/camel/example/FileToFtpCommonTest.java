package org.apache.camel.example;

import java.util.concurrent.TimeUnit;

import com.jcraft.jsch.*;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;

public class FileToFtpCommonTest {
    @Test
    public void testFileToFtp() throws JSchException {
        Config config = ConfigProvider.getConfig();

        JSch jsch = new JSch();
        jsch.setKnownHosts(System.getProperty("user.home") + "/.ssh/known_hosts");

        Session session = jsch.getSession("ftpuser", config.getValue("ftp.host", String.class));
        session.setPort(config.getValue("ftp.port", Integer.class));
        session.setPassword("ftppassword");
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(5000);
        Channel sftp = null;
        try {
            sftp = session.openChannel("sftp");
            sftp.connect(5000);

            ChannelSftp channelSftp = (ChannelSftp) sftp;

            await().atMost(10L, TimeUnit.SECONDS).pollDelay(500, TimeUnit.MILLISECONDS).until(() -> {
                try {
                    return channelSftp.ls("uploads/books/*.csv").size() >= 3;
                } catch (Exception e) {
                    return false;
                }
            });
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
        }
    }
}

package com.xing.beetle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;

public class RedisFailoverManager implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RedisFailoverManager.class);

    private String currentMaster;
    private final Client client;
    private final String masterFile;

    public RedisFailoverManager(Client client, String masterFile, RedisConfiguration initialConfig) {
        this.client = client;
        this.masterFile = masterFile;
        this.currentMaster = initialConfig.getHostname() + ":" + initialConfig.getPort();
    }
    @Override
    public void run() {
        try {
            String masterInFile = readCurrentMaster();
            if (!currentMaster.equals(masterInFile)) {
                log.info("Redis master configuration file changed: " + currentMaster + " -> " + masterInFile);
                currentMaster = masterInFile;

                final String[] masterHostPort = masterInFile.split(":");
                if (masterHostPort.length != 2) {
                    log.warn("Invalid file content '{}' in redis master file {}. Not performing master switch.", masterInFile, getMasterFile());
                } else {
                    client.getDeduplicationStore().reconnect(new RedisConfiguration(masterHostPort[0], Integer.valueOf(masterHostPort[1])));
                }
            }
        } catch (NumberFormatException nfe) {
            log.error("Malformed port number for new redis master.", nfe);
        } catch(Exception e) {
            log.error("Error when trying to read current Redis master. Retrying.", e);
        }
    }

    private String readCurrentMaster() throws IOException {

        try (FileInputStream stream = new FileInputStream(new File(masterFile))) {
            FileChannel fc = stream.getChannel();
            MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());

            return sanitizeMasterString(Charset.forName("UTF-8").decode(bb).toString());
        }
    }

    private String sanitizeMasterString(String masterString) {
        if (masterString != null) {
            return masterString.replace("\n", "").replace("\r", "");
        }

        return masterString;
    }

    public String getCurrentMaster() {
        return currentMaster;
    }

    public String getMasterFile() {
        return masterFile;
    }

    public boolean hasMasterFile() {
        return masterFile != null && !masterFile.isEmpty();
    }

}
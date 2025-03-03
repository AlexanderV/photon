package de.komoot.photon.elasticsearch;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.node.InternalSettingsPreparer;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeValidationException;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.transport.Netty4Plugin;
import org.elasticsearch.transport.client.PreBuiltTransportClient;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * Helper class to start/stop elasticsearch node and get elasticsearch clients
 *
 * @author felix
 */
@Slf4j
public class Server {
    private Node esNode;

    private Client esClient;

    private File esDirectory;

    private Integer shards = null;

    protected static class MyNode extends Node {
        public MyNode(Settings preparedSettings, Collection<Class<? extends Plugin>> classpathPlugins) {
            super(InternalSettingsPreparer.prepareEnvironment(preparedSettings, null), classpathPlugins);
        }
    }

    public Server(String mainDirectory) {
        try {
            if (SystemUtils.IS_OS_WINDOWS) {
                setupDirectories(new URL("file:///" + mainDirectory));
            } else {
                setupDirectories(new URL("file://" + mainDirectory));
            }
        } catch (Exception e) {
            throw new RuntimeException("Can't create directories: " + mainDirectory, e);
        }
    }

    public Server start(String clusterName, String transportAddresses) {
        Settings.Builder sBuilder = Settings.builder();
        sBuilder.put("path.home", this.esDirectory.toString());
        sBuilder.put("network.host", "127.0.0.1"); // http://stackoverflow.com/a/15509589/1245622
        sBuilder.put("cluster.name", clusterName);

        if (transportAddresses != null && !transportAddresses.isEmpty()) {
            TransportClient trClient = new PreBuiltTransportClient(sBuilder.build());
            List<String> addresses = Arrays.asList(transportAddresses.split(","));
            for (String tAddr : addresses) {
                int index = tAddr.indexOf(":");
                if (index >= 0) {
                    int port = Integer.parseInt(tAddr.substring(index + 1));
                    String addrStr = tAddr.substring(0, index);
                    trClient.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(addrStr, port)));
                } else {
                    trClient.addTransportAddress(new InetSocketTransportAddress(new InetSocketAddress(tAddr, 9300)));
                }
            }

            esClient = trClient;

            log.info("started elastic search client connected to " + addresses);

        } else {

            try {
                sBuilder.put("transport.type", "netty4").put("http.type", "netty4").put("http.enabled", "true");
                Settings settings = sBuilder.build();
                Collection<Class<? extends Plugin>> lList = new LinkedList<>();
                lList.add(Netty4Plugin.class);
                esNode = new MyNode(settings, lList);
                esNode.start();

                log.info("started elastic search node");

                esClient = esNode.client();

            } catch (NodeValidationException e) {
                throw new RuntimeException("Error while starting elasticsearch server", e);
            }

        }
        return this;
    }

    /**
     * stops the elasticsearch node
     */
    public void shutdown() {
        try {
            if (esNode != null)
                esNode.close();

            esClient.close();
        } catch (IOException e) {
            throw new RuntimeException("Error during elasticsearch server shutdown", e);
        }
    }

    /**
     * returns an elasticsearch client
     */
    public Client getClient() {
        return esClient;
    }

    private void setupDirectories(URL directoryName) throws IOException, URISyntaxException {
        final File mainDirectory = new File(directoryName.toURI());
        final File photonDirectory = new File(mainDirectory, "photon_data");
        this.esDirectory = new File(photonDirectory, "elasticsearch");
        final File pluginDirectory = new File(esDirectory, "plugins");
        final File scriptsDirectory = new File(esDirectory, "config/scripts");
        final File painlessDirectory = new File(esDirectory, "modules/lang-painless");

        for (File directory : new File[]{photonDirectory, esDirectory, pluginDirectory, scriptsDirectory,
                painlessDirectory}) {
            directory.mkdirs();
        }

        // copy script directory to elastic search directory
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();

        Files.copy(loader.getResourceAsStream("modules/lang-painless/antlr4-runtime.jar"),
                new File(painlessDirectory, "antlr4-runtime.jar").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(loader.getResourceAsStream("modules/lang-painless/asm-debug-all.jar"),
                new File(painlessDirectory, "asm-debug-all.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(loader.getResourceAsStream("modules/lang-painless/lang-painless.jar"),
                new File(painlessDirectory, "lang-painless.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(loader.getResourceAsStream("modules/lang-painless/plugin-descriptor.properties"),
                new File(painlessDirectory, "plugin-descriptor.properties").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(loader.getResourceAsStream("modules/lang-painless/plugin-security.policy"),
                new File(painlessDirectory, "plugin-security.policy").toPath(), StandardCopyOption.REPLACE_EXISTING);

    }

    public DatabaseProperties recreateIndex(String[] languages) throws IOException {
        deleteIndex();

        final Client client = this.getClient();

        loadIndexSettings().createIndex(client, PhotonIndex.NAME);

        new IndexMapping().addLanguages(languages).putMapping(client, PhotonIndex.NAME, PhotonIndex.TYPE);

        DatabaseProperties dbProperties = new DatabaseProperties().setLanguages(languages);
        dbProperties.saveToDatabase(client);

        return dbProperties;
    }

    public void updateIndexSettings(String synonymFile) throws IOException {
        // Load the settings from the database to make sure it is at the right
        // version. If the version is wrong, we should not be messing with the
        // index.
        DatabaseProperties dbProperties = new DatabaseProperties();
        dbProperties.loadFromDatabase(getClient());

        loadIndexSettings().setSynonymFile(synonymFile).updateIndex(getClient(), PhotonIndex.NAME);

        // Sanity check: legacy databases don't save the languages, so there is no way to update
        //               the mappings consistently.
        if (dbProperties.getLanguages() != null) {
            new IndexMapping()
                    .addLanguages(dbProperties.getLanguages())
                    .putMapping(getClient(), PhotonIndex.NAME, PhotonIndex.TYPE);
        }
    }

    private IndexSettings loadIndexSettings() {
        return new IndexSettings().setShards(shards);
    }

    public void deleteIndex() {
        try {
            this.getClient().admin().indices().prepareDelete(PhotonIndex.NAME).execute().actionGet();
        } catch (IndexNotFoundException e) {
            // ignore
        }
    }


    /**
     * Set the maximum number of shards for the embedded node
     * This typically only makes sense for testing
     *
     * @param shards the maximum number of shards
     * @return this Server instance for chaining
     */
    public Server setMaxShards(int shards) {
        this.shards = shards;
        return this;
    }
}

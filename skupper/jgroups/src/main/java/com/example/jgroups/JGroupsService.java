package com.example.jgroups;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jgroups.JChannel;
import org.jgroups.Message;
import org.jgroups.ObjectMessage;
import org.jgroups.Receiver;
import org.jgroups.View;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class JGroupsService implements Receiver {

    private static final Logger LOG = Logger.getLogger(JGroupsService.class);

    @ConfigProperty(name = "jgroups.cluster.name", defaultValue = "quarkus-cluster")
    String clusterName;

    @ConfigProperty(name = "jgroups.tcpping.initial_hosts", defaultValue = "localhost[7800]")
    String initialHosts;

    @ConfigProperty(name = "jgroups.bind_port", defaultValue = "7800")
    String bindPort;

    @ConfigProperty(name = "jgroups.bind_addr", defaultValue = "0.0.0.0")
    String bindAddr;

    private JChannel channel;
    private final List<String> receivedMessages = new CopyOnWriteArrayList<>();

    void onStart(@Observes StartupEvent ev) {
        try {
            LOG.infof("Starting JGroups cluster with name: %s", clusterName);
            LOG.infof("Initial hosts: %s", initialHosts);
            LOG.infof("Bind address: %s, Bind port: %s", bindAddr, bindPort);

            // Set system properties for JGroups configuration
            System.setProperty("jgroups.tcpping.initial_hosts", initialHosts);
            System.setProperty("jgroups.bind_port", bindPort);
            System.setProperty("jgroups.bind_addr", bindAddr);

            // Create channel with TCPPING configuration
//            channel = new JChannel("tcpping.xml");
            channel = new JChannel("tunnel.xml");
            channel.setReceiver(this);
            channel.connect(clusterName);

            LOG.infof("Successfully connected to cluster: %s", clusterName);
            LOG.infof("Local address: %s", channel.getAddress());
        } catch (Exception e) {
            LOG.error("Failed to start JGroups channel", e);
            throw new RuntimeException("Failed to start JGroups channel", e);
        }
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (channel != null && channel.isConnected()) {
            LOG.info("Disconnecting from JGroups cluster");
            channel.close();
        }
    }

    @Override
    public void receive(Message msg) {
        try {
            Object obj = msg.getObject();
            if (obj instanceof String) {
                String content = (String) obj;
                LOG.infof("Received message from %s: %s", msg.getSrc(), content);
                receivedMessages.add(String.format("[%s] %s", msg.getSrc(), content));
            }
        } catch (Exception e) {
            LOG.error("Error processing received message", e);
        }
    }

    @Override
    public void viewAccepted(View newView) {
        LOG.infof("View changed: %s", newView);
    }

    public void sendMessage(String message) throws Exception {
        if (channel == null || !channel.isConnected()) {
            throw new IllegalStateException("Channel is not connected");
        }
        Message msg = new ObjectMessage(null, message);
        channel.send(msg);
        LOG.infof("Sent message to cluster: %s", message);
    }

    public View getView() {
        return channel != null ? channel.getView() : null;
    }

    public String getLocalAddress() {
        return channel != null ? channel.getAddress().toString() : "Not connected";
    }

    public List<String> getReceivedMessages() {
        return new ArrayList<>(receivedMessages);
    }

    public void clearMessages() {
        receivedMessages.clear();
    }
}

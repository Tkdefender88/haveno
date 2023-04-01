package haveno.core.api;

import haveno.common.app.DevEnv;
import haveno.common.config.BaseCurrencyNetwork;
import haveno.common.config.Config;
import haveno.core.trade.HavenoUtils;
import haveno.core.xmr.model.EncryptedConnectionList;
import haveno.core.xmr.setup.DownloadListener;
import haveno.core.xmr.setup.WalletsSetup;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import monero.common.MoneroConnectionManager;
import monero.common.MoneroConnectionManagerListener;
import monero.common.MoneroError;
import monero.common.MoneroRpcConnection;
import monero.common.TaskLooper;
import monero.daemon.MoneroDaemonRpc;
import monero.daemon.model.MoneroDaemonInfo;
import monero.daemon.model.MoneroPeer;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public final class CoreMoneroConnectionsService {

    private static final int MIN_BROADCAST_CONNECTIONS = 0; // TODO: 0 for stagenet, 5+ for mainnet
    private static final long REFRESH_PERIOD_LOCAL_MS = 5000; // refresh period when connected to local node
    private static final long REFRESH_PERIOD_REMOTE_MS = 20000; // refresh period when connected to remote node
    private static final long MIN_ERROR_LOG_PERIOD_MS = 300000; // minimum period between logging errors fetching daemon info
    private static Long lastErrorTimestamp;

    // default Monero nodes
    private static final Map<BaseCurrencyNetwork, List<MoneroRpcConnection>> DEFAULT_CONNECTIONS;
    static {
        DEFAULT_CONNECTIONS = new HashMap<BaseCurrencyNetwork, List<MoneroRpcConnection>>();
        DEFAULT_CONNECTIONS.put(BaseCurrencyNetwork.XMR_LOCAL, Arrays.asList(
                new MoneroRpcConnection("http://127.0.0.1:28081").setPriority(1)
        ));
        DEFAULT_CONNECTIONS.put(BaseCurrencyNetwork.XMR_STAGENET, Arrays.asList(
                new MoneroRpcConnection("http://127.0.0.1:38081").setPriority(1), // localhost is first priority, use loopback address to match url generated by local node service
                new MoneroRpcConnection("http://45.63.8.26:38081").setPriority(1),
                new MoneroRpcConnection("http://stagenet.community.rino.io:38081").setPriority(2),
                new MoneroRpcConnection("http://stagenet.melo.tools:38081").setPriority(2),
                new MoneroRpcConnection("http://node.sethforprivacy.com:38089").setPriority(2),
                new MoneroRpcConnection("http://node2.sethforprivacy.com:38089").setPriority(2),
                new MoneroRpcConnection("http://ct36dsbe3oubpbebpxmiqz4uqk6zb6nhmkhoekileo4fts23rvuse2qd.onion:38081").setPriority(2)
        ));
        DEFAULT_CONNECTIONS.put(BaseCurrencyNetwork.XMR_MAINNET, Arrays.asList(
                new MoneroRpcConnection("http://127.0.0.1:18081").setPriority(1),
                new MoneroRpcConnection("http://node.community.rino.io:18081").setPriority(2),
                new MoneroRpcConnection("http://xmr-node.cakewallet.com:18081").setPriority(2),
                new MoneroRpcConnection("http://xmr-node-eu.cakewallet.com:18081").setPriority(2),
                new MoneroRpcConnection("http://xmr-node-usa-east.cakewallet.com:18081").setPriority(2),
                new MoneroRpcConnection("http://xmr-node-uk.cakewallet.com:18081").setPriority(2),
                new MoneroRpcConnection("http://node.sethforprivacy.com:18089").setPriority(2)
        ));
    }

    private final Object lock = new Object();
    private final Config config;
    private final CoreContext coreContext;
    private final CoreAccountService accountService;
    private final CoreMoneroNodeService nodeService;
    private final MoneroConnectionManager connectionManager;
    private final EncryptedConnectionList connectionList;
    private final ObjectProperty<List<MoneroPeer>> peers = new SimpleObjectProperty<>();
    private final IntegerProperty numPeers = new SimpleIntegerProperty(0);
    private final LongProperty chainHeight = new SimpleLongProperty(0);
    private final DownloadListener downloadListener = new DownloadListener();

    private MoneroDaemonRpc daemon;
    @Getter
    private MoneroDaemonInfo lastInfo;
    private boolean isInitialized = false;
    private TaskLooper updateDaemonLooper;;

    @Inject
    public CoreMoneroConnectionsService(Config config,
                                        CoreContext coreContext,
                                        WalletsSetup walletsSetup,
                                        CoreAccountService accountService,
                                        CoreMoneroNodeService nodeService,
                                        MoneroConnectionManager connectionManager,
                                        EncryptedConnectionList connectionList) {
        this.config = config;
        this.coreContext = coreContext;
        this.accountService = accountService;
        this.nodeService = nodeService;
        this.connectionManager = connectionManager;
        this.connectionList = connectionList;

        // initialize immediately if monerod configured
        if (!"".equals(config.xmrNode)) initialize();

        // initialize after account open and basic setup
        walletsSetup.addSetupTaskHandler(() -> { // TODO: use something better than legacy WalletSetup for notification to initialize

            // initialize from connections read from disk
            initialize();

            // listen for account to be opened or password changed
            accountService.addListener(new AccountServiceListener() {

                @Override
                public void onAccountOpened() {
                    try {
                        log.info(getClass() + ".onAccountOpened() called");
                        initialize();
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void onPasswordChanged(String oldPassword, String newPassword) {
                    log.info(getClass() + ".onPasswordChanged({}, {}) called", oldPassword, newPassword);
                    connectionList.changePassword(oldPassword, newPassword);
                }
            });
        });
    }

    // ------------------------ CONNECTION MANAGEMENT -------------------------

    public MoneroDaemonRpc getDaemon() {
        accountService.checkAccountOpen();
        return this.daemon;
    }

    public void addListener(MoneroConnectionManagerListener listener) {
        synchronized (lock) {
            connectionManager.addListener(listener);
        }
    }

    public void addConnection(MoneroRpcConnection connection) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionList.addConnection(connection);
            connectionManager.addConnection(connection);
        }
    }

    public void removeConnection(String uri) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionList.removeConnection(uri);
            connectionManager.removeConnection(uri);
        }
    }

    public MoneroRpcConnection getConnection() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            return connectionManager.getConnection();
        }
    }

    public List<MoneroRpcConnection> getConnections() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            return connectionManager.getConnections();
        }
    }

    public void setConnection(String connectionUri) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.setConnection(connectionUri); // listener will update connection list
        }
    }

    public void setConnection(MoneroRpcConnection connection) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.setConnection(connection); // listener will update connection list
        }
    }

    public MoneroRpcConnection checkConnection() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.checkConnection();
            return getConnection();
        }
    }

    public List<MoneroRpcConnection> checkConnections() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.checkConnections();
            return getConnections();
        }
    }

    public void startCheckingConnection(Long refreshPeriod) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.startCheckingConnection(refreshPeriod == null ? getDefaultRefreshPeriodMs() : refreshPeriod);
            connectionList.setRefreshPeriod(refreshPeriod);
        }
    }

    public void stopCheckingConnection() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.stopCheckingConnection();
            connectionList.setRefreshPeriod(-1L);
        }
    }

    public MoneroRpcConnection getBestAvailableConnection() {
        synchronized (lock) {
            accountService.checkAccountOpen();
            return connectionManager.getBestAvailableConnection();
        }
    }

    public void setAutoSwitch(boolean autoSwitch) {
        synchronized (lock) {
            accountService.checkAccountOpen();
            connectionManager.setAutoSwitch(autoSwitch);
            connectionList.setAutoSwitch(autoSwitch);
        }
    }

    public boolean isConnectionLocal() {
        return getConnection() != null && HavenoUtils.isLocalHost(getConnection().getUri());
    }

    public long getDefaultRefreshPeriodMs() {
        if (daemon == null) return REFRESH_PERIOD_LOCAL_MS;
        else {
            if (isConnectionLocal()) {
                if (lastInfo != null && (lastInfo.isBusySyncing() || (lastInfo.getHeightWithoutBootstrap() != null && lastInfo.getHeightWithoutBootstrap() > 0 && lastInfo.getHeightWithoutBootstrap() < lastInfo.getHeight()))) return REFRESH_PERIOD_REMOTE_MS; // refresh slower if syncing or bootstrapped
                else return REFRESH_PERIOD_LOCAL_MS; // TODO: announce faster refresh after done syncing
            } else {
                return REFRESH_PERIOD_REMOTE_MS;
            }
        }
    }

    public void verifyConnection() {
        if (daemon == null) throw new RuntimeException("No connection to Monero node");
        if (!isSyncedWithinTolerance()) throw new RuntimeException("Monero node is not synced");
    }

    public boolean isSyncedWithinTolerance() {
        if (daemon == null) return false;
        Long targetHeight = lastInfo.getTargetHeight(); // the last time the node thought it was behind the network and was in active sync mode to catch up
        if (targetHeight == 0) return true; // monero-daemon-rpc sync_info's target_height returns 0 when node is fully synced
        long currentHeight = chainHeight.get();
        if (targetHeight - currentHeight <= 3) { // synced if not more than 3 blocks behind target height
            return true;
        }
        log.warn("Our chain height: {} is out of sync with peer nodes chain height: {}", chainHeight.get(), targetHeight);
        return false;
    }

    // ----------------------------- APP METHODS ------------------------------

    public ReadOnlyIntegerProperty numPeersProperty() {
        return numPeers;
    }

    public ReadOnlyObjectProperty<List<MoneroPeer>> peerConnectionsProperty() {
        return peers;
    }

    public boolean hasSufficientPeersForBroadcast() {
        return numPeers.get() >= getMinBroadcastConnections();
    }

    public LongProperty chainHeightProperty() {
        return chainHeight;
    }

    public ReadOnlyDoubleProperty downloadPercentageProperty() {
        return downloadListener.percentageProperty();
    }

    public int getMinBroadcastConnections() {
        return MIN_BROADCAST_CONNECTIONS;
    }

    public boolean isDownloadComplete() {
        return downloadPercentageProperty().get() == 1d;
    }

    /**
     * Signals that both the daemon and wallet have synced.
     *
     * TODO: separate daemon and wallet download/done listeners
     */
    public void doneDownload() {
        downloadListener.doneDownload();
    }

    // ------------------------------- HELPERS --------------------------------

    private void initialize() {
        synchronized (lock) {

            // reset connection manager's connections and listeners
            connectionManager.reset();

            // load connections
            connectionList.getConnections().forEach(connectionManager::addConnection);
            log.info("Read " + connectionList.getConnections().size() + " connections from disk");

            // add default connections
            for (MoneroRpcConnection connection : DEFAULT_CONNECTIONS.get(Config.baseCurrencyNetwork())) {
                if (connectionList.hasConnection(connection.getUri())) continue;
                addConnection(connection);
            }

            // restore last used connection if present
            var currentConnectionUri = connectionList.getCurrentConnectionUri();
            if (currentConnectionUri.isPresent()) connectionManager.setConnection(currentConnectionUri.get());

            // set monero connection from startup arguments
            if (!isInitialized && !"".equals(config.xmrNode)) {
                connectionManager.setConnection(new MoneroRpcConnection(config.xmrNode, config.xmrNodeUsername, config.xmrNodePassword).setPriority(1));
            }

            // restore configuration
            connectionManager.setAutoSwitch(connectionList.getAutoSwitch());
            long refreshPeriod = connectionList.getRefreshPeriod();
            if (refreshPeriod > 0) connectionManager.startCheckingConnection(refreshPeriod);
            else if (refreshPeriod == 0) connectionManager.startCheckingConnection();
            else checkConnection();

            // run once
            if (!isInitialized) {

                // register connection change listener
                connectionManager.addListener(this::onConnectionChanged);

                // register local node listener
                nodeService.addListener(new MoneroNodeServiceListener() {
                    @Override
                    public void onNodeStarted(MoneroDaemonRpc daemon) {
                        log.info(getClass() + ".onNodeStarted() called");
                        daemon.getRpcConnection().checkConnection(connectionManager.getTimeout());
                        setConnection(daemon.getRpcConnection());
                    }

                    @Override
                    public void onNodeStopped() {
                        log.info(getClass() + ".onNodeStopped() called");
                        checkConnection();
                    }
                });

                isInitialized = true;
            }

            // if offline and last connection is local, start local node if offline
            currentConnectionUri.ifPresent(uri -> {
                try {
                    if (!connectionManager.isConnected() && HavenoUtils.isLocalHost(uri) && !nodeService.isOnline()) {
                        nodeService.startMoneroNode();
                    }
                } catch (Exception e) {
                    log.warn("Unable to start local monero node: " + e.getMessage());
                }
            });

            // prefer to connect to local node unless prevented by configuration
            if (("".equals(config.xmrNode) || HavenoUtils.isLocalHost(config.xmrNode)) &&
                (!connectionManager.isConnected() || connectionManager.getAutoSwitch()) &&
                nodeService.isConnected()) {
                MoneroRpcConnection connection = connectionManager.getConnectionByUri(nodeService.getDaemon().getRpcConnection().getUri());
                if (connection != null) {
                    connection.checkConnection(connectionManager.getTimeout());
                    setConnection(connection);
                }
            }

            // if using legacy desktop app, connect to best available connection
            if (!coreContext.isApiUser() && "".equals(config.xmrNode)) {
                connectionManager.setAutoSwitch(true);
                connectionManager.setConnection(connectionManager.getBestAvailableConnection());
            }

            // update connection
            onConnectionChanged(connectionManager.getConnection());
        }
    }

    private void onConnectionChanged(MoneroRpcConnection currentConnection) {
        synchronized (lock) {
            if (currentConnection == null) {
                daemon = null;
                connectionList.setCurrentConnectionUri(null);
            } else {
                daemon = new MoneroDaemonRpc(connectionManager.getConnection());
                connectionList.removeConnection(currentConnection.getUri());
                connectionList.addConnection(currentConnection);
                connectionList.setCurrentConnectionUri(currentConnection.getUri());
            }
            startPollingDaemon();
        }
    }

    private void startPollingDaemon() {
        if (updateDaemonLooper != null) updateDaemonLooper.stop();
        updateDaemonInfo();
        updateDaemonLooper = new TaskLooper(() -> {
            updateDaemonInfo();
        });
        updateDaemonLooper.start(getDefaultRefreshPeriodMs());
    }

    private void updateDaemonInfo() {
        try {
            log.trace("Updating daemon info");
            if (daemon == null) throw new RuntimeException("No daemon connection");
            lastInfo = daemon.getInfo();
            //System.out.println(JsonUtils.serialize(lastInfo));
            //System.out.println(JsonUtils.serialize(daemon.getSyncInfo()));
            chainHeight.set(lastInfo.getTargetHeight() == 0 ? lastInfo.getHeight() : lastInfo.getTargetHeight());
            try {
                peers.set(getOnlinePeers());
            } catch (MoneroError err) {
                peers.set(new ArrayList<MoneroPeer>()); // TODO: peers unknown due to restricted RPC call
            }
            numPeers.set(peers.get().size());
            if (lastErrorTimestamp != null) {
                log.info("Successfully fetched daemon info after previous error");
                lastErrorTimestamp = null;
            }
        } catch (Exception e) {
            if (lastErrorTimestamp == null || System.currentTimeMillis() - lastErrorTimestamp > MIN_ERROR_LOG_PERIOD_MS) {
                lastErrorTimestamp = System.currentTimeMillis();
                log.warn("Could not update daemon info: " + e.getMessage());
                if (DevEnv.isDevMode()) e.printStackTrace();
            }
            if (connectionManager.getAutoSwitch()) connectionManager.setConnection(connectionManager.getBestAvailableConnection());
        }
    }

    private List<MoneroPeer> getOnlinePeers() {
        return daemon.getPeers().stream()
                .filter(peer -> peer.isOnline())
                .collect(Collectors.toList());
    }
}

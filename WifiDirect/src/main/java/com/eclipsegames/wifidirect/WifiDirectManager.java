package com.eclipsegames.wifidirect;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.ChannelListener;
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.os.Looper;
import android.util.Pair;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WifiDirectManager {
    private final Activity activity;
    private WifiDirectBroadcastReceiver receiver;
    private final WifiP2pManager p2pManager;
    private final Channel channel;
    private final List<WifiP2pDevice> discoveredPeers = new ArrayList<>();
    private WifiDirectThread thread;
    private static final int PORT_NUM = 8888;
    private final EventListener eventListener;

    /*============================================================================*/
    /*================================ PUBLIC API ================================*/
    /*============================================================================*/

    /**Please call the Close() method before the object is destroyed by the GC to ensure that resources are released.*/
    public WifiDirectManager(Activity a, EventListener e) {
        activity = a;
        receiver = new WifiDirectBroadcastReceiver();
        p2pManager = (WifiP2pManager)activity.getSystemService(Context.WIFI_P2P_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        activity.getApplication().getApplicationContext().registerReceiver(receiver, filter);
        assert p2pManager != null;
        channel = p2pManager.initialize(activity, Looper.getMainLooper(), new WifiDirectListener());
        eventListener = e;
    }

    /**Please call the Close() method before the object is destroyed by the GC to ensure that resources are released.*/
    public void Close() {
        CancelDiscovery();
        CancelConnect();
        if (thread != null) {
            thread.CloseThread();
            thread = null;
        }
        activity.unregisterReceiver(receiver);
        receiver = null;
    }

    /*========================================================================*/
    /*================================ CLIENT ================================*/
    /*========================================================================*/

    /*================================ Discover Peers ================================*/

    /**Asynchronously starts discovering peers.
     * To actually get the list of peers, call GetDiscoveredPeers().
     * It's strongly recommended to subscribe GetDiscoveredPeers() to the peerListener.*/
    public void DiscoverPeers() {
        p2pManager.discoverPeers(channel, new ActionListener() {
            @Override public void onSuccess() {eventListener.OnPeerStatusChanged(Status.ATTEMPTING_PEER_DISCOVERY.ordinal());}
            @Override public void onFailure(int reasonCode) {eventListener.OnPeerStatusChanged(Status.FAILED_TO_DISCOVER_PEERS.ordinal());}
        });
    }

    public void CancelDiscovery() {p2pManager.stopPeerDiscovery(channel, null);}

    /**Gets the list of currently discovered peers.
     * It's strongly recommended to subscribe this function to the peerListener.*/
    public List<Pair<String, String>> GetDiscoveredPeers() {
        List<Pair<String, String>> devices = new ArrayList<>();
        for (WifiP2pDevice d : discoveredPeers)
            devices.add(new Pair<>(d.deviceAddress, d.deviceName));
        return devices;
    }

    /*================================ Connect to Specific Peer ================================*/

    /**Attempts to connect to the device with the give MAC address.
     * Returns false if the device with the address has not been discovered.
     * Returns true otherwise. A true return does not necessarily mean the connection was successful.
     * Handle the results of the connection by subscribing to connectionListener.*/
    public boolean ConnectToPeer(String macAddress) {
        Optional<WifiP2pDevice> device = discoveredPeers.stream().filter(d -> d.deviceAddress.equals(macAddress)).findFirst();
        if (!device.isPresent()) return false;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.get().deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        p2pManager.connect(channel, config, new ActionListener() {
            @Override public void onSuccess() {eventListener.OnConnectionStatusChanged(Status.ATTEMPTING_CONNECTION.ordinal());}
            @Override public void onFailure(int reason) {eventListener.OnConnectionStatusChanged(Status.CONNECTION_FAILED.ordinal());}
        });
        return true;
    }

    /**Cancels an ongoing connection attempt*/
    public void CancelConnect() {p2pManager.cancelConnect(channel, null);}

    /*==================================================================================*/
    /*================================ CLIENT OR SERVER ================================*/
    /*==================================================================================*/

    /**Cannot be called until after StartServer() or ConnectToServer() has been called and succeeded.
     * Cannot be called after StopThread().*/
    public void SendMessage(byte[] message) {thread.SendMessage(message);}

    /*================================ Event Handlers ================================*/

    public interface EventListener {
        void OnMessageReceived(byte[] message);
        void OnStatusChanged(int status);
        void OnPeerStatusChanged(int status);
        void OnConnectionStatusChanged(int status);
        void OnDisconnected(int status);
        void OnError(int status);
    }

    private enum Status {
        WIFI_DIRECT_ENABLED, WIFI_DIRECT_DISABLED,//wifi direct toggled on/off
        ATTEMPTING_PEER_DISCOVERY, FAILED_TO_DISCOVER_PEERS, STARTED_PEER_DISCOVERY, PEER_LIST_CHANGED, NO_PEERS_FOUND, STOPPED_DISCOVERY,//peer list changed
        ATTEMPTING_CONNECTION, CONNECTION_FAILED, CONNECTION_SUCCESSFUL, SOCKET_CONNECTION_SUCCESSFUL, SOCKET_CONNECTION_FAILED,//connection status changed
        DISCONNECTED, CONNECTION_LOST,//disconnection status changed
        ERROR_UNHANDLED_ACTION, ERROR_SENDING_MESSAGE, ERROR_RECEIVING_MESSAGE, ERROR_CREATING_SERVER_SOCKET//error status
    }

    /*=================================================================================*/
    /*================================ PRIVATE CLASSES ================================*/
    /*=================================================================================*/

    /**Receives and handles system broadcasts. Nested in WifiDirectManager*/
    private final class WifiDirectBroadcastReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) action = "";
            switch (action) {
                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                    //wifi direct turned on/off
                    eventListener.OnStatusChanged(
                        (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        ? Status.WIFI_DIRECT_ENABLED
                        : Status.WIFI_DIRECT_DISABLED).ordinal()
                    );
                    break;
                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                    //peer list changed
                    if (p2pManager != null) p2pManager.requestPeers(channel, new WifiDirectListener());
                    break;
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                    //connection state changed
                    if (p2pManager != null) p2pManager.requestConnectionInfo(channel, new WifiDirectListener());
                    break;
                case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:
                    //discovery has changed
                    eventListener.OnPeerStatusChanged(
                        (intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        ? Status.STARTED_PEER_DISCOVERY
                        : Status.STOPPED_DISCOVERY).ordinal()
                    );
                    break;
                default:
                    eventListener.OnError(Status.ERROR_UNHANDLED_ACTION.ordinal());
                    break;
            }
        }
    }

    private class WifiDirectListener implements ChannelListener, ConnectionInfoListener, PeerListListener {
        @Override public void onChannelDisconnected() {eventListener.OnDisconnected(Status.DISCONNECTED.ordinal());}
        @Override public void onConnectionInfoAvailable(WifiP2pInfo wifiP2pInfo) {
            eventListener.OnConnectionStatusChanged(Status.CONNECTION_SUCCESSFUL.ordinal());
            if (!wifiP2pInfo.groupFormed || thread != null) return;
            thread = wifiP2pInfo.isGroupOwner? new ServerThread() : new ClientThread(wifiP2pInfo.groupOwnerAddress.getHostAddress());
            thread.start();
        }
        @Override public void onPeersAvailable(WifiP2pDeviceList peerList) {
            List<WifiP2pDevice> refreshedPeers = (List<WifiP2pDevice>) peerList.getDeviceList();
            synchronized (discoveredPeers) {
                if (discoveredPeers.size() == 0) {
                    eventListener.OnPeerStatusChanged(Status.NO_PEERS_FOUND.ordinal());
                    return;
                }
                if (!refreshedPeers.equals(discoveredPeers)) {
                    discoveredPeers.clear();
                    discoveredPeers.addAll(refreshedPeers);
                    eventListener.OnPeerStatusChanged(Status.PEER_LIST_CHANGED.ordinal());
                }
            }
        }
    }

    /*================================ Sockets and IO ================================*/

    private abstract class WifiDirectThread extends Thread {
        protected Socket otherDeviceSocket;
        protected InputStream iStream;
        protected OutputStream oStream;
        protected KeepAliveThread keepAliveThread;
        private final ExecutorService executorService;
        protected static final int portNum = PORT_NUM;

        protected WifiDirectThread() {
            executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            keepAliveThread = new KeepAliveThread();
        }

        protected final void ReadMessages() throws IOException {
            byte[] buffer = new byte[1024], temp;
            int bytesRead;
            executorService.execute(() -> {//send a keep-alive message every 5 seconds
                while (otherDeviceSocket.isConnected()) {
                    try {
                        oStream.write(new byte[] {0});
                        executorService.wait(5000);
                    } catch (IOException | InterruptedException ignored) {
                        eventListener.OnError(Status.ERROR_SENDING_MESSAGE.ordinal());
                    }
                }
            });
            while (otherDeviceSocket.isConnected()) {//continuously read messages
                bytesRead = iStream.read(buffer);
                if (bytesRead == -1) {
                    eventListener.OnDisconnected(Status.DISCONNECTED.ordinal());
                    return;
                }
                if (isInterrupted()) return;
                temp = new byte[bytesRead];
                System.arraycopy(buffer, 0, temp, 0, bytesRead);
                if (temp[0] == 0) keepAliveThread.GotKeepAlive();
                else eventListener.OnMessageReceived(temp);
            }
        }

        private void SendMessage(byte[] message) {
            executorService.execute(() -> {
                try {oStream.write(message);}
                catch (IOException e) {eventListener.OnError(Status.ERROR_SENDING_MESSAGE.ordinal());}
            });
        }

        protected final void CleanResources() {
            try {otherDeviceSocket.close();} catch (IOException ignored) {/*Already closed*/}
            try {iStream.close();} catch (IOException ignored) {/*Already closed*/}
            try {oStream.close();} catch (IOException ignored) {/*Already closed*/}
            otherDeviceSocket = null;
            iStream = null;
            oStream = null;
            executorService.shutdown();
            keepAliveThread.running = false;
            keepAliveThread.interrupt();
        }

        protected void CloseThread() {
            interrupt();
            CleanResources();
        }

        private final class KeepAliveThread extends Thread {
            private volatile boolean running, gotKeepAlive;
            private KeepAliveThread() {running = true;}
            private void GotKeepAlive() {gotKeepAlive = true;}
            @Override public void run() {
                while (running) {
                    try {wait(6000);}
                    catch (InterruptedException e) {CloseThread();}
                    if (gotKeepAlive) gotKeepAlive = false;
                    else {
                        eventListener.OnDisconnected(Status.CONNECTION_LOST.ordinal());
                        CloseThread();
                    }
                }
            }
        }
    }

    private final class ServerThread extends WifiDirectThread {
        private ServerSocket serverSocket;

        private ServerThread() {
            try {
                serverSocket = new ServerSocket(portNum);
                otherDeviceSocket = serverSocket.accept();
                iStream = otherDeviceSocket.getInputStream();
                oStream = otherDeviceSocket.getOutputStream();
                keepAliveThread.start();
                eventListener.OnConnectionStatusChanged(Status.SOCKET_CONNECTION_SUCCESSFUL.ordinal());
            } catch (IOException e) {
                eventListener.OnError(Status.ERROR_CREATING_SERVER_SOCKET.ordinal());
                try {serverSocket.close();} catch (IOException ignored) {/*Already closed*/}
                serverSocket = null;
                CleanResources();
            }
        }

        @Override public void run() {
            if (serverSocket == null || otherDeviceSocket == null || iStream == null || oStream == null) return;
            try {ReadMessages();}
            catch (IOException e) {eventListener.OnError(Status.ERROR_RECEIVING_MESSAGE.ordinal());}
            finally {
                try {serverSocket.close();} catch (IOException ignored) {/*Already closed*/}
                serverSocket = null;
                CleanResources();
            }
        }

        @Override protected void CloseThread() {
            super.CloseThread();
            try {serverSocket.close();} catch (IOException ignored) {/*Already closed*/}
            serverSocket = null;
        }
    }

    private final class ClientThread extends WifiDirectThread {
        private ClientThread (String ipAddress) {
            try {
                otherDeviceSocket = new Socket(ipAddress, portNum);
                iStream = otherDeviceSocket.getInputStream();
                oStream = otherDeviceSocket.getOutputStream();
                keepAliveThread.start();
                eventListener.OnConnectionStatusChanged(Status.SOCKET_CONNECTION_SUCCESSFUL.ordinal());
            } catch (IOException e) {
                eventListener.OnConnectionStatusChanged(Status.SOCKET_CONNECTION_FAILED.ordinal());
                CleanResources();
            }
        }

        @Override public void run() {
            if (otherDeviceSocket == null || iStream == null || oStream == null) return;
            try {ReadMessages();}
            catch (IOException ignored) {eventListener.OnError(Status.ERROR_RECEIVING_MESSAGE.ordinal());}
            finally {CleanResources();}
        }
    }
}
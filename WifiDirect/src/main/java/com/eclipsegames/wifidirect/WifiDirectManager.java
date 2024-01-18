package com.eclipsegames.wifidirect;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.MacAddress;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pConfig.Builder;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WifiDirectManager {
    private final Activity activity;
    private final WifiP2pManager p2pManager;
    private final Channel channel;
    private final EventListener eventListener;
    private WifiDirectBroadcastReceiver receiver;
    private final Map<String, String> discoveredServices = new HashMap<>();//<MAC address, device name>
    private WifiDirectThread thread;
    private WifiP2pDnsSdServiceInfo serviceInfo;
    private static final int PORT_NUM = 8888;

    /*============================================================================*/
    /*================================ PUBLIC API ================================*/
    /*============================================================================*/

    /**Please call the Close() method before the object is destroyed by the GC to ensure that resources are released.*/
    public WifiDirectManager(Activity a, EventListener e) {
        eventListener = e;
        activity = a;
        p2pManager = (WifiP2pManager) activity.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = p2pManager.initialize(activity, Looper.getMainLooper(), () -> eventListener.OnConnectionStatusChanged(Status.DISCONNECTED.ordinal()));
        receiver = new WifiDirectBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        activity.registerReceiver(receiver, filter);
    }

    /**Please call the Close() method before the object is destroyed by the GC to ensure that resources are released.*/
    public void Close() {
        CancelDiscovery();
        CancelConnect();
        if (thread != null) {
            thread.CloseThread();
            thread = null;
        }
        RemoveService();
        Disconnect();
        activity.unregisterReceiver(receiver);
        receiver = null;
    }

    /*========================================================================*/
    /*================================ SERVER ================================*/
    /*========================================================================*/

    /**If this device hosts a server, this method will attempt to create a discoverable server that clients can search for and connect to.*/
    @SuppressLint("MissingPermission")
    public void CreateDiscoverableServer(String passphrase) {
        RemoveService();
        Disconnect();
        WifiP2pConfig config = new Builder().setNetworkName("DIRECT-pn3-Pennies-Server").setPassphrase(passphrase).build();
        p2pManager.createGroup(channel, config, new ActionListener() {
            @Override public void onSuccess() {
                Map<String, String> record = new HashMap<>();
                record.put("SSID", config.getNetworkName());
                serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("3-Pennies-Server", "_presence._tcp", record);
                p2pManager.addLocalService(channel, serviceInfo, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() {eventListener.OnDiscoveryStatusChanged(Status.SERVICE_DISCOVERABLE.ordinal());}
                    @Override public void onFailure(int reason) {eventListener.OnError(Status.ERROR_ADDING_SERVICE.ordinal(), reason);}
                });
            }
            @Override public void onFailure(int i) {eventListener.OnError(Status.ERROR_CREATING_GROUP.ordinal(), i);}//TODO figure out why this errored
        });
    }

    /**Removes the local service from discoverability. Does not disconnect clients.*/
    public void RemoveService() {
        p2pManager.clearLocalServices(channel, new ActionListener() {
            @Override public void onSuccess() {eventListener.OnDiscoveryStatusChanged(Status.SERVICE_REMOVED.ordinal());}
            @Override public void onFailure(int i) {}
        });
    }

    /*========================================================================*/
    /*================================ CLIENT ================================*/
    /*========================================================================*/

    /**Asynchronous call to start discovering services. Filters by network name to only callback 3-Pennies-Server.*/
    public void DiscoverServices() {
        CancelDiscovery();
        WifiP2pDnsSdServiceRequest serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
        p2pManager.addServiceRequest(channel, serviceRequest, new ActionListener() {
            @SuppressLint("MissingPermission")
            @Override public void onSuccess() {
                p2pManager.discoverServices(channel, new ActionListener() {
                    @Override public void onSuccess() {}//handled in setDnsSdResponseListeners
                    @Override public void onFailure(int i) {eventListener.OnError(Status.ERROR_DISCOVERING_SERVICES.ordinal(), i);}//TODO figure out why this errored
                });
            }
            @Override public void onFailure(int i) {eventListener.OnError(Status.ERROR_ADDING_SERVICE_REQUEST.ordinal(), i);}
        });
        p2pManager.setDnsSdResponseListeners(channel,
            (instanceName, registrationType, srcDevice) -> {
                if (instanceName.equals("3-Pennies-Server")) {
                    discoveredServices.put(srcDevice.deviceAddress, srcDevice.deviceName);
                    eventListener.OnDiscoveryStatusChanged(Status.SERVICE_LIST_CHANGED.ordinal());
                }
            }, null
        );
    }

    /**Stops an ongoing discovery search.*/
    public void CancelDiscovery() {
        p2pManager.clearServiceRequests(channel, null);
        discoveredServices.clear();
    }

    /**Returns the current map of devices (MAC address, device name) that are hosting a valid service.*/
    public Map<String, String> GetDiscoveredServices() {return discoveredServices;}

    /**Attempts to connect to the device with the given MAC address and passphrase.*/
    @SuppressLint("MissingPermission")
    public void ConnectToService(String macAddress, String passphrase) {
        WifiP2pConfig config = new Builder().setNetworkName("DIRECT-pn3-Pennies-Server").setDeviceAddress(MacAddress.fromString(macAddress)).setPassphrase(passphrase).build();
        config.wps.setup = WpsInfo.PBC;
        p2pManager.connect(channel, config, new ActionListener() {
            @Override public void onSuccess() {}//handled in broadcast receiver
            @Override public void onFailure(int reason) {eventListener.OnError(Status.ERROR_CONNECTING.ordinal(), reason);}
        });
    }

    /**Cancels an ongoing connection attempt. Does not disconnect an existing connection.*/
    public void CancelConnect() {p2pManager.cancelConnect(channel, null);}

    /*==================================================================================*/
    /*================================ CLIENT OR SERVER ================================*/
    /*==================================================================================*/

    /**Disconnects. If called by the server, you need to first call RemoveService().*/
    public void Disconnect() {p2pManager.removeGroup(channel, null);}

    /**Sends a message to the other device.
     * Cannot be called until after a connection has successfully been established, and while they are connected.
     * Cannot be called after StopThread(). Will throw Java Runtime exception.*/
    public void SendMessage(byte[] message) {thread.SendMessage(message);}

    /*================================ Event Handlers ================================*/

    public interface EventListener {
        void OnMessageReceived(byte[] message);
        void OnStatusChanged(int status);
        void OnDiscoveryStatusChanged(int status);
        void OnConnectionStatusChanged(int status);
        void OnError(int status, int reason);
    }

    private enum Status {
        WIFI_DIRECT_ENABLED, WIFI_DIRECT_DISABLED,//wifi direct toggled on/off
        SERVICE_DISCOVERABLE, SERVICE_REMOVED, STARTED_DISCOVERY, SERVICE_LIST_CHANGED, STOPPED_DISCOVERY,//service discovery
        CONNECTION_SUCCESSFUL, DISCONNECTED, CONNECTION_LOST,//connection status
        ERROR_CREATING_GROUP, ERROR_ADDING_SERVICE_REQUEST, ERROR_ADDING_SERVICE,//error status
            ERROR_DISCOVERING_SERVICES, ERROR_CONNECTING, ERROR_SOCKET_CONNECTION_FAILED,
            ERROR_UNHANDLED_ACTION, ERROR_SENDING_MESSAGE, ERROR_RECEIVING_MESSAGE, ERROR_CREATING_SERVER_SOCKET
    }

    /*=================================================================================*/
    /*================================ PRIVATE CLASSES ================================*/
    /*=================================================================================*/

    /**Receives and handles system broadcasts. Nested in WifiDirectManager*/
    private final class WifiDirectBroadcastReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
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
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                    //connected
                    WifiP2pInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                    if (info == null) return;
                    CancelDiscovery();
                    RemoveService();
                    if (!info.groupFormed || thread != null) return;
                    eventListener.OnConnectionStatusChanged(Status.CONNECTION_SUCCESSFUL.ordinal());
                    thread = info.isGroupOwner? new ServerThread() : new ClientThread(info.groupOwnerAddress.getHostAddress());
                    thread.start();
                    break;
                case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:
                    //discovery turned on/off
                    eventListener.OnDiscoveryStatusChanged(
                        (intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        ? Status.STARTED_DISCOVERY
                        : Status.STOPPED_DISCOVERY).ordinal()
                    );
                    break;
                default:
                    eventListener.OnError(Status.ERROR_UNHANDLED_ACTION.ordinal(), -1);
                    break;
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
                        eventListener.OnError(Status.ERROR_SENDING_MESSAGE.ordinal(), -1);
                    }
                }
            });
            while (otherDeviceSocket.isConnected()) {//continuously read messages
                bytesRead = iStream.read(buffer);
                if (bytesRead == -1) {
                    eventListener.OnConnectionStatusChanged(Status.DISCONNECTED.ordinal());
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
                catch (IOException e) {eventListener.OnError(Status.ERROR_SENDING_MESSAGE.ordinal(), -1);}
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
                        eventListener.OnConnectionStatusChanged(Status.CONNECTION_LOST.ordinal());
                        CloseThread();
                    }
                }
            }
        }
    }

    private final class ServerThread extends WifiDirectThread {
        private ServerSocket serverSocket;

        private ServerThread() {super();}

        @Override public void run() {
            try {
                serverSocket = new ServerSocket(portNum);
                otherDeviceSocket = serverSocket.accept();
                iStream = otherDeviceSocket.getInputStream();
                oStream = otherDeviceSocket.getOutputStream();
                keepAliveThread.start();
                eventListener.OnConnectionStatusChanged(Status.CONNECTION_SUCCESSFUL.ordinal());
            } catch (IOException e) {
                eventListener.OnError(Status.ERROR_CREATING_SERVER_SOCKET.ordinal(), -1);
                try {serverSocket.close();} catch (IOException ignored) {/*Already closed*/}
                serverSocket = null;
                CleanResources();
            }
            if (serverSocket == null || otherDeviceSocket == null || iStream == null || oStream == null) return;
            try {ReadMessages();}
            catch (IOException e) {eventListener.OnError(Status.ERROR_RECEIVING_MESSAGE.ordinal(), -1);}
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
        private final String ipAddress;

        private ClientThread (String ip) {
            super();
            ipAddress = ip;
        }

        @Override public void run() {
            try {
                otherDeviceSocket = new Socket(ipAddress, portNum);
                iStream = otherDeviceSocket.getInputStream();
                oStream = otherDeviceSocket.getOutputStream();
                keepAliveThread.start();
                eventListener.OnConnectionStatusChanged(Status.CONNECTION_SUCCESSFUL.ordinal());
            } catch (IOException e) {
                eventListener.OnConnectionStatusChanged(Status.ERROR_SOCKET_CONNECTION_FAILED.ordinal());
                CleanResources();
            }
            if (otherDeviceSocket == null || iStream == null || oStream == null) return;
            try {ReadMessages();}
            catch (IOException ignored) {eventListener.OnError(Status.ERROR_RECEIVING_MESSAGE.ordinal(), -1);}
            finally {CleanResources();}
        }
    }
}
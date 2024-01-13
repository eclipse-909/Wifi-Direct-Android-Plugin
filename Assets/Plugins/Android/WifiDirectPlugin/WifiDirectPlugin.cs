using UnityEngine;
using System;
using System.Collections.Generic;

namespace WifiDirectPlugin {
    /// <summary>
    /// This class is the bridge between C# and Java via JNI calls.
    /// This class uses a singleton because it doesn't make sense to have multiple instances of a WifiDirect object.
    /// </summary>
    public sealed class WifiDirect {
        readonly AndroidJavaObject wifiDirectManager;
        static AndroidJavaObject unityActivity;
        static WifiDirect thisDevice = null;
        /// <summary>Automatically instantiates if null. Use this to call instance methods.</summary>
        public static WifiDirect ThisDevice => thisDevice ??= new();

        WifiDirect() {
            if (unityActivity == null) {
                unityActivity = new AndroidJavaClass("com.unity3d.player.UnityPlayer").GetStatic<AndroidJavaObject>("currentActivity");
                unityActivity.Call("SetServiceName", Application.productName);
            }
            wifiDirectManager = new AndroidJavaObject("com.eclipsegames.wifidirect.WifiDirectManager", unityActivity, new EventCallbackProxy(this));
        }

        ~WifiDirect() {wifiDirectManager.Call("Close");}
        
        /*================================ SERVER ONLY METHODS ================================*/
        /// <summary>
        /// Server method.
        /// Creates a Wifi-Direct group to host clients on.
        /// </summary>
        public void CreateP2PGroup() {wifiDirectManager.Call("CreateGroup");}

        /*================================ CLIENT ONLY METHODS ================================*/
        /// <summary>
        /// Client method.
        /// Initiates the process to asynchronously discover nearby devices with Wifi-Direct.
        /// Subscribe to the PeerStatusChanged event to listen for changes.
        /// </summary>
        public void DiscoverPeers() {wifiDirectManager.Call("DiscoverPeers");}
        /// <summary>
        /// Client method.
        /// Stops an ongoing peer discovery.
        /// Subscribe to the PeerStatusChanged event to listen for changes.
        /// </summary>
        public void CancelDiscovery() {wifiDirectManager.Call("CancelDiscovery");}
        /// <summary>Gets the list of currently discovered peers. The list is only up-to-date while discovering.</summary>
        public List<(string macAddress, string deviceName)> GetDiscoveredPeers() {
            return AndroidJNIHelper.ConvertFromJNIArray<List<(string, string)>>(
                wifiDirectManager.Call<AndroidJavaObject>("GetDiscoveredPeers").GetRawObject()
            );
        }
        /// <summary>
        /// Client method.
        /// Attempts to connect to the device with the given MAC address.
        /// Can only successfully connect if the device has been discovered.
        /// Subscribe to the ConnectionAttempted event to listen for changes.
        /// </summary>
        public bool ConnectToPeer(string macAddress) {return wifiDirectManager.Call<bool>("ConnectToPeer", macAddress);}
        /// <summary>
        /// Client method.
        /// Cancels an ongoing connection attempt.
        /// Subscribe to the ConnectionAttempted event to listen for changes.
        /// </summary>
        public void CancelConnect() {wifiDirectManager.Call("CancelConnect");}

        /*================================ CLIENT OR SERVER METHOD ================================*/
        /// <summary>Removes the Wifi-Direct group if exists, and disconnects the devices.</summary>
        public void RemoveP2PGroup() {wifiDirectManager.Call("CreateGroup");}
        /// <summary>
        /// Sends a message to the other device.
        /// This function will only work if you are connected to another device.
        /// Subscribe to ConnectionAttempted and listen for status == SOCKET_CONNECTION_SUCCESSFUL to find out when you can start using this method.
        /// This method does not check if the communication thread or the socket is null, so it will throw a Java runtime error if called incorrectly.
        /// </summary>
        public void SendMessage(byte[] message) {wifiDirectManager.Call("SendMessage", message);}

        /*================================ Event Handlers ================================*/
        //Subscribe to these events to handle important changes in the program.
        /// <summary>
        /// Invoked when this device receives a message from the other.
        /// This does not include keep-alive messages as those are handled in the Java runtime.
        /// </summary>
        public event EventHandler<MessageReceivedEventArgs> MessageReceived;
        /// <summary>
        /// Invoked when Wifi-Direct on this device has been turned on or off.
        /// Status codes are WIFI_DIRECT_ENABLED and WIFI_DIRECT_DISABLED.
        /// </summary>
        public event EventHandler<StatusChangedEventArgs> StatusChanged;
        /// <summary>
        /// Invoked when a change has occured during peer discovery.
        /// Status codes are ATTEMPTING_PEER_DISCOVERY, FAILED_TO_DISCOVER_PEERS, STARTED_PEER_DISCOVERY, PEER_LIST_CHANGED, NO_PEERS_FOUND, and STOPPED_DISCOVERY.
        /// </summary>
        public event EventHandler<StatusChangedEventArgs> PeerStatusChanged;
        /// <summary>
        /// Invoked when a change has occured to your connection with another device.
        /// Status codes are ATTEMPTING_CONNECTION, CONNECTION_FAILED, CONNECTION_SUCCESSFUL, SOCKET_CONNECTION_SUCCESSFUL, and SOCKET_CONNECTION_FAILED.
        /// </summary>
        public event EventHandler<StatusChangedEventArgs> ConnectionStatusChanged;
        /// <summary>
        /// Invoked when this device has disconnected from the other device.
        /// Status codes are DISCONNECTED and CONNECTION_LOST.
        /// </summary>
        public event EventHandler<StatusChangedEventArgs> Disconnected;
        /// <summary>
        /// Invoked when an error has occured during the Java runtime.
        /// Excludes errors that arise from using SendMessage() incorrectly and any unhandled, unexpected errors.
        /// Status codes are ERROR_UNHANDLED_ACTION, ERROR_SENDING_MESSAGE, ERROR_RECEIVING_MESSAGE, and ERROR_CREATING_SERVER_SOCKET.
        /// </summary>
        public event EventHandler<StatusChangedEventArgs> Error;

        /*================================ Counterparts to JNI callback invocation functions ================================*/
        void OnMessageReceived(MessageReceivedEventArgs args) {MessageReceived?.Invoke(this, args);}
        void OnStatusChanged(StatusChangedEventArgs args) {StatusChanged?.Invoke(this, args);}
        void OnPeerStatusChanged(StatusChangedEventArgs args) {PeerStatusChanged?.Invoke(this, args);}
        void OnConnectionStatusChanged(StatusChangedEventArgs args) {ConnectionStatusChanged?.Invoke(this, args);}
        void OnDisconnection(StatusChangedEventArgs args) {Disconnected?.Invoke(this, args);}
        void OnError(StatusChangedEventArgs args) {Error?.Invoke(this, args);}
        
        sealed class EventCallbackProxy : AndroidJavaProxy {
            readonly WifiDirect manager;

            public EventCallbackProxy(WifiDirect m) : base("com.eclipsegames.wifidirect.WifiDirectManager$EventListener") {manager = m;}

            void OnMessageReceived(byte[] message) {manager.OnMessageReceived(new(message));}
            void OnStatusChanged(int status) {manager.OnStatusChanged(new(status));}
            void OnPeerStatusChanged(int status) {manager.OnPeerStatusChanged(new(status));}
            void OnConnectionAttempted(int status) {manager.OnConnectionStatusChanged(new(status));}
            void OnDisconnection(int status) {manager.OnDisconnection(new(status));}
            void OnError(int status) {manager.OnError(new(status));}
        }
    }

    /// <summary>
    /// All events, except MessageReceived, will give you an int status in each callback function.
    /// Match the status to the status codes to handle specific events.
    /// Each event does not use every status code, so you don't need to handle each one.
    /// See the documentation for each event to see which status codes they are limited to.
    /// </summary>
    public enum WifiDirectStatus {
        WIFI_DIRECT_ENABLED, WIFI_DIRECT_DISABLED,//wifi direct toggled on/off
        ATTEMPTING_PEER_DISCOVERY, FAILED_TO_DISCOVER_PEERS, STARTED_PEER_DISCOVERY, PEER_LIST_CHANGED, NO_PEERS_FOUND, STOPPED_DISCOVERY,//peer-discovery list changed
        ATTEMPTING_CONNECTION, CONNECTION_FAILED, CONNECTION_SUCCESSFUL, SOCKET_CONNECTION_SUCCESSFUL, SOCKET_CONNECTION_FAILED,//connection status changed
        DISCONNECTED, CONNECTION_LOST,//disconnection status changed
        ERROR_CREATING_GROUP, ERROR_UNHANDLED_ACTION, ERROR_SENDING_MESSAGE, ERROR_RECEIVING_MESSAGE, ERROR_CREATING_SERVER_SOCKET//error status
    }
    /// <summary>Event args for MessageReceived event. Property: byte[] Message</summary>
    public sealed class MessageReceivedEventArgs : EventArgs {
        public byte[] Message {get; set;}
        public MessageReceivedEventArgs(byte[] msg) {Message = msg;}
    }
    /// <summary>Event args for all events except MessageReceived. Property: int Status</summary>
    public sealed class StatusChangedEventArgs : EventArgs {
        public int Status {get; set;}
        public StatusChangedEventArgs(int s) {Status = s;}
    }
}
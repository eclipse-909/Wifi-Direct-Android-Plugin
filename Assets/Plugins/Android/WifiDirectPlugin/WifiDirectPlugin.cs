using UnityEngine;
using System;
using System.Collections.Generic;

namespace Plugins.Android.WifiDirectPlugin {
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
            unityActivity ??= new AndroidJavaClass("com.unity3d.player.UnityPlayer").GetStatic<AndroidJavaObject>("currentActivity");
            wifiDirectManager = new AndroidJavaObject("com.eclipsegames.wifidirect.WifiDirectManager", unityActivity, new EventCallbackProxy(this));
        }

        ~WifiDirect() {wifiDirectManager.Call("Close");}
        
        /*================================ SERVER ONLY METHODS ================================*/
        /// <summary>
        /// Server method.
        /// Creates and hosts a server on this device for other to connect to with ConnectToServer(string, string).
        /// Subscribe to DiscoveryStatusChanged to be notified if the server was created successfully.
        /// </summary>
        public void CreateDiscoverableServer(string passphrase) {wifiDirectManager.Call("CreateDiscoverableServer", passphrase);}
        /// <summary>
        /// Server method.
        /// Removes any existing services hosted on this device. This does not disconnect any connected clients. It just makes your device not discoverable.
        /// Subscribe to DiscoveryStatusChanged to be notified if the server was removed successfully.
        /// </summary>
        public void RemoveService() {wifiDirectManager.Call("RemoveService");}

        /*================================ CLIENT ONLY METHODS ================================*/
        /// <summary>
        /// Client method.
        /// Asynchronous call to start discovering services hosted on other devices.
        /// Subscribe to DiscoveryStatusChanged to be notified if the map of discovered devices has changed.
        /// </summary>
        public void DiscoverServices() {wifiDirectManager.Call("DiscoverServices");}
        /// <summary>
        /// Client method.
        /// Cancels an ongoing service discovery search.
        /// Subscribe to DiscoveryStatusChanged to be notified if discovery has stopped.
        /// </summary>
        public void CancelDiscovery() {wifiDirectManager.Call("CancelDiscovery");}
        /// <summary>
        /// Client method.
        /// Returns the current map of discovered devices that are hosting a valid service.
        /// (MAC address -> device name).
        /// Subscribe to DiscoveryStatusChanged to be notified if the map of discovered devices has changed.
        /// </summary>
        public Dictionary<string, string> GetDiscoveredServices() {//<MAC address, device name>
            return AndroidJNIHelper.ConvertFromJNIArray<Dictionary<string, string>>(
                wifiDirectManager.Call<AndroidJavaObject>("GetDiscoveredServices").GetRawObject()
            );
        }
        /// <summary>
        /// Client method.
        /// Attempts to connect to a device with the given MAC address and passphrase.
        /// Subscribe to ConnectionStatusChanged to be notified if the connection attempt was successful.
        /// </summary>
        public void ConnectToService(string macAddress, string passphrase) {wifiDirectManager.Call("ConnectToService", macAddress, passphrase);}
        /// <summary>
        /// Client method.
        /// Cancels an ongoing connection attempt. Does not disconnect from the server.
        /// </summary>
        public void CancelConnect() {wifiDirectManager.Call("CancelConnect");}

        /*================================ CLIENT OR SERVER METHOD ================================*/
        /// <summary>
        /// Disconnects and stops all Wifi-Direct processes.
        /// If called by the server device, you must call RemoveService() first.
        /// Subscribe to ConnectionStatusChanged to be notified if you successfully disconnected.
        /// </summary>
        public void Disconnect() {wifiDirectManager.Call("Disconnect");}
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
        /// Invoked when a change has occured during service discovery.
        /// Status codes are SERVICE_DISCOVERABLE, SERVICE_REMOVED, STARTED_DISCOVERY, SERVICE_LIST_CHANGED, and STOPPED_DISCOVERY.
        /// </summary>
        public event EventHandler<StatusChangedEventArgs> DiscoveryStatusChanged;
        /// <summary>
        /// Invoked when a change has occured to your connection with another device.
        /// Status codes are CONNECTION_SUCCESSFUL, DISCONNECTED, and CONNECTION_LOST.
        /// </summary>
        public event EventHandler<StatusChangedEventArgs> ConnectionStatusChanged;
        /// <summary>
        /// Invoked when an error has been caught during the Java runtime.
        /// Excludes errors that arise from using SendMessage() incorrectly and any unhandled, unexpected errors.
        /// Status codes are ERROR_CREATING_GROUP, ERROR_ADDING_SERVICE_REQUEST, ERROR_ADDING_SERVICE,
        /// ERROR_DISCOVERING_SERVICES, ERROR_CONNECTING, ERROR_SOCKET_CONNECTION_FAILED, ERROR_UNHANDLED_ACTION,
        /// ERROR_SENDING_MESSAGE, ERROR_RECEIVING_MESSAGE, and ERROR_CREATING_SERVER_SOCKET.
        /// </summary>
        public event EventHandler<StatusChangedEventArgs> Error;

        /*================================ Counterparts to JNI callback invocation functions ================================*/
        void OnMessageReceived(MessageReceivedEventArgs args) {MessageReceived?.Invoke(this, args);}
        void OnStatusChanged(StatusChangedEventArgs args) {StatusChanged?.Invoke(this, args);}
        void OnDiscoveryStatusChanged(StatusChangedEventArgs args) {DiscoveryStatusChanged?.Invoke(this, args);}
        void OnConnectionStatusChanged(StatusChangedEventArgs args) {ConnectionStatusChanged?.Invoke(this, args);}
        void OnError(StatusChangedEventArgs args) {Error?.Invoke(this, args);}
        
        sealed class EventCallbackProxy : AndroidJavaProxy {
            readonly WifiDirect manager;

            public EventCallbackProxy(WifiDirect m) : base("com.eclipsegames.wifidirect.WifiDirectManager$EventListener") {manager = m;}

            void OnMessageReceived(byte[] message) {manager.OnMessageReceived(new(message));}
            void OnStatusChanged(int status) {manager.OnStatusChanged(new(status));}
            void OnDiscoveryStatusChanged(int status) {manager.OnDiscoveryStatusChanged(new(status));}
            void OnConnectionAttempted(int status) {manager.OnConnectionStatusChanged(new(status));}
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
        SERVICE_DISCOVERABLE, SERVICE_REMOVED, STARTED_DISCOVERY, SERVICE_LIST_CHANGED, STOPPED_DISCOVERY,//service discovery
        CONNECTION_SUCCESSFUL, DISCONNECTED, CONNECTION_LOST,//connection status
        ERROR_CREATING_GROUP, ERROR_ADDING_SERVICE_REQUEST, ERROR_ADDING_SERVICE,//error status
            ERROR_DISCOVERING_SERVICES, ERROR_CONNECTING, ERROR_SOCKET_CONNECTION_FAILED,
            ERROR_UNHANDLED_ACTION, ERROR_SENDING_MESSAGE, ERROR_RECEIVING_MESSAGE, ERROR_CREATING_SERVER_SOCKET
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
using System;
using System.Collections.Generic;
using System.Text;
using UnityEngine;
using UnityEngine.UI;
using WifiDirectPlugin;

public class WifiDirectManager : MonoBehaviour {
	bool initialized = false;
	public RectTransform wifiMultiplayerPanel;
	public Button hostButton, joinButton;
	public Text instructions;
	const string defaultInstructions = "Host a game or join a friend.",
				 hostInstructions = "Hosting game... Please stand near the person you wish to connect with. Passphrase: ",
				 discoveringInstructions = "Looking for server... Please stand near the person you wish to connect with.",
				 selectedInstructions = "Please enter the passphrase for the network you've selected.";

	public void OnWifiMultiplayerClicked() {
		if (initialized) return;
		wifiMultiplayerPanel.gameObject.SetActive(true);
		WifiDirect.ThisDevice.MessageReceived += HandleMessage;
		WifiDirect.ThisDevice.StatusChanged += WifiDirectStatusChanged;
		WifiDirect.ThisDevice.DiscoveryStatusChanged += PeerStatusChanged;
		WifiDirect.ThisDevice.ConnectionStatusChanged += ConnectionStatusChanged;
		WifiDirect.ThisDevice.Error += ErrorOccured;
		instructions.text = defaultInstructions;
		initialized = true;
		if (WifiDirect.permissionCallbacks != null) return;
		WifiDirect.permissionCallbacks = new();
		WifiDirect.permissionCallbacks.PermissionGranted += permission => {
			Debug.Log("Permission granted: " + permission);
		};
		WifiDirect.permissionCallbacks.PermissionDenied += permission => {
			Debug.Log("Permission denied: " + permission);
		};
		WifiDirect.permissionCallbacks.PermissionDeniedAndDontAskAgain += permission => {
			Debug.Log("Permission denied & don't ask again: " + permission);
		};
	}

	public void OnHostClicked() {
		const string valid = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
		StringBuilder res = new StringBuilder();
		System.Random rnd = new System.Random();
		byte length = 8;
		while (0 < length--)
			res.Append(valid[rnd.Next(valid.Length)]);
		string passphrase = res.ToString();
		WifiDirect.ThisDevice.CreateDiscoverableServer(passphrase);
		instructions.text = hostInstructions + passphrase;
		hostButton.gameObject.SetActive(false);
		joinButton.gameObject.SetActive(false);
	}

	public void OnJoinClicked() {
		WifiDirect.ThisDevice.DiscoverServices();
		instructions.text = discoveringInstructions;
		hostButton.gameObject.SetActive(false);
		joinButton.gameObject.SetActive(false);
	}

	public void OnCancelClicked() {
		WifiDirect.ThisDevice.CancelDiscovery();
		WifiDirect.ThisDevice.CancelConnect();
		Disconnect();
		instructions.text = defaultInstructions;
		hostButton.gameObject.SetActive(true);
		joinButton.gameObject.SetActive(true);
		wifiMultiplayerPanel.gameObject.SetActive(false);
	}

	void DisplayDiscoveredDevices() {
		Debug.Log("Updated discovered devices list:");
		foreach (KeyValuePair<string, string> device in WifiDirect.ThisDevice.GetDiscoveredServices())
			Debug.Log(device.Key + ": " + device.Value);
		//let each discovered device be a button that the user can click to connect to that device.
		//clicking a button will invoke OnNetworkSelected() - see below
	}

	public void OnNetworkSelected() {
		instructions.text = selectedInstructions;
		//display an input field for the user to enter the passphrase.
		//submitting the passphrase in the input field will invoke OnPassphraseSubmitted(string) - see below
	}
	
	public void OnPassphraseSubmitted(string macAddress, string passphrase) {
		WifiDirect.ThisDevice.ConnectToService(macAddress, passphrase);
	}

	public void Disconnect() {WifiDirect.ThisDevice.Disconnect();}

	public void SendMessage(byte[] message) {WifiDirect.ThisDevice.SendMessage(message);}

	void HandleMessage(object sender, MessageReceivedEventArgs args) {
		byte[] message = args.Message;
		Debug.Log(Encoding.UTF8.GetString(message));
	}

	void WifiDirectStatusChanged(object sender, StatusChangedEventArgs args) {
		Debug.Log("Wifi-Direct status changed: " + Enum.GetName(typeof(WifiDirectStatus), args.Status));
		switch ((WifiDirectStatus)args.Status) {
			case WifiDirectStatus.WIFI_DIRECT_ENABLED: break;
			case WifiDirectStatus.WIFI_DIRECT_DISABLED: break;
		}
	}

	void PeerStatusChanged(object sender, StatusChangedEventArgs args) {
		Debug.Log("Peer status changed: " + Enum.GetName(typeof(WifiDirectStatus), args.Status));
		switch ((WifiDirectStatus)args.Status) {
			case WifiDirectStatus.SERVICE_DISCOVERABLE: break;
			case WifiDirectStatus.SERVICE_REMOVED: break;
			case WifiDirectStatus.STARTED_DISCOVERY: break;
			case WifiDirectStatus.SERVICE_LIST_CHANGED:
				DisplayDiscoveredDevices();
				break;
			case WifiDirectStatus.STOPPED_DISCOVERY: break;
		}
	}

	void ConnectionStatusChanged(object sender, StatusChangedEventArgs args) {
		Debug.Log("Connection status changed: " + Enum.GetName(typeof(WifiDirectStatus), args.Status));
		switch ((WifiDirectStatus)args.Status) {
			case WifiDirectStatus.CONNECTION_SUCCESSFUL: break;
			case WifiDirectStatus.DISCONNECTED: break;
			case WifiDirectStatus.CONNECTION_LOST: break;
		}
	}

	void ErrorOccured(object sender, ErrorEventArgs args) {
		Debug.Log("Error: " + Enum.GetName(typeof(WifiDirectStatus), args.Status) + "\nReason: " + Enum.GetName(typeof(ErrorReason), args.Reason));
		switch ((WifiDirectStatus)args.Status) {
			case WifiDirectStatus.ERROR_CREATING_GROUP: break;
			case WifiDirectStatus.ERROR_ADDING_SERVICE_REQUEST: break;
			case WifiDirectStatus.ERROR_ADDING_SERVICE: break;
			case WifiDirectStatus.ERROR_DISCOVERING_SERVICES: break;
			case WifiDirectStatus.ERROR_CONNECTING: break;
			case WifiDirectStatus.ERROR_SOCKET_CONNECTION_FAILED: break;
			case WifiDirectStatus.ERROR_UNHANDLED_ACTION: break;
			case WifiDirectStatus.ERROR_SENDING_MESSAGE: break;
			case WifiDirectStatus.ERROR_RECEIVING_MESSAGE: break;
			case WifiDirectStatus.ERROR_CREATING_SERVER_SOCKET: break;
		}
	}
}
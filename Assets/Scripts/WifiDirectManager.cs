using UnityEngine;
using WifiDirectPlugin;

public class WifiDirectManager : MonoBehaviour {
	void Start() {
		WifiDirect.ThisDevice.MessageReceived += HandleMessage;
		WifiDirect.ThisDevice.StatusChanged += WifiDirectStatusChanged;
		//WifiDirect.ThisDevice.PeerStatusChanged += ;
		//WifiDirect.ThisDevice.ConnectionStatusChanged += ;
		//WifiDirect.ThisDevice.Disconnected += ;
		//WifiDirect.ThisDevice.Error += ;
	}
	
	public void OnHostClicked() {}

	public void OnJoinClicked() {}

	public void OnCancelClicked() {}

	public void DisplayDiscoveredDevices() {}

	public void Disconnect() {}

	public void SendMessage(byte[] message) {WifiDirect.ThisDevice.SendMessage(message);}

	void HandleMessage(object sender, MessageReceivedEventArgs args) {
		byte[] message = args.Message;
		//do something with message
	}

	void WifiDirectStatusChanged(object sender, StatusChangedEventArgs args) {
		switch ((WifiDirectStatus)args.Status) {
			case WifiDirectStatus.WIFI_DIRECT_ENABLED:
				//do something
				break;
			case WifiDirectStatus.WIFI_DIRECT_DISABLED:
				//do something
				break;
			default:
				//if you have a separate callback function for each event, this should never get executed.
				//if you have 1 callback function used by all events (except MessageReceived) it would be necessary to switch through all status codes.
				break;
		}
	}
}
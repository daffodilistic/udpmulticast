/*

UDP Messenger by Giacomo Furlan.
http://giacomofurlan.name

This software is being distributed under the Creative Common's Attribution 3.0 Unported licence (CC BY 3.0)
http://creativecommons.org/licenses/by/3.0/

You are not allowed to use this source code for pirate purposes.

This software is provided "as-is" and it comes with no warranties.

*/


package name.giacomofurlan.udpmessenger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public abstract class UDPMessenger {
	protected static String DEBUG_TAG = "UDPMessenger";
	protected static final Integer BUFFER_SIZE = 4096;
	
	protected String TAG;
	protected int MULTICAST_PORT;
	
	private WifiManager wim;
	
	// the standard mDNS multicast address and port number
    private static final byte[] MDNS_ADDR =
        new byte[] {(byte) 224,(byte) 5,(byte) 6,(byte) 7};
	
	private boolean receiveMessages = false;
	
	protected Context context;
	private MulticastSocket socket;
	
	protected abstract Runnable getIncomingMessageAnalyseRunnable();
	private final Handler incomingMessageHandler;
	protected Message incomingMessage;
	private Thread receiverThread;
	private NetworkInterface networkInterface;
	 private InetAddress groupAddress;
	 
	/**
	 * Class constructor
	 * @param context the application's context
	 * @param tag a valid string, used to filter the UDP broadcast messages (in and out). It can't be null or 0-characters long.
	 * @param multicastPort the port to multicast to. Must be between 1025 and 49151 (inclusive)
	 * @param connectionPort the port to get the connection back. Must be between 1025 and 49151
	 */
	public UDPMessenger(Context context, String tag, int multicastPort) throws IllegalArgumentException {
		if(context == null || tag == null || tag.length() == 0 ||
			multicastPort <= 1024 || multicastPort > 49151)
			throw new IllegalArgumentException();
		
		this.context = context.getApplicationContext();
		TAG = tag;
		MULTICAST_PORT = multicastPort;
		
		incomingMessageHandler = new Handler(Looper.getMainLooper());
	}
	
	/**
	 * Sends a broadcast message (TAG EPOCH_TIME message). Opens a new socket in case it's closed.
	 * @param message the message to send (multicast). It can't be null or 0-characters long.
	 * @return
	 * @throws IllegalArgumentException
	 */
	public boolean sendMessage(String message) throws IllegalArgumentException {
		if(message == null || message.length() == 0)
			throw new IllegalArgumentException();
		
		// Check for WiFi connectivity
		ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo mWifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		
		if(mWifi == null || !mWifi.isConnected())
		{
			Log.d(DEBUG_TAG, "Sorry! You need to be in a WiFi network in order to send UDP multicast packets. Aborting.");
			return false;
		}
		
		// Check for IP address
		WifiManager wim = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
		int ip = wim.getConnectionInfo().getIpAddress();
		
		// Create the send socket
		if(socket == null) {
			try {
				groupAddress = InetAddress.getByAddress(MDNS_ADDR);
				
				socket = new MulticastSocket(MULTICAST_PORT);
				socket.setTimeToLive(3);
				socket.setReuseAddress(true);
				socket.setNetworkInterface(getFirstWifiOrEthernetInterface());
				socket.joinGroup(groupAddress);
			} catch (Exception e) {
				Log.d(DEBUG_TAG, "There was a problem creating the sending socket. Aborting.");
				e.printStackTrace();
				return false;
			}
		}
		
		// Build the packet
		DatagramPacket packet;
		Message msg = new Message(TAG, message);
		byte data[] = msg.toString().getBytes();
		
		try {
			packet = new DatagramPacket(data, data.length, InetAddress.getByAddress(MDNS_ADDR), MULTICAST_PORT);
		} catch (UnknownHostException e) {
			Log.d(DEBUG_TAG, "It seems that " + ipToString(ip, true) + " is not a valid ip! Aborting.");
			e.printStackTrace();
			return false;
		}
		
		try {
			socket.send(packet);
			Log.d(DEBUG_TAG,"Message sent!");
		} catch (IOException e) {
			Log.d(DEBUG_TAG, "There was an error sending the UDP packet. Aborted.");
			e.printStackTrace();
			return false;
		}
		
		return true;
	}
	
	public void startMessageReceiver() {
		Runnable receiver = new Runnable() {
			@Override
			public void run() {
				wim = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
				if(wim != null) {
					MulticastLock mcLock = wim.createMulticastLock(TAG);
					mcLock.acquire();
				}
				
				byte[] buffer = new byte[BUFFER_SIZE];
				DatagramPacket rPacket = new DatagramPacket(buffer, buffer.length);
				MulticastSocket rSocket;
				
				try {
					groupAddress = InetAddress.getByAddress(MDNS_ADDR);
					
					rSocket = new MulticastSocket(MULTICAST_PORT);
					rSocket.setTimeToLive(3);
					rSocket.setReuseAddress(true);
					rSocket.setNetworkInterface(getFirstWifiOrEthernetInterface());
					rSocket.joinGroup(groupAddress);

				} catch (IOException e) {
					Log.d(DEBUG_TAG, "Impossible to create a new MulticastSocket on port " + MULTICAST_PORT);
					e.printStackTrace();
					return;
				}
				
				while(receiveMessages) {
					try {
						Log.d(DEBUG_TAG,"Recieving!");
						rSocket.receive(rPacket);
					} catch (IOException e1) {
						Log.d(DEBUG_TAG, "There was a problem receiving the incoming message.");
						e1.printStackTrace();
						continue;
					}
					
					if(!receiveMessages)
						break;
					
					byte data[] = rPacket.getData();
					int i;
					for(i = 0; i < data.length; i++)
					{
						if(data[i] == '\0')
							break;
					}
					
					String messageText;
					
					try {
						messageText = new String(data, 0, i, "UTF-8");
						Log.d(DEBUG_TAG,"Message text is " +messageText);
					} catch (UnsupportedEncodingException e) {
						Log.d(DEBUG_TAG, "UTF-8 encoding is not supported. Can't receive the incoming message.");
						e.printStackTrace();
						continue;
					}
					
					try {
						incomingMessage = new Message(messageText, rPacket.getAddress());
					} catch (IllegalArgumentException ex) {
						Log.d(DEBUG_TAG, "There was a problem processing the message: " + messageText);
						ex.printStackTrace();
						continue;
					}
					
					incomingMessageHandler.post(getIncomingMessageAnalyseRunnable());
				}
				
				if (receiveMessages == false) {
					rSocket.close();
				}
			}
			
		};
		
		receiveMessages = true;
		if(receiverThread == null)
			receiverThread = new Thread(receiver);
		
		if(!receiverThread.isAlive())
			receiverThread.start();
	}
	
	public void stopMessageReceiver() {
		receiveMessages = false;
	}
	
	public static class NetInfoException extends Exception {
        private static final long serialVersionUID = 5543786811674326615L;
        public NetInfoException() {}
        public NetInfoException(String message) {
            super(message);
        }
        public NetInfoException(Throwable e) {
            super(e);
        }
        public NetInfoException(String message, Throwable e) {
            super(message, e);
        }
    }
	
    public List<InterfaceInfo> getNetworkInformation() throws NetInfoException {
        List<InterfaceInfo> interfaceList = new ArrayList<InterfaceInfo>();
        
        InetAddress wifiAddress = null;
        InetAddress reversedWifiAddress = null;
        if (wim.isWifiEnabled()) {
            // get the ip address of the wifi interface
            int rawAddress = wim.getConnectionInfo().getIpAddress();
            try {
                wifiAddress = InetAddress.getByAddress(new byte[] {
                    (byte) ((rawAddress >> 0) & 0xFF),
                    (byte) ((rawAddress >> 8) & 0xFF),
                    (byte) ((rawAddress >> 16) & 0xFF),
                    (byte) ((rawAddress >> 24) & 0xFF),
                });
                // It's unclear how to interpret the byte order
                // of the WifiInfo.getIpAddress() int value, so
                // we also compare with the reverse order.  The
                // result is probably consistent with ByteOrder.nativeOrder(),
                // but we don't know for certain since there's no documentation.
                reversedWifiAddress = InetAddress.getByAddress(new byte[] {
                    (byte) ((rawAddress >> 24) & 0xFF),
                    (byte) ((rawAddress >> 16) & 0xFF),
                    (byte) ((rawAddress >> 8) & 0xFF),
                    (byte) ((rawAddress >> 0) & 0xFF),
                });
            } catch (UnknownHostException e) {
                throw new NetInfoException("problem retreiving wifi ip address", e);
            }
        }
        
        InetAddress localhost;
        try {
            localhost = InetAddress.getLocalHost();
        } catch (Exception e) {
            throw new NetInfoException("cannot determine the localhost address", e);
        }

        // get a list of all network interfaces
        Enumeration<NetworkInterface> networkInterfaces;
        try {
            networkInterfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            throw new NetInfoException("problem getting net interfaces", e);
        }

        // find the wifi network interface based on the ip address
        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            int flags = 0;
            Enumeration<InetAddress> addressEnum = networkInterface.getInetAddresses();
            List<InetAddress> addresses = new ArrayList<InetAddress>();
            while (addressEnum.hasMoreElements()) {
                InetAddress address = addressEnum.nextElement();

                // check for localhost
                if (address.equals(localhost)) {
                    flags |= InterfaceInfo.NET_LOCALHOST;
                }
                
                // check for wifi
                if ( (wifiAddress != null) &&
                     (reversedWifiAddress != null) &&
                     (address.equals(wifiAddress) || address.equals(reversedWifiAddress))
                ) {
                    flags |= InterfaceInfo.NET_WIFI;
                }
                
                addresses.add(address);
            }
            
            // assume an eth* interface that isn't wifi is wired ethernet.
            if (((flags & InterfaceInfo.NET_WIFI)==0) && networkInterface.getName().startsWith("eth")) {
                flags |= InterfaceInfo.NET_ETHERNET;
            }

            interfaceList.add(new InterfaceInfo(networkInterface, addresses, flags));
        }
        return interfaceList;
    }
	
	public static class InterfaceInfo {
        private NetworkInterface networkInterface;
        private List<InetAddress> addresses;
        private int flags = 0;
        public static final int NET_ETHERNET  = 1<<2;
        public static final int NET_LOCALHOST = 1<<0;
        public static final int NET_OTHER     = 1<<3;
        public static final int NET_WIFI      = 1<<1;
        
        public InterfaceInfo(NetworkInterface networkInterface, List<InetAddress> addresses, int flags) {
            this.networkInterface = networkInterface;
            this.addresses = addresses;
            this.flags = flags;
        }
        
        public NetworkInterface getNetworkInterface() {
            return networkInterface;
        }
        public List<InetAddress> getAddresses() {
            return addresses;
        }
        public int getFlags() {
            return flags;
        }
        public boolean isLocalhost() {
            return ((flags & NET_LOCALHOST) != 0);
        }
        public boolean isWifi() {
            return ((flags & NET_WIFI) != 0);
        }
        public boolean isEthernet() {
            return ((flags & NET_ETHERNET) != 0);
        }
        
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("interface "+networkInterface+" :");
            if ((flags & NET_LOCALHOST)!=0) { sb.append(" localhost"); }
            if ((flags & NET_WIFI)!=0) { sb.append(" wifi"); }
            if ((flags & NET_ETHERNET)!=0) { sb.append(" ethernet"); }
            sb.append("\n");
            for (InetAddress address : addresses) {
                sb.append("  addr "+address.toString()+"\n");
            }
            return sb.toString();
        }
        
    }
	
	public NetworkInterface getFirstWifiOrEthernetInterface() {
        try {
            for (InterfaceInfo ii : getNetworkInformation()) {
                if (ii.isWifi() || ii.isEthernet()) {
                    return ii.getNetworkInterface();
                }
            }
        } catch (NetInfoException e) {
            Log.w(TAG, "cannot find a wifi/ethernet interface");
        }
        return null;
    }
	
	public static String ipToString(int ip, boolean broadcast) {
		String result = new String();
		
		Integer[] address = new Integer[4];
		for(int i = 0; i < 4; i++)
			address[i] = (ip >> 8*i) & 0xFF;
		for(int i = 0; i < 4; i++) {
			if(i != 3)
				result = result.concat(address[i]+".");
			else result = result.concat("255.");
		}
		return result.substring(0, result.length() - 2);
	}
}

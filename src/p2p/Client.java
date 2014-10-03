package p2p;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import thread.ServerWaiter;
import net.freehaven.tor.control.TorControlConnection;


/**
 *
 *
 * @author Simeon Andreev
 *
 */
public class Client {


	/**
	 * A response enumeration for the exit method.
	 * 		* SUCCESS indicates a successfully closed server socket.
	 * 		* FAIL    indicates a failure when closing the server socket.
	 */
	public enum ExitResponse {
		SUCCESS,
		FAIL
	}

	/**
	 * A response enumeration for the connect method.
	 * 		* SUCCESS indicates a successfully opened socket connection.
	 * 		* TIMEOUT indicates a reached timeout upon opening the socket connection.
	 * 		* OPEN    indicates that a socket connection is already open.
	 * 		* FAIL    indicates a failure when opening the socket connection.
	 */
	public enum ConnectResponse {
		SUCCESS,
		TIMEOUT,
		OPEN,
		FAIL
	}

	/**
	 * A response enumeration for the disconnect method.
	 * 		* SUCCESS indicates a successfully closed socket connection.
	 * 		* CLOSED  indicates that the socket connection is not open.
	 * 		* FAIL    indicates a failure when closing the socket connection.
	 */
	public enum DisconnectResponse {
		SUCCESS,
		CLOSED,
		FAIL
	}

	/**
	 * A response enumeration for the send method.
	 * 		* SUCCESS indicates a successfully sent message.
	 * 		* CLOSED  indicates that the socket connection is not open.
	 * 		* FAIL    indicates a failure when sending the message.
	 */
	public enum SendResponse {
		SUCCESS,
		CLOSED,
		FAIL
	}


	/** The logger for this class. */
	private Logger logger = Logger.getLogger(Constants.clientlogger);

	/** The parameters of this client. */
	private final Configuration configuration;
	/** The server socket receiving messages. */
	private final ServerWaiter waiter;
	/** The open socket connections to Tor hidden services. */
	private final HashMap<String, Socket> sockets = new HashMap<String, Socket>();


	/**
	 * Constructor method.
	 *
	 * @param configuration The parameters of this client.
	 * @param listener The listener that should receive notifications upon received messages.
	 * @throws IOException Throws an IOException if unable to open a server socket on the specified port.
	 *
	 * @see Configuration
	 */
	public Client(Configuration configuration, Listener listener) throws IOException {
		this.configuration = configuration;
		waiter = new ServerWaiter(listener, configuration.getHiddenServicePort());
		waiter.start();
		logger.log(Level.INFO, "Client object created.");
	}


	/**
	 * Closes the server socket and any open receiving socket connections. Will not close connections open for sending.
	 *
	 * @return  ExitResponse.FAIL    if an IOException occurred while closing the server socket.
	 * 			ExitResponse.SUCCESS if the server socket was closed.
	 */
	public ExitResponse exit() {
		logger.log(Level.INFO, "Client exiting.");

		try {
			// Stop the server waiter and all socket waiters of open socket connections.
			logger.log(Level.INFO, "Stopping server waiter.");
			waiter.stop();
		} catch (IOException e) {
			logger.log(Level.INFO, "Received IOException while closing the server socket: " + e.getMessage());
			return ExitResponse.FAIL;
		}

		logger.log(Level.INFO, "Stopped server waiter.");
		return ExitResponse.SUCCESS;
	}


	/**
	 * Returns the local Tor hidden service identifier. Will create a hidden service if none is present by using JTorCtl.
	 *
	 * @param fresh If true, will always generate a new identifier, even if one is already present.
	 * @return The hidden service identifier.
	 * @throws IOException Throws an exception when unable to read the Tor hostname file, or when unable to connect to Tors control socket.
	 */
	public String identifier(boolean fresh) throws IOException {
		logger.log(Level.INFO, (fresh ? "Fetching a fresh" : "Attempting to reuse (if present) the") + " identifier.");

		File hostname = new File(configuration.getHiddenServiceDirectory() + File.separator + Constants.filename);

		// If a fresh identifier is requested, delete the hidden service directory.
		if (fresh) {
			logger.log(Level.INFO, "Deleting hostname file.");
			File hiddenservice = new File(configuration.getHiddenServiceDirectory());
			File privatekey = new File(configuration.getHiddenServiceDirectory() + File.separator + Constants.prkey);

			boolean success = hostname.delete();
			logger.log(Level.INFO, "Deleted hostname file: " + (success ? "yes" : "no"));
			success = privatekey.delete();
			logger.log(Level.INFO, "Deleted private key file: " + (success ? "yes" : "no"));
			success = hiddenservice.delete();
			logger.log(Level.INFO, "Deleted hidden service directory: " + (success ? "yes" : "no"));
		}

		// If the Tor hostname file does not exist in the Tor hidden service directory, create a hidden service with JTorCtl.
		if (fresh || !hostname.exists()) {
			logger.log(Level.INFO, "Creating hidden service.");
			createHiddenService();
		}

		// Read the content of the Tor hidden service hostname file.
		return readIdentifier(hostname);
	}



	/**
	 * Sends a message to an open socket at the specified Tor hidden service identifier.
	 *
	 * @param identifier The identifier of the Tor hidden service.
	 * @param message The message to be sent.
	 * @return  ConnectResponse.CLOSED  if a socket connection is not open for the identifier.
	 * 			ConnectResponse.FAIL    if an IOException occured while sending the message to the identifier.
	 * 			ConnectResponse.SUCCESS if the message was sent to the identifier.
	 */
	public SendResponse send(String identifier, String message) {
		logger.log(Level.INFO, "Sending a message to identifier " + identifier + " on port " + configuration.getHiddenServicePort() + ".");
		// Check if a socket is open for the specified identifier.
		if (!sockets.containsKey(identifier)) {
			logger.log(Level.INFO, "No socket is open for identifier: " + identifier);
			return SendResponse.CLOSED;
		}

		// Get the socket for the specified identifier.
		logger.log(Level.INFO, "Getting socket from map.");
		Socket socket = sockets.get(identifier);

		try {
			// Send the message and close the socket connection.
			logger.log(Level.INFO, "Sending message: " + message);
			socket.getOutputStream().write(message.getBytes());
		} catch (IOException e) {
			// IOException when getting socket output stream or sending bytes via the stream.
			logger.log(Level.INFO, "Received an IOException while sending a message to identifier = " + identifier + ": " + e.getMessage());
			return SendResponse.FAIL;
		}

		logger.log(Level.INFO, "Sending done.");
		return SendResponse.SUCCESS;
	}

	/**
	 * Opens a socket connection to the specified Tor hidden service.
	 *
	 * @param identifier The Tor hidden service identifier of the destination.
	 * @param port The port number of the destination Tor hidden service.
	 * @return  ConnectResponse.OPEN    if a socket connection is already open for the identifier.
	 * 			ConnectResponse.FAIL    if an IOException occured while opening the socket connection for the identifier.
	 * 			ConnectResponse.SUCCESS if a socket connection is now open for the identifier.
	 * 			ConnectResponse.TIMEOUT if the socket connection timeout was reached upon opening the connection to the identifier.
	 */
	public ConnectResponse connect(String identifier, int port) {
		logger.log(Level.INFO, "Opening a socket for identifier " + identifier + " on port " + port + ".");

		// Check if a socket is already open to the given identifier.
		if (sockets.containsKey(identifier)) {
			logger.log(Level.INFO, "A socket is already open for the given identifier.");
			return ConnectResponse.OPEN;
		}

		// Create a proxy by using the Tor SOCKS proxy.
		InetSocketAddress midpoint = new InetSocketAddress(Constants.localhost, configuration.getTorSocksProxyPort());
		logger.log(Level.INFO, "Creating proxy on " + Constants.localhost + ":" + configuration.getTorSocksProxyPort());
		Proxy proxy = new Proxy(Proxy.Type.SOCKS, midpoint);
		logger.log(Level.INFO, "Creating a socket using the proxy.");
		Socket socket = new Socket(proxy);
		InetSocketAddress destination = new InetSocketAddress(identifier, port);

		ConnectResponse response = ConnectResponse.SUCCESS;

		try {
			// Bind the socket to the destination Tor hidden service.
			logger.log(Level.INFO, "Binding destination address to the socket.");
			socket.connect(destination, configuration.getSocketTimeout());
			logger.log(Level.INFO, "Adding socket to open sockets.");
			sockets.put(identifier,  socket);
			logger.log(Level.INFO, "Opened socket for identifier: " + identifier);
		} catch (SocketTimeoutException e) {
			// Socket connection timeout reached.
			logger.log(Level.INFO, "Timeout reached for connection to identifier: " + identifier);
			response = ConnectResponse.TIMEOUT;
		} catch (IOException e) {
			logger.log(Level.INFO, "Received an IOException while connecting the socket to identifier = " + identifier + ": " + e.getMessage());
			response = ConnectResponse.FAIL;
		}

		return response;
	}

	/**
	 * Closes the socket connection for the specified Tor hidden service identifier.
	 *
	 * @param identifier The identifier of the Tor hidden service.
	 * @return  DisconnectResponse.CLOSED  if no socket connection for the identifier.
	 * 			DisconnectResponse.FAIL    if an IOException occured while closing the socket connection for the identifier.
	 * 			DisconnectResponse.SUCCESS if the socket connection for the identifier was closed.
	 */
	public DisconnectResponse disconnect(String identifier) {
		logger.log(Level.INFO, "Closing socket for identifier: " + identifier);

		// Check if the socket is open.
		if (!sockets.containsKey(identifier)) {
			logger.log(Level.INFO, "Socket not open.");
			return DisconnectResponse.CLOSED;
		}

		try {
			// Get the socket from the map.
			Socket socket = sockets.get(identifier);
			logger.log(Level.INFO, "Closing socket.");
			// Close the socket.
			socket.close();
			sockets.remove(identifier);
		} catch (IOException e) {
			// Closing the socket failed due to an IOException.
			logger.log(Level.INFO, "Received an IOException while closing the socket for identifier = " + identifier + ": " + e.getMessage());
			return DisconnectResponse.FAIL;
		}

		return DisconnectResponse.SUCCESS;
	}

	/**
	 * Creates a Tor hidden service by connecting to the Tor control port and invoking JTorCtl.
	 *
	 * @throws IOException Thrown when the Tor control socket is not reachable, or if the Tor authentication fails.
	 */
	private void createHiddenService() throws IOException {
		logger.log(Level.INFO, "Creating hidden service.");
		logger.log(Level.INFO, "Opening socket on " + Constants.localhost + ":" + configuration.getTorControlPort() + " to control Tor.");
		// Connect to the Tor control port.
		Socket s = new Socket(Constants.localhost, configuration.getTorControlPort());
		logger.log(Level.INFO, "Fetching JTorCtl connection.");
		TorControlConnection conn = TorControlConnection.getConnection(s);
		logger.log(Level.INFO, "Authenticating the connection.");
		// Authenticate the connection.
		conn.authenticate(configuration.getAuthenticationBytes());

		// TODO: this needs to change for multiple applications
		// Set the properties for the hidden service configuration.
		String[] properties = new String[] {
			Constants.hsdirkeyword + " " + configuration.getHiddenServiceDirectory(),
			Constants.hsportkeyword + " " + configuration.getHiddenServicePort() + " " + Constants.localhost + ":" + configuration.getHiddenServicePort()
		};
		logger.log(Level.INFO, "Setting configuration:\n" + properties[0] + "\n" + properties[1]);
		conn.setConf(Arrays.asList(properties));

		logger.log(Level.INFO, "Created hidden service.");
	}

	/**
	 * Reads the Tor hidden service identifier from the hostname file.
	 *
	 * @param hostname The file from which the Tor hidden service identifier should be read.
	 * @return The string identifier representing the Tor hidden service identifier.
	 * @throws IOException Thrown when unable to read the Tor hidden service hostname file.
	 */
	private String readIdentifier(File hostname) throws IOException {
		logger.log(Level.INFO, "Reading identifier from file: " + hostname);
		FileInputStream stream = new FileInputStream(hostname);
		InputStreamReader reader = new InputStreamReader(stream);
		BufferedReader buffer = new BufferedReader(reader);

		logger.log(Level.INFO, "Reading line.");
		String identifier = buffer.readLine();

		logger.log(Level.INFO, "Closing file stream.");
		buffer.close();

		logger.log(Level.INFO, "Read identifier: " + identifier);
		return identifier;
	}

}
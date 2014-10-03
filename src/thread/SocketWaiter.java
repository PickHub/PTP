package thread;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.logging.Level;

import p2p.Constants;
import p2p.Listener;


/**
 * A waiter attending a socket. Will wait for received messages on the socket and notify a listener.
 *
 * @author Simeon Andreev
 *
 * @see Waiter
 */
public class SocketWaiter extends Waiter {

	/** The socket attended by this waiter. */
	private final Socket socket;


	/**
	 * Constructor method.
	 *
	 * @param socket The socket which this waiter should attend.
	 * @param listener The listener that should be notified of received messages on the socket.
	 */
	public SocketWaiter(Socket socket, Listener listener) {
		super(listener);
		this.socket = socket;
		logger.log(Level.INFO, "ServerWaiter object created.");
	}


	/**
	 * @see Runnable
	 */
	@Override
	public void run() {
		logger.log(Level.INFO, "Socket thread started.");
		try {
			logger.log(Level.INFO, "Socket thread entering its execution loop.");
			// Block on the socket input stream, notify the listener when a message is read.
			while (true) {
				// Create a buffer for the message.
				byte[] buffer = new byte[Constants.maxlength];
				logger.log(Level.INFO, "Socket thread waiting on a message.");
				// Block on the input stream, waiting for a message.
				final int read = socket.getInputStream().read(buffer);
				// Reached the end of the input stream if -1 is read.
				if (read == -1) {
					logger.log(Level.INFO, "Socket thread reached the end of the socket input stream.");
					break;
				}
				logger.log(Level.INFO, "Socket thread received a message with length " + read + " bytes.");
				// Copy the read bytes.
				byte[] message = new byte[read];
				System.arraycopy(buffer, 0, message, 0, message.length);
				logger.log(Level.INFO, "Socket thread notifying listener of the received message.");
				// Notify the listener that a message is received.
				listener.receive(message);
				logger.log(Level.INFO, "Listener notified.");
			}
		} catch (SocketException e)	{
			logger.log(Level.WARNING, "Socket thread received a SocketException during a socket operation: " + e.getMessage());
		} catch (IOException e) {
			logger.log(Level.WARNING, "Socket thread received an IOException during a socket operation: " + e.getMessage());
		}
		logger.log(Level.INFO, "Socket thread exiting run method.");
	}

	/**
	 * @see Waiter
	 */
	public void stop() throws IOException {
		// Stop the waiter only if its running.
		if (socket.isClosed()) return;

		// Close the socket, so that the waiter exits the execution loop.
		logger.log(Level.INFO, "Closing the socket input stream.");
		socket.getInputStream().close();
		logger.log(Level.INFO, "Closing the socket.");
		socket.close();
		logger.log(Level.INFO, "Socket thread stopped.");
	}

}
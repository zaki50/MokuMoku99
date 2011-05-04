
package org.zakky.mokumoku.server;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public class StatusServerService extends Service {

    public static final String TAG = StatusServerService.class.getSimpleName();

    private static final String HOST = "::";

    private static final int PORT = 2525;

    private static final int BACKLOG = 10;

    // private int mStartId = -1;

    private ServerThread mServerThread;

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand()");

        super.onStartCommand(intent, flags, startId);

        // mStartId = startId;

        try {
            mServerThread = new ServerThread(HOST, PORT, BACKLOG);
            mServerThread.start();

        } catch (IOException e) {
            Log.e(TAG, "failed to bind server socket to [" + HOST + "]:" + PORT);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");

        final ServerThread serverThread = mServerThread;
        if (serverThread != null) {
            serverThread.quit();
        }
    }

}

final class ServerThread extends Thread {

    private volatile boolean mQuit;

    private final Selector mSelector;

    private final List<ClientStatus> mClients;

    private final ByteBuffer mReceiveBuffer;

    public ServerThread(String host, int port, int backlog) throws IOException {
        super();
        mQuit = false;
        mClients = createInitialStatusList();
        mSelector = createSocketSelector(host, port, backlog);
        mReceiveBuffer = ByteBuffer.allocate(4096);
    }

    private static List<ClientStatus> createInitialStatusList() {
        final List<ClientStatus> statusList = new ArrayList<ClientStatus>();
        for (int i = 0; i < 8; i++) {
            statusList.add(new ClientStatus());
        }
        return statusList;
    }

    private static Selector createSocketSelector(String host, int port, int backlog)
            throws IOException {
        final Selector selector = SelectorProvider.provider().openSelector();

        final ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.configureBlocking(false);
        serverSocket.socket().setReceiveBufferSize(8192);
        serverSocket.socket().setReuseAddress(true);

        final SocketAddress localSockAddr = new InetSocketAddress(Inet6Address.getByName(host),
                port);
        serverSocket.socket().bind(localSockAddr, backlog);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);

        return selector;
    }

    @Override
    public void run() {
        super.run();
        // publishProgress("waiting client");
        try {
            while (!mQuit) {
                mSelector.select();
                for (SelectionKey key : mSelector.selectedKeys()) {
                    if (key.isValid() && key.isAcceptable()) {
                        handleAcceptable(key);
                    }
                    if (key.isValid() && key.isWritable()) {
                        handleWritable(key);
                    }
                    if (key.isValid() && key.isReadable()) {
                        handleReadable(key);
                    }
                }
            }
        } catch (IOException e) {
            Log.e(StatusServerService.TAG, "failed to select", e);
        } finally {
            try {
                mSelector.close();
            } catch (IOException e) {
                Log.e(StatusServerService.TAG, "failed to close Selector", e);
            }
            Log.i(StatusServerService.TAG, "server thread finised.");
        }
    }

    public void quit() {
        mQuit = true;
        interrupt();
    }

    private void handleAcceptable(SelectionKey key) {
        final ServerSocketChannel s = (ServerSocketChannel) key.channel();
        try {
            final SocketChannel clientSocket = s.accept();
            if (clientSocket == null) {
                return;
            }
            clientSocket.configureBlocking(false);

            clientSocket.register(mSelector, SelectionKey.OP_READ);
        } catch (ClosedChannelException e) {
            Log.i(StatusServerService.TAG, "server socket closed.", e);
        } catch (IOException e) {
            Log.i(StatusServerService.TAG, "failed to accept connection.", e);
        }
    }

    private void handleWritable(SelectionKey key) {
        final Object attachment = key.attachment();
        if (attachment == null) {
            key.cancel();
            return;
        }
        final ByteBuffer data = (ByteBuffer) attachment;
        if (0 < data.remaining()) {
            final SocketChannel socket = (SocketChannel) key.channel();
            try {
                socket.write(data);
            } catch (IOException e) {
                Log.e(StatusServerService.TAG, "failed to send data.", e);
            }
        }
        if (data.remaining() == 0) {
            key.attach(null);
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void handleReadable(SelectionKey key) {
        final SocketChannel s = (SocketChannel) key.channel();
        mReceiveBuffer.clear();
        try {
            s.read(mReceiveBuffer); // TODO 一回で読めるとは限らない
            mReceiveBuffer.flip();
            if (mReceiveBuffer.remaining() < 8) {
                key.cancel();
                s.close();
                return;
            }
            mReceiveBuffer.order(ByteOrder.BIG_ENDIAN);
            final int clientId = mReceiveBuffer.getInt();
            final int length = mReceiveBuffer.getInt();
            updateClientStatus(clientId, length, mReceiveBuffer);
            sendClientStatus(key, s);
        } catch (IOException e) {
            Log.i(StatusServerService.TAG, "failed to read data from client.", e);
        }
    }

    /**
     * 
     * @param clientId
     * @param length
     * @param buffer
     * @return 更新できた場合は {@code true}、上限に達していて破棄された場合は
     * {@code false}。
     */
    private boolean updateClientStatus(int clientId, int length, ByteBuffer buffer) {
        if (length != buffer.remaining()) {
            // TODO 丸ごと読めなかった場合に対処する
            throw new RuntimeException("invalid length: " + length + "(header) "
                    + buffer.remaining() + "(actual)");
        }

        int openStatusIndex = -1;
        int prevStatusIndex = -1;
        int index = 0;
        final ListIterator<ClientStatus> iterator = mClients.listIterator();
        while (iterator.hasNext()) {
            final ClientStatus status = iterator.next();
            if (status.getId() == clientId) {
                // found
                prevStatusIndex = index;
                break;
            }
            if (openStatusIndex == -1 && status.getId() == ClientStatus.INVALID_ID) {
                openStatusIndex = index;
            }
            index++;
        }
        final boolean isNew = (prevStatusIndex == -1);
        if (isNew) {
            // new client
            if (openStatusIndex == -1) {
                // クライアント数上限
                return false;
            }
            // 新規クライアントなので、openなところを使用する。
            prevStatusIndex = openStatusIndex;
        }

        if (isNew || length != 0) {
            final ClientStatus clientStatus = new ClientStatus(clientId, buffer.array(),
                    buffer.arrayOffset() + buffer.position(), buffer.remaining());
            mClients.set(prevStatusIndex, clientStatus);
        }
        return true;
    }

    private void sendClientStatus(SelectionKey key, SocketChannel socket) {
        int totalLength = 4; // length(4)
        for (ClientStatus client : mClients) {
            totalLength += 8; // id(4) and length(4)
            totalLength += client.getPayload().length;
        }

        final ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(totalLength - 4);
        for (ClientStatus client : mClients) {
            buffer.putInt(client.getId());
            buffer.putInt(client.getPayload().length);
            buffer.put(client.getPayload());
        }
        buffer.flip();
        key.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
        key.attach(buffer);
    }
}

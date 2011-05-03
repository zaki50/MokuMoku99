
package org.zakky.mokumoku;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import android.app.Activity;
import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;

public class InfoActivity extends Activity {
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }

    private AsyncTask<Void, String, Void> mServer;

    @Override
    protected void onStart() {
        super.onStart();

        if (mServer != null) {
            mServer.cancel(true);
        }
        mServer = new AsyncTask<Void, String, Void>() {
            private final ProgressDialog mProgress = new ProgressDialog(InfoActivity.this);

            private final List<ClientStatus> mClients = new ArrayList<ClientStatus>();

            {
                for (int i = 0; i < 8; i++) {
                    mClients.add(new ClientStatus());
                }
            }

            @Override
            protected void onPreExecute() {
                super.onPreExecute();

                mProgress.setTitle("Running MokuMoku Server");
                mProgress.show();
            }

            @Override
            protected Void doInBackground(Void... params) {
                try {
                    final Selector selector = SelectorProvider.provider().openSelector();

                    final ServerSocketChannel serverSocket = ServerSocketChannel.open();
                    serverSocket.configureBlocking(false);

                    final SocketAddress localSockAddr = new InetSocketAddress(
                            InetAddress.getByName(null), 2525);
                    serverSocket.socket().bind(localSockAddr, 10);

                    serverSocket.register(selector, SelectionKey.OP_ACCEPT);

                    publishProgress("waiting client");
                    while (true) {
                        final ByteBuffer buffer = ByteBuffer.allocate(4096);
                        selector.select();
                        for (SelectionKey k : selector.selectedKeys()) {
                            if (k.isAcceptable()) {
                                final ServerSocketChannel s = (ServerSocketChannel) k.channel();
                                final SocketChannel clientSocket = s.accept();

                                publishProgress("waiting client");

                                clientSocket.register(selector, SelectionKey.OP_READ);
                            }
                            if (k.isReadable()) {
                                final SocketChannel s = (SocketChannel) k.channel();
                                buffer.clear();
                                s.read(buffer);
                                buffer.flip();
                                buffer.order(ByteOrder.BIG_ENDIAN);
                                final int clientId = buffer.getInt();
                                final int length = buffer.getInt();
                                if (0 < length) {
                                    updateClientStatus(clientId, length, buffer);
                                }
                                sendClientStatus(s);
                            }
                        }
                    }
                } catch (UnknownHostException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
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

                final ClientStatus clientStatus = new ClientStatus(clientId, buffer.array(),
                        buffer.arrayOffset() + buffer.position(), buffer.remaining());

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
                if (prevStatusIndex == -1) {
                    // new client
                    if (openStatusIndex == -1) {
                        // クライアント数上限
                        return false;
                    }
                    // 新規クライアントなので、openなところを使用する。
                    prevStatusIndex = openStatusIndex;
                }

                mClients.set(prevStatusIndex, clientStatus);
                return true;
            }

            private void sendClientStatus(SocketChannel socket) {
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
            }

            @Override
            protected void onProgressUpdate(String... values) {
                super.onProgressUpdate(values);
                mProgress.setMessage(values[0]);
            }

            @Override
            protected void onCancelled() {
                super.onCancelled();
                mProgress.dismiss();
            }

            @Override
            protected void onPostExecute(Void result) {
                super.onPostExecute(result);
                mProgress.dismiss();
            }

        };
        mServer.execute();
    }
}

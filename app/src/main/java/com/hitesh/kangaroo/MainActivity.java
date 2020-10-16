package com.hitesh.kangaroo;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Stack;

public class MainActivity extends AppCompatActivity {
    public static final Integer HEADER = 64;
    public static final String DISCONNECT = "  ";
    private Context context;
    private TextView ipFormat;
    private EditText ip, port;
    private AlertDialog dialog;
    private Socket socket;
    private BufferedOutputStream writer;
    private BufferedInputStream reader;
    private ClipboardManager clipboardManager;
    private String ipFormatStr;
    private String ipStr;
    private Integer portInt;
    private Boolean connected = false;
    private String lastReceived;
    int message;

    Thread dataReceiver = new Thread(new Runnable() {
        @Override
        public void run() {
            while (connected) {
                byte[] msgLength = new byte[HEADER];
                try {
                    int read = reader.read(msgLength);
                    if (read == -1) {
                        disconnect();
                        break;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                StringBuilder message = new StringBuilder(new String(msgLength, StandardCharsets.UTF_8));
                if (message.toString().trim().equals(""))
                    continue;
                int len = Integer.parseInt(message.toString().trim());
                byte[] msg = new byte[len];
                message = new StringBuilder();
                int read = 0;
                while (true) {
                    try {
                        int x = reader.read(msg, read, len - read);
                        read += x;
                        if (read == len)
                            break;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                message.append(new String(msg, StandardCharsets.UTF_8));
                lastReceived = message.toString();
                if (message.toString().equals(DISCONNECT)) {
                    disconnect();
                    break;
                } else {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("text", message.toString()));
                }
                SystemClock.sleep(100);
            }
        }
    });

    private Button.OnClickListener connectClicked = new Button.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!connected) {
                socket = new Socket();
                try {
                    ipFormatStr = getIpFormat();
                    ipStr = getId();
                    portInt = getPortInt();
                } catch (Exception e) {
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                startDialog();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            SocketAddress socketAddress = new InetSocketAddress(ipFormatStr + ipStr, portInt);
                            socket.connect(socketAddress, 10000);
                            writer = new BufferedOutputStream(socket.getOutputStream());
                            reader = new BufferedInputStream(socket.getInputStream());
                            connected = true;
                            message = R.string.connected;
                            dataReceiver.start();
                        } catch (SocketTimeoutException e) {
                            message = R.string.timeout;
                        } catch (Exception s) {
                            message = R.string.error;
                        } finally {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    dismissDialog();
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                }).start();
            } else {
                Toast.makeText(context, "Connected to " + ipFormatStr + ipStr + ':' + portInt, Toast.LENGTH_LONG).show();
            }
        }
    };

    private Button.OnClickListener disconnectClicked = new Button.OnClickListener() {
        @Override
        public void onClick(View v) {
            disconnect();
        }
    };
    private ClipboardManager.OnPrimaryClipChangedListener clipChangedListener = new ClipboardManager.OnPrimaryClipChangedListener() {
        @Override
        public void onPrimaryClipChanged() {
            if (connected) {
                if (lastReceived != null) {
                    if (!lastReceived.equals(getClipText())) {
                        sendMessage(getClipText());
                    }
                } else {
                    sendMessage(getClipText());
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;
        ipFormat = findViewById(R.id.ipFormat);
        ip = findViewById(R.id.ip);
        port = findViewById(R.id.port);
        Button connect = findViewById(R.id.connect);
        Button disConnect = findViewById(R.id.disconnect);
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        connect.setOnClickListener(connectClicked);
        disConnect.setOnClickListener(disconnectClicked);
        clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
        new Thread(new Runnable() {
            @Override
            public void run() {
                getIp();
            }
        }).start();
    }

    private void sendMessage(String message) {
        try {
            StringBuilder len = new StringBuilder(Integer.toString(message.getBytes().length));
            Integer len_of_len = len.length();
            for (int i = 0; i < HEADER - len_of_len; ++i) {
                len.insert(0, ' ');
            }
            writer.write(len.toString().getBytes());
            writer.flush();
            writer.write(message.getBytes());
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void disconnect() {
        if (connected) {
            try {
                Thread disconnector = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        sendMessage(DISCONNECT);
                    }
                });
                disconnector.start();
                disconnector.join();
                connected = false;
                writer.close();
                reader.close();
                socket.close();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(context, R.string.disconnected, Toast.LENGTH_LONG).show();
                    }
                });
                ((Activity) context).finish();
            } catch (Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(context, R.string.error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    private void getIp() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            final byte[] addr = BigInteger.valueOf(wifiManager.getConnectionInfo().getIpAddress()).toByteArray();
            String hostAddress = InetAddress.getByAddress(addr).getHostAddress();
            StringBuilder str = new StringBuilder();
            Stack<Integer> stack = new Stack<>();
            int last;
            int i = 0;
            while ((last = hostAddress.indexOf('.', i)) != -1) {
                stack.add(last);
                i = stack.peek() + 1;
            }
            last = hostAddress.length();
            while (!stack.empty()) {
                str.append(hostAddress.substring(stack.peek() + 1, last)).append('.');
                last = stack.pop();
            }
            final String ipInitial = str.toString();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ipFormat.setText(ipInitial);
                }
            });
        } catch (UnknownHostException e) {
            e.printStackTrace();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, R.string.noConnection, Toast.LENGTH_LONG).show();
                    ip.setEnabled(false);
                    port.setEnabled(false);
                }
            });
        }
    }

    private String getClipText() {
        String s;
        try {
            s = Objects.requireNonNull(clipboardManager.getPrimaryClip()).getItemAt(0).coerceToText(getApplicationContext()).toString();
        } catch (NullPointerException e) {
            s = "";
        }
        return s;
    }

    private String getId() throws Exception {
        if (ip.getText().toString().equals("")) {
            throw new Exception("Invalid IP");
        }
        return ip.getText().toString().trim();
    }

    private String getIpFormat() throws Exception {
        if (ipFormat.getText().toString().equals("")) {
            throw new Exception("Connect to wifi and restart app");
        }
        return ipFormat.getText().toString().trim();
    }

    private Integer getPortInt() throws Exception {
        if (port.getText().toString().equals("")) {
            throw new Exception("Enter PORT Number");
        }
        return Integer.parseInt(port.getText().toString().trim());
    }

    private void startDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = View.inflate(context, R.layout.connecting_view, null);
        builder.setView(view)
                .setCancelable(false);
        dialog = builder.create();
        dialog.show();
    }

    private void dismissDialog() {
        dialog.dismiss();
    }

    @Override
    public void onBackPressed() {
        if (connected)
            moveTaskToBack(true);
        else
            super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }

}
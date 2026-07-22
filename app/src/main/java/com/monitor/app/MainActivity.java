package com.monitor.app;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.webrtc.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MonitorApp";
    private static final String SIGNALING_URL = "wss://signaling-server-71q2.onrender.com";

    private TextView statusText;
    private EditText roomIdInput;
    private Button connectButton;
    private Button switchCameraButton;
    private SurfaceViewRenderer remoteView;

    private String roomId = "oda1";
    private boolean connected = false;

    private EglBase eglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private WebSocketClient wsClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = (TextView) findViewById(R.id.statusText);
        roomIdInput = (EditText) findViewById(R.id.roomIdInput);
        connectButton = (Button) findViewById(R.id.connectButton);
        switchCameraButton = (Button) findViewById(R.id.switchCameraButton);
        remoteView = (SurfaceViewRenderer) findViewById(R.id.remoteView);

        eglBase = EglBase.create();
        remoteView.init(eglBase.getEglBaseContext(), null);
        remoteView.setMirror(false);

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!connected) {
                    roomId = roomIdInput.getText().toString().trim();
                    if (roomId.isEmpty()) roomId = "oda1";
                    startConnection();
                }
            }
        });

        switchCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendSwitchCamera();
            }
        });
    }

    private void startConnection() {
        connected = true;
        connectButton.setText("Bağlanıyor...");
        setStatus("WebRTC başlatılıyor...");

        initWebRTC();
        connectSignaling();
    }

    private void initWebRTC() {
        PeerConnectionFactory.InitializationOptions initOptions =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initOptions);

        VideoEncoderFactory encoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        VideoDecoderFactory decoderFactory =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }

    private List<PeerConnection.IceServer> getIceServers() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<PeerConnection.IceServer>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80")
                .setUsername("6e19a374f95004d5aa0269ac")
                .setPassword("03EFYItjIl2Lt1uv")
                .createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80?transport=tcp")
                .setUsername("6e19a374f95004d5aa0269ac")
                .setPassword("03EFYItjIl2Lt1uv")
                .createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443")
                .setUsername("6e19a374f95004d5aa0269ac")
                .setPassword("03EFYItjIl2Lt1uv")
                .createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp")
                .setUsername("6e19a374f95004d5aa0269ac")
                .setPassword("03EFYItjIl2Lt1uv")
                .createIceServer());

        return iceServers;
    }

    private void createPeerConnection() {
        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(getIceServers());

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                sendIceCandidate(candidate);
            }

            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}

            @Override
            public void onIceConnectionChange(final PeerConnection.IceConnectionState iceConnectionState) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("Bağlantı durumu: " + iceConnectionState);
                    }
                });
            }

            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override public void onAddStream(MediaStream mediaStream) {}
            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onDataChannel(DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                MediaStreamTrack track = rtpReceiver.track();
                if (track instanceof VideoTrack) {
                    final VideoTrack videoTrack = (VideoTrack) track;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            videoTrack.addSink(remoteView);
                            setStatus("Video alınıyor");
                        }
                    });
                }
            }
        });
    }

    private void connectSignaling() {
        try {
            wsClient = new WebSocketClient(new URI(SIGNALING_URL)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Sunucuya bağlandı, oda: " + roomId);
                        }
                    });
                    sendJoin();
                }

                @Override
                public void onMessage(String message) {
                    handleSignalMessage(message);
                }

                @Override
                public void onClose(int code, final String reason, boolean remote) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Bağlantı kapandı: " + reason);
                        }
                    });
                }

                @Override
                public void onError(final Exception ex) {
                    Log.e(TAG, "WebSocket hata", ex);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("Hata: " + ex.getMessage());
                        }
                    });
                }
            };
            wsClient.connect();
        } catch (Exception e) {
            Log.e(TAG, "Signaling bağlantı hatası", e);
            setStatus("Signaling bağlantı hatası");
        }
    }

    private void sendJoin() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "join");
            obj.put("room", roomId);
            obj.put("role", "monitor");
            wsClient.send(obj.toString());
        } catch (Exception e) {
            Log.e(TAG, "join gönderilemedi", e);
        }
    }

    private void sendAnswer(String description) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "answer");
            obj.put("sdp", description);
            wsClient.send(obj.toString());
        } catch (Exception e) {
            Log.e(TAG, "answer gönderilemedi", e);
        }
    }

    private void sendIceCandidate(IceCandidate candidate) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "ice-candidate");
            obj.put("candidate", candidate.sdp);
            obj.put("sdpMid", candidate.sdpMid);
            obj.put("sdpMLineIndex", candidate.sdpMLineIndex);
            wsClient.send(obj.toString());
        } catch (Exception e) {
            Log.e(TAG, "ice gönderilemedi", e);
        }
    }

    private void sendSwitchCamera() {
        try {
            if (wsClient != null && wsClient.isOpen()) {
                JSONObject obj = new JSONObject();
                obj.put("type", "switch-camera");
                wsClient.send(obj.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "switch-camera gönderilemedi", e);
        }
    }

    private void handleSignalMessage(String message) {
        try {
            JSONObject obj = new JSONObject(message);
            String type = obj.getString("type");

            if (type.equals("offer")) {
                if (peerConnection == null) {
                    createPeerConnection();
                }
                String sdp = obj.getString("sdp");
                peerConnection.setRemoteDescription(new SimpleSdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        MediaConstraints constraints = new MediaConstraints();
                        peerConnection.createAnswer(new SimpleSdpObserver() {
                            @Override
                            public void onCreateSuccess(SessionDescription answerSdp) {
                                peerConnection.setLocalDescription(new SimpleSdpObserver(), answerSdp);
                                sendAnswer(answerSdp.description);
                            }
                        }, constraints);
                    }
                }, new SessionDescription(SessionDescription.Type.OFFER, sdp));

            } else if (type.equals("ice-candidate")) {
                IceCandidate candidate = new IceCandidate(
                        obj.getString("sdpMid"),
                        obj.getInt("sdpMLineIndex"),
                        obj.getString("candidate"));
                if (peerConnection != null) {
                    peerConnection.addIceCandidate(candidate);
                }

            } else if (type.equals("peer-joined")) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("Kamera bağlandı, bekleniyor...");
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "mesaj işlenemedi: " + message, e);
        }
    }

    private void setStatus(String s) {
        statusText.setText(s);
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) { Log.e("SDP", "create fail: " + s); }
        @Override public void onSetFailure(String s) { Log.e("SDP", "set fail: " + s); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (peerConnection != null) peerConnection.close();
        if (wsClient != null) wsClient.close();
        remoteView.release();
    }
                  }

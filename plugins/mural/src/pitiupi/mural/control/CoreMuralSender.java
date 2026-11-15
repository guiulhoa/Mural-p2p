package pitiupi.mural.control;

import pitiupi.application.messaging.MessageDispatcher;
import pitiupi.application.messaging.SendTarget;
import pitiupi.application.messaging.TransportMode;
import pitiupi.application.presence.UserPeerView;
import pitiupi.control.ConnectionManager;
import pitiupi.models.Messages.Message;
import pitiupi.presentation.shell.LocalNetworkInfo;
import pitiupi.presentation.shell.PeerIpv4;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Envia pelo {@link MessageDispatcher} do núcleo: o plugin nunca abre socket. */
public final class CoreMuralSender implements MuralSender {

    private final ConnectionManager connectionManager;

    public CoreMuralSender(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public String userName() {
        return connectionManager.getUserName();
    }

    @Override
    public String localIp() {
        return LocalNetworkInfo.bestLocalIpv4();
    }

    @Override
    public List<PeerInfo> onlinePeers() {
        List<PeerInfo> peers = new ArrayList<>();
        for (UserPeerView view : connectionManager.getPresenceService().listPeerViews()) {
            if (view.isBroadcastTarget() || view.isSelf()) {
                continue;
            }
            view.getInetAddress().ifPresent(addr ->
                    peers.add(new PeerInfo(view.getDisplayName(), PeerIpv4.fromInetAddress(addr))));
        }
        return peers;
    }

    @Override
    public int send(Message message, String peerIp, TransportMode mode) {
        SendTarget target;
        if (peerIp == null) {
            target = SendTarget.broadcastAll();
        } else {
            InetAddress address = resolve(peerIp);
            if (address == null) {
                return 0;
            }
            target = SendTarget.singlePeer(address);
        }
        return MessageDispatcher.send(connectionManager, message, target, mode);
    }

    private static InetAddress resolve(String ip) {
        try {
            return InetAddress.getByName(PeerIpv4.normalize(ip));
        } catch (UnknownHostException ex) {
            return null;
        }
    }
}

package pitiupi.mural.plugin;

import pitiupi.api.plugin.PeerActionsRegistrar;
import pitiupi.api.plugin.PluginWorkspace;
import pitiupi.application.peeractions.PeerAction;
import pitiupi.application.peeractions.PeerActionTarget;
import pitiupi.application.peeractions.PeerTarget;
import pitiupi.application.presence.UserPeerView;
import pitiupi.control.ConnectionManager;
import pitiupi.models.Messages.Message;
import pitiupi.models.Plugin;
import pitiupi.mural.GUI.MuralPanel;
import pitiupi.mural.control.CoreMuralSender;
import pitiupi.mural.control.MuralController;
import pitiupi.mural.net.AvisoMessage;
import pitiupi.mural.net.LeituraMessage;
import pitiupi.presentation.shell.PeerIpv4;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Quadro de avisos: uma pessoa publica um texto (para a sala ou para uma
 * pessoa) e quem recebe só pode confirmar a leitura.
 */
public class MuralPlugin extends Plugin {

    private PluginWorkspace workspace;
    private MuralController controller;
    private MuralPanel panel;
    private ExecutorService io;
    private ScheduledExecutorService scheduler;

    @Override
    public void createPlugin(ConnectionManager connectionManager, PluginWorkspace workspace) {
        this.workspace = workspace;
        this.io = Executors.newSingleThreadExecutor(r -> daemon(r, "mural-io"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "mural-retry"));

        CoreMuralSender sender = new CoreMuralSender(connectionManager);
        this.controller = new MuralController(sender, io, scheduler);
        this.panel = new MuralPanel(controller, sender, workspace::setStatusMessage);
        controller.setSink(panel);

        bindShellWorkspaceContent(panel);
        installPeerActions(workspace.peerActions());
    }

    @Override
    public void installPeerActions(PeerActionsRegistrar registrar) {
        registrar.register(PeerAction.of(
                "mural.room",
                "Publicar aviso para todos",
                PeerActionTarget.ALL_PEERS,
                target -> {
                    workspace.openOrFocusTab(getIdentifier(), getDisplayName());
                    panel.focusRoom();
                }
        ));
        registrar.register(PeerAction.of(
                "mural.peer",
                "Enviar aviso para esta pessoa",
                PeerActionTarget.SINGLE_PEER,
                target -> {
                    workspace.openOrFocusTab(getIdentifier(), getDisplayName());
                    if (target instanceof PeerTarget.Single single) {
                        UserPeerView peer = single.peer();
                        peer.getInetAddress().ifPresent(addr ->
                                panel.focusPeer(peer.getDisplayName(), PeerIpv4.fromInetAddress(addr)));
                    }
                }
        ));
    }

    @Override
    public List<Class<? extends Message>> getMessages() {
        return List.of(AvisoMessage.class, LeituraMessage.class);
    }

    @Override
    public void receiveMessage(Message message) {
        SwingUtilities.invokeLater(() -> {
            if (controller == null) {
                return;
            }
            if (message instanceof AvisoMessage aviso) {
                controller.onAvisoReceived(aviso);
            } else if (message instanceof LeituraMessage leitura) {
                controller.onLeituraReceived(leitura);
            }
        });
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @Override
    public String getName() {
        return "Mural";
    }

    @Override
    public String getDisplayName() {
        return "Quadro de avisos";
    }

    @Override
    public String getDescription() {
        return "Publique um aviso; quem recebe só confirma a leitura.";
    }

    @Override
    public String getAuthor() {
        return "Guilherme Ulhoa";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getIdentifier() {
        return "pitiupi.mural";
    }
}

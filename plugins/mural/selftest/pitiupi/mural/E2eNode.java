package pitiupi.mural;

import pitiupi.api.plugin.InviteDetails;
import pitiupi.api.plugin.PeerActionsRegistrar;
import pitiupi.api.plugin.PluginWorkspace;
import pitiupi.application.messaging.TransportMode;
import pitiupi.application.peeractions.PeerAction;
import pitiupi.application.presence.UserPeerView;
import pitiupi.control.ConnectionManager;
import pitiupi.control.plugins.PluginManager;
import pitiupi.models.Plugin;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Nó para teste ponta a ponta: sobe o núcleo REAL (ConnectionManager +
 * PluginManager) sem janela, carrega o JAR do mural pelo SPI e age como
 * remetente ou leitor. Orquestrado por {@code selftest/e2e.sh}.
 *
 * <pre>
 * args: papel nome porta [destino modo]
 *   papel   = sender | reader
 *   destino = SALA | PESSOA   (só sender)
 *   modo    = MULTICAST_UDP | UNICAST_UDP | TCP   (só sender)
 * Variável de ambiente PITIUPI_HOME deve apontar para uma pasta com plugins/mural.jar
 * </pre>
 */
public final class E2eNode {

    private static final String TEXTO = "Reunião às 14h — sala 3 ✔";

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        String role = args[0];
        String name = args[1];
        int port = Integer.parseInt(args[2]);

        PluginManager pm = new PluginManager();
        ConnectionManager cm = new ConnectionManager(name, pm, port);
        pm.loadPlugins(cm, plugin -> new StubWorkspace(cm, name));

        Plugin mural = pm.getLoadedPlugins().stream()
                .filter(p -> "pitiupi.mural".equals(p.getIdentifier()))
                .findFirst()
                .orElse(null);
        if (mural == null) {
            fail(name, "plugin pitiupi.mural NÃO foi carregado do JAR (SPI?)");
        }
        log(name, "plugin carregado: " + mural.getDisplayName() + " v" + mural.getVersion()
                + " (classloader=" + mural.getClass().getClassLoader().getClass().getSimpleName() + ")");

        Field f = mural.getClass().getDeclaredField("controller");
        f.setAccessible(true);
        Object controller = f.get(mural);

        int rc = switch (role) {
            case "sender" -> runSender(name, cm, controller, args[3], args[4]);
            case "reader" -> runReader(name, controller);
            default -> 64;
        };
        cm.shutdownGracefully();
        System.exit(rc);
    }

    //remetente

    private static int runSender(String name, ConnectionManager cm, Object controller,
                                 String destino, String modo) throws Exception {
        UserPeerView peer = waitFor(30_000, () -> firstPeer(cm));
        if (peer == null) {
            fail(name, "nenhum peer apareceu na lista (presença/multicast)");
        }
        String peerIp = pitiupi.presentation.shell.PeerIpv4.fromInetAddress(peer.getInetAddress().get());
        log(name, "peer visto: " + peer.getDisplayName() + " (" + peerIp + ")");

        String targetIp = "PESSOA".equals(destino) ? peerIp : null;
        TransportMode mode = TransportMode.valueOf(modo);
        Method publish = controller.getClass().getMethod("publish", String.class, String.class, TransportMode.class);
        Object enviado = onEdt(() -> publish.invoke(controller, TEXTO, targetIp, mode));
        if (enviado == null) {
            fail(name, "publish devolveu null");
        }
        log(name, "publicado: destino=" + destino + " transporte=" + modo);

        Method readCount = enviado.getClass().getMethod("readCount");
        Integer reads = waitFor(20_000, () -> {
            int n = (Integer) onEdt(() -> readCount.invoke(enviado));
            return n > 0 ? n : null;
        });
        if (reads == null) {
            fail(name, "ninguém confirmou a leitura em 20 s");
        }
        Method readers = enviado.getClass().getMethod("readers");
        Object lista = onEdt(() -> readers.invoke(enviado));
        log(name, "E2E_OK confirmação recebida: " + lista);
        return 0;
    }

    //leitor

    private static int runReader(String name, Object controller) throws Exception {
        Method receivedList = controller.getClass().getMethod("receivedList");
        Object first = waitFor(40_000, () -> {
            List<?> l = (List<?>) onEdt(() -> receivedList.invoke(controller));
            return l.isEmpty() ? null : l.get(0);
        });
        if (first == null) {
            fail(name, "nenhum aviso chegou em 40 s");
        }
        Object aviso = first.getClass().getMethod("aviso").invoke(first);
        String text = (String) aviso.getClass().getMethod("getText").invoke(aviso);
        String author = (String) aviso.getClass().getMethod("getAuthorName").invoke(aviso);
        String id = (String) aviso.getClass().getMethod("getAvisoId").invoke(aviso);
        log(name, "recebeu aviso de " + author + ": \"" + text + "\"");
        if (!TEXTO.equals(text)) {
            fail(name, "texto chegou diferente do enviado");
        }

        Method confirm = controller.getClass().getMethod("confirmRead", String.class);
        onEdt(() -> confirm.invoke(controller, id));
        Method isRead = first.getClass().getMethod("isRead");
        Boolean lido = waitFor(10_000, () -> (Boolean) isRead.invoke(first) ? Boolean.TRUE : null);
        if (lido == null) {
            fail(name, "confirmação não foi entregue ao núcleo");
        }
        log(name, "E2E_OK leitura confirmada");
        Thread.sleep(6_000); // deixa os reenvios UDP saírem e o remetente terminar
        return 0;
    }

    //utilitários

    private static UserPeerView firstPeer(ConnectionManager cm) {
        for (UserPeerView v : cm.getPresenceService().listPeerViews()) {
            if (!v.isSelf() && !v.isBroadcastTarget() && v.getInetAddress().isPresent()) {
                return v;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T onEdt(Callable<Object> call) throws Exception {
        Object[] box = new Object[1];
        Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                box[0] = call.call();
            } catch (Exception ex) {
                err[0] = ex;
            }
        });
        if (err[0] != null) {
            throw err[0];
        }
        return (T) box[0];
    }

    private static <T> T waitFor(long timeoutMs, Callable<T> probe) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            T v = probe.call();
            if (v != null) {
                return v;
            }
            Thread.sleep(300);
        }
        return null;
    }

    private static void log(String who, String msg) {
        System.out.println("[" + who + "] " + msg);
        System.out.flush();
    }

    private static void fail(String who, String msg) {
        log(who, "E2E_FALHA " + msg);
        System.exit(2);
    }

    /** Workspace mínimo: o que a shell daria, sem janela. */
    private static final class StubWorkspace implements PluginWorkspace {
        private final ConnectionManager cm;
        private final String who;
        final List<PeerAction> actions = new ArrayList<>();

        StubWorkspace(ConnectionManager cm, String who) {
            this.cm = cm;
            this.who = who;
        }

        @Override public ConnectionManager connectionManager() { return cm; }
        @Override public PeerActionsRegistrar peerActions() { return actions::add; }
        @Override public void openOrFocusTab(String id, String title, JComponent content) { }
        @Override public void setStatusMessage(String text) { log(who, "status: " + text); }
        @Override public void deliverInviteAccepted(InviteDetails invite) { }
    }
}

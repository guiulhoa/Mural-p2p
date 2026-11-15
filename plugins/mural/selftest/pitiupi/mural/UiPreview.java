package pitiupi.mural;

import pitiupi.application.messaging.TransportMode;
import pitiupi.mural.GUI.MuralPanel;
import pitiupi.mural.control.MuralController;
import pitiupi.mural.control.MuralSender;
import pitiupi.mural.net.AvisoMessage;
import pitiupi.mural.net.Escopo;
import pitiupi.mural.net.LeituraMessage;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.Executor;

/**
 * Gera imagens da tela do mural com dados de exemplo (precisa de um display,
 * por exemplo {@code xvfb-run}). Uso: UiPreview pasta-de-saida
 */
public final class UiPreview {

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : ".");
        out.mkdirs();
        SwingUtilities.invokeAndWait(() -> {
            try {
                build(out);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        System.exit(0);
    }

    private static void build(File out) throws Exception {
        MuralProtocolTest.FakeSender net = new MuralProtocolTest.FakeSender("Ana", "192.168.0.10");
        net.peers.add(new MuralSender.PeerInfo("Bia", "192.168.0.11"));
        net.peers.add(new MuralSender.PeerInfo("Caio", "192.168.0.12"));
        net.peers.add(new MuralSender.PeerInfo("Dani", "192.168.0.13"));
        Executor direct = Runnable::run;
        MuralController c = new MuralController(net, direct, null);

        // enviados
        var e1 = c.publish("Prova de Redes adiada para sexta-feira, 20/11, às 14h, na sala 3.\nTragam calculadora.",
                null, TransportMode.MULTICAST_UDP);
        c.publish("Bia, seu trabalho está no git. Conferir o README!", "192.168.0.11", TransportMode.UNICAST_UDP);
        c.onLeituraReceived(new LeituraMessage(e1.avisoId(), c.ownId(), "r1", "Bia", "192.168.0.11", System.currentTimeMillis()));
        c.onLeituraReceived(new LeituraMessage(e1.avisoId(), c.ownId(), "r2", "Caio", "192.168.0.12", System.currentTimeMillis()));

        // recebidos: um não lido, um já lido, um com HTML malicioso no nome e no texto
        c.onAvisoReceived(new AvisoMessage("x1", "o1", "Prof. Flávio", "192.168.0.50",
                "Entrega do plugin até domingo, 23h59, via git. Não esqueçam o README com o protocolo.",
                System.currentTimeMillis() - 600_000, Escopo.SALA, "MULTICAST_UDP"));
        c.onAvisoReceived(new AvisoMessage("x2", "o2", "Caio", "192.168.0.12",
                "Quem quiser carona para a UFSJ amanhã, me avise.",
                System.currentTimeMillis() - 300_000, Escopo.SALA, "MULTICAST_UDP"));
        c.confirmRead("x2");
        c.onAvisoReceived(new AvisoMessage("x3", "o3", "<html><h1>HACK</h1></html>", "192.168.0.99",
                "<html><b>negrito?</b><img src='http://x/y.png'></html> texto puro",
                System.currentTimeMillis() - 60_000, Escopo.PESSOA, "UNICAST_UDP"));

        MuralPanel panel = new MuralPanel(c, net, s -> { });
        c.setSink(panel);

        JFrame frame = new JFrame("Mural");
        frame.setContentPane(panel);
        frame.setSize(820, 620);
        frame.setVisible(true);
        frame.validate();
        shot(frame, new File(out, "mural-recebidos.png"));

        // aba Enviados
        var tabs = (javax.swing.JTabbedPane) find(panel, javax.swing.JTabbedPane.class);
        tabs.setSelectedIndex(1);
        frame.validate();
        shot(frame, new File(out, "mural-enviados.png"));
        frame.dispose();
    }

    private static java.awt.Component find(java.awt.Container root, Class<?> type) {
        for (java.awt.Component comp : root.getComponents()) {
            if (type.isInstance(comp)) {
                return comp;
            }
            if (comp instanceof java.awt.Container cont) {
                java.awt.Component r = find(cont, type);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void shot(JFrame frame, File file) throws Exception {
        BufferedImage img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        frame.paint(img.getGraphics());
        ImageIO.write(img, "png", file);
        System.out.println("gerado: " + file);
    }
}

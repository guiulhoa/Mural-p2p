package pitiupi.mural.GUI;

import pitiupi.application.messaging.PluginTransportCatalog;
import pitiupi.application.messaging.TransportMode;
import pitiupi.mural.control.AvisoEnviado;
import pitiupi.mural.control.AvisoRecebido;
import pitiupi.mural.control.MuralController;
import pitiupi.mural.control.MuralSender;
import pitiupi.mural.control.MuralSink;
import pitiupi.mural.net.AvisoMessage;
import pitiupi.mural.net.Escopo;
import pitiupi.presentation.shell.ui.ShellUi;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Tela do mural: escrever (em cima), avisos recebidos e avisos enviados (abas).
 *
 * <p>O texto dos avisos vem da rede. Por isso nada aqui usa HTML: o corpo é um
 * {@link JTextArea} e os {@link JLabel} têm o HTML desligado, para ninguém
 * injetar marcação na tela dos colegas.</p>
 */
public final class MuralPanel extends JPanel implements MuralSink {

    /** Opção do combo de destino. {@code ip == null} é a sala toda. */
    private record Destino(String label, String ip) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final Destino SALA = new Destino("Todos na sala", null);
    private static final DateTimeFormatter HORA =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final MuralController controller;
    private final MuralSender sender;
    private final Consumer<String> statusSink;

    private final JTextArea input = new JTextArea(4, 40);
    private final JLabel counter = new JLabel(" ");
    private final JComboBox<Destino> destinoCombo = new JComboBox<>();
    private final JComboBox<TransportMode> transportCombo = new JComboBox<>();
    private final JButton publishButton = new JButton("Publicar aviso");
    private final JTabbedPane tabs = new JTabbedPane();
    private final ListPanel receivedList = new ListPanel();
    private final ListPanel sentList = new ListPanel();

    public MuralPanel(MuralController controller, MuralSender sender, Consumer<String> statusSink) {
        this.controller = controller;
        this.sender = sender;
        this.statusSink = statusSink != null ? statusSink : s -> { };

        setLayout(new BorderLayout(0, 6));
        add(buildComposer(), BorderLayout.NORTH);

        tabs.addTab("Recebidos", wrapScroll(receivedList));
        tabs.addTab("Enviados", wrapScroll(sentList));
        add(tabs, BorderLayout.CENTER);

        refreshDestinos(null);
        reloadTransports();
        updateComposerState();
        rebuildReceived();
        rebuildSent();
    }

    // ------------------------------------------------------------ API p/ plugin

    /** Seleciona "Todos na sala" como destino. */
    public void focusRoom() {
        refreshDestinos(null);
        input.requestFocusInWindow();
    }

    /** Seleciona uma pessoa como destino (ação da lista lateral). */
    public void focusPeer(String name, String ip) {
        refreshDestinos(ip, name);
        input.requestFocusInWindow();
    }

    // ----------------------------------------------------------------- compor

    private JPanel buildComposer() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        ShellUi.applySectionTitleBorder(panel, "Novo aviso");

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateComposerState(); }
            @Override public void removeUpdate(DocumentEvent e) { updateComposerState(); }
            @Override public void changedUpdate(DocumentEvent e) { updateComposerState(); }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "publicar");
        input.getActionMap().put("publicar", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { publish(); }
        });
        panel.add(new JScrollPane(input), BorderLayout.CENTER);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.add(new JLabel("Para:"));
        destinoCombo.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) { refreshDestinos(selectedIp()); }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
        destinoCombo.addActionListener(e -> reloadTransports());
        row.add(destinoCombo);
        row.add(new JLabel("Transporte:"));
        row.add(transportCombo);
        publishButton.addActionListener(e -> publish());
        row.add(publishButton);
        row.add(counter);

        JPanel south = new JPanel(new BorderLayout());
        south.add(row, BorderLayout.CENTER);
        south.add(ShellUi.mutedLabel("Ctrl+Enter publica. Quem recebe só pode confirmar a leitura."), BorderLayout.SOUTH);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private void publish() {
        String ip = selectedIp();
        TransportMode mode = (TransportMode) transportCombo.getSelectedItem();
        AvisoEnviado enviado = controller.publish(input.getText(), ip, mode);
        if (enviado != null) {
            input.setText("");
            tabs.setSelectedIndex(1);
        }
    }

    private void updateComposerState() {
        int len = input.getText().strip().length();
        int raw = input.getText().length();
        counter.setText(raw + "/" + AvisoMessage.MAX_TEXT);
        boolean tooLong = raw > AvisoMessage.MAX_TEXT;
        counter.setForeground(tooLong ? Color.RED : javax.swing.UIManager.getColor("Label.foreground"));
        publishButton.setEnabled(len > 0 && !tooLong);
    }

    private String selectedIp() {
        Destino d = (Destino) destinoCombo.getSelectedItem();
        return d == null ? null : d.ip();
    }

    private void refreshDestinos(String selectIp) {
        refreshDestinos(selectIp, null);
    }

    /** Recarrega a lista de pessoas online e tenta manter/selecionar {@code selectIp}. */
    private void refreshDestinos(String selectIp, String nameIfMissing) {
        DefaultComboBoxModel<Destino> model = new DefaultComboBoxModel<>();
        model.addElement(SALA);
        Destino wanted = selectIp == null ? SALA : null;
        for (MuralSender.PeerInfo p : sender.onlinePeers()) {
            Destino d = new Destino(p.name() + " (" + p.ip() + ")", p.ip());
            model.addElement(d);
            if (Objects.equals(selectIp, p.ip())) {
                wanted = d;
            }
        }
        if (wanted == null) { // pessoa fora da lista agora: mantém como opção
            String who = nameIfMissing != null ? nameIfMissing : selectIp;
            wanted = new Destino(who + " (" + selectIp + ")", selectIp);
            model.addElement(wanted);
        }
        // setModel + setSelectedItem disparam actionPerformed -> reloadTransports
        destinoCombo.setModel(model);
        destinoCombo.setSelectedItem(wanted);
    }

    private void reloadTransports() {
        boolean sala = selectedIp() == null;
        List<TransportMode> modes = sala
                ? PluginTransportCatalog.forBroadcast(PluginTransportCatalog.PluginKind.CHAT)
                : PluginTransportCatalog.forSinglePeer(PluginTransportCatalog.PluginKind.CHAT);
        Object keep = transportCombo.getSelectedItem();
        DefaultComboBoxModel<TransportMode> model = new DefaultComboBoxModel<>(modes.toArray(new TransportMode[0]));
        transportCombo.setModel(model);
        transportCombo.setSelectedItem(keep != null && modes.contains(keep) ? keep : modes.get(0));
        transportCombo.setToolTipText(((TransportMode) transportCombo.getSelectedItem()).describeForUi(sala));
    }

    // ------------------------------------------------------------------ listas

    private void rebuildReceived() {
        receivedList.removeAll();
        List<AvisoRecebido> list = controller.receivedList();
        if (list.isEmpty()) {
            receivedList.add(emptyLabel("Nenhum aviso recebido ainda."));
        }
        for (AvisoRecebido r : list) {
            receivedList.add(receivedCard(r));
            receivedList.add(Box.createVerticalStrut(6));
        }
        int unread = controller.unreadCount();
        tabs.setTitleAt(0, unread > 0 ? "Recebidos (" + unread + " não lidos)" : "Recebidos");
        receivedList.revalidate();
        receivedList.repaint();
    }

    private void rebuildSent() {
        sentList.removeAll();
        List<AvisoEnviado> list = controller.sentList();
        if (list.isEmpty()) {
            sentList.add(emptyLabel("Você ainda não publicou nenhum aviso."));
        }
        for (AvisoEnviado e : list) {
            sentList.add(sentCard(e));
            sentList.add(Box.createVerticalStrut(6));
        }
        sentList.revalidate();
        sentList.repaint();
    }

    private JComponent receivedCard(AvisoRecebido r) {
        AvisoMessage a = r.aviso();
        String who = a.getAuthorName() == null || a.getAuthorName().isBlank() ? a.getAuthorIp() : a.getAuthorName();
        String header = who + " (" + a.getAuthorIp() + ") · " + HORA.format(Instant.ofEpochMilli(a.getCreatedAtMillis()))
                + " · " + transportLabel(a.getTransport())
                + (a.getEscopo() == Escopo.PESSOA ? " · só para você" : " · para todos");

        JComponent footer;
        if (r.isRead()) {
            footer = plainLabel("✔ Lido às " + HORA.format(Instant.ofEpochMilli(r.readAtMillis())));
        } else if (r.isAckPending()) {
            footer = plainLabel("Enviando confirmação…");
        } else {
            JButton confirm = new JButton("Confirmar leitura");
            confirm.addActionListener(e -> controller.confirmRead(a.getAvisoId()));
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            p.add(confirm);
            footer = p;
        }
        return card(header, a.getText(), footer, !r.isRead());
    }

    private JComponent sentCard(AvisoEnviado e) {
        String header = "Você → " + e.destinoLabel() + " · " + HORA.format(Instant.ofEpochMilli(e.createdAtMillis()))
                + " · " + e.transport().getLabel();

        String envio;
        if (e.isSending()) {
            envio = "Enviando…";
        } else if (e.deliveries() == 0) {
            envio = "✖ Falhou o envio";
        } else {
            envio = "Enviado";
        }
        int n = e.readCount();
        String leituras;
        if (n == 0) {
            leituras = "ninguém confirmou ainda (de " + e.expectedReaders() + " online no envio)";
        } else {
            String nomes = e.readers().stream()
                    .map(l -> l.name() + " " + HORA.format(Instant.ofEpochMilli(l.readAtMillis())))
                    .collect(Collectors.joining(", "));
            leituras = "leram " + n + (e.expectedReaders() >= n ? " de " + e.expectedReaders() : "") + ": " + nomes;
        }
        return card(header, e.text(), plainLabel(envio + " · " + leituras), false);
    }

    private static JComponent card(String header, String text, JComponent footer, boolean highlight) {
        // BoxLayout vertical estica o que tem máximo grande: limita a altura ao preferido.
        JPanel card = new JPanel(new BorderLayout(0, 4)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(highlight ? new Color(0x3B82F6) : Color.GRAY, highlight ? 2 : 1),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        JLabel head = plainLabel(header);
        head.setFont(head.getFont().deriveFont(java.awt.Font.BOLD));
        card.add(head, BorderLayout.NORTH);

        JTextArea body = new JTextArea(text);
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setOpaque(false);
        body.setBorder(null);
        card.add(body, BorderLayout.CENTER);

        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    /** JLabel que nunca interpreta HTML, mesmo que o texto comece com {@code <html>}. */
    private static JLabel plainLabel(String text) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", Boolean.TRUE);
        return label;
    }

    private static JLabel emptyLabel(String text) {
        JLabel label = plainLabel(text);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        label.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return label;
    }

    private static String transportLabel(String modeName) {
        try {
            return TransportMode.valueOf(modeName).getLabel();
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    private static JScrollPane wrapScroll(ListPanel list) {
        JScrollPane scroll = new JScrollPane(list);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    // -------------------------------------------------------------- MuralSink

    @Override
    public void recebidoAtualizado(AvisoRecebido aviso) {
        SwingUtilities.invokeLater(this::rebuildReceived);
    }

    @Override
    public void enviadoAtualizado(AvisoEnviado aviso) {
        SwingUtilities.invokeLater(this::rebuildSent);
    }

    @Override
    public void status(String text) {
        SwingUtilities.invokeLater(() -> statusSink.accept(text));
    }

    /** Coluna de cartões que acompanha a largura do viewport (o texto quebra linha). */
    private static final class ListPanel extends JPanel implements Scrollable {
        ListPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 64; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}

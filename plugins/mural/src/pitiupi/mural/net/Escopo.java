package pitiupi.mural.net;

/**
 * Para quem o aviso foi publicado.
 * <ul>
 *   <li>{@link #SALA}: grupo (multicast, ou uma cópia unicast/TCP por pessoa online).</li>
 *   <li>{@link #PESSOA}: uma única pessoa (unicast).</li>
 * </ul>
 */
public enum Escopo {
    SALA,
    PESSOA
}

package com.jcraft.jogg;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Repository-owned stand-in for jogg's logical bitstream layer.
 *
 * <p>Accepts the pages {@link SyncState} produced and hands their packets out one at a time, which is
 * the only behaviour {@code sound/void} depends on.
 */
public final class StreamState {

    private final Deque<Object> pending = new ArrayDeque<>();
    private int serialno;

    public void init(int serialno) {
        this.serialno = serialno;
        pending.clear();
    }

    public int pagein(Page page) {
        if (page.serialno != serialno) {
            return -1;
        }
        pending.addAll(page.packets);
        return 0;
    }

    public int packetout(Packet packet) {
        if (pending.isEmpty()) {
            return 0;
        }
        packet.payload = pending.removeFirst();
        return 1;
    }

    public void clear() {
        pending.clear();
    }
}

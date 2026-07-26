package com.jcraft.jogg;

import java.util.List;

/**
 * Repository-owned stand-in for the Ogg page the installed decoder pages out.
 *
 * <p>It carries only what {@code sound/void}'s call sequence reads back: a serial number and the
 * end-of-stream flag. The packet payloads ride along so {@link StreamState} has something to hand out.
 */
public final class Page {
    List<Object> packets = List.of();
    int serialno;
    int eos;

    public int serialno() {
        return serialno;
    }

    public int eos() {
        return eos;
    }
}

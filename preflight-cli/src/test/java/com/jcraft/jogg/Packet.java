package com.jcraft.jogg;

/**
 * Repository-owned stand-in for an Ogg packet.
 *
 * <p>The real packet carries encoded bytes that jorbis decodes. The stand-in carries the already
 * decoded payload instead — a header marker or one block of samples — because the point is to exercise
 * the harness's paging and packet loop, not to reimplement Vorbis.
 */
public final class Packet {
    public Object payload;
}
